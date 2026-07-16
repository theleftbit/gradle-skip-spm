package com.theleftbit.skipspm

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import javax.inject.Inject

/**
 * Runs `skip export` to build the SwiftPM package into Android AARs, then normalizes each AAR's
 * manifest namespace. Up-to-date when the Swift sources, the SPM manifest, and the ABIs are
 * unchanged — so it only re-runs when the shared package actually changes.
 */
@DisableCachingByDefault(because = "Drives an external skip/Swift build; its native outputs aren't relocatable cache entries.")
abstract class SkipExportTask : DefaultTask() {

    /** Package location — NOT a tracked input (it contains the churning `.build/`). */
    @get:Internal
    abstract val packageDir: DirectoryProperty

    /** Tracked input: the Swift sources. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: DirectoryProperty

    /** Tracked input: the SPM manifest + lockfile. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manifests: ConfigurableFileCollection

    @get:Input
    abstract val module: Property<String>

    @get:Input
    abstract val buildMode: Property<String>

    @get:Input
    abstract val abis: ListProperty<String>

    @get:Input
    abstract val namespacePrefix: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /** Whether to delete stale transform-cache entries after the export (see [pruneStaleTransformEntries]). */
    @get:Internal
    abstract val pruneStaleTransforms: Property<Boolean>

    /** Gradle user home, where the transform caches to prune live (`<home>/caches/<version>/transforms`). */
    @get:Internal
    abstract val gradleUserHomeDir: Property<File>

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun export() {
        val pkg = packageDir.get().asFile
        val out = outputDir.get().asFile
        out.mkdirs()
        // Hash the previous AARs before anything touches them: the transform-cache prune below must
        // only target AARs whose bytes actually changed in this export (see pruneStaleTransformEntries).
        val previousHashes = out.listFiles { f -> f.extension == "aar" }
            ?.associate { it.name to it.contentHash() }.orEmpty()
        // Drop stale AARs so a removed module's leftover can't linger and get consumed.
        out.listFiles { f -> f.extension == "aar" }?.forEach { it.delete() }

        val command = mutableListOf(
            resolveSkipExecutable(), "export",
            "--module", module.get(),
            "--project", pkg.name,
            "--no-export-project",
            "-d", out.absolutePath,
            "--${buildMode.get()}",
        )
        abis.get().forEach { abi ->
            command.add("--arch")
            command.add(abi)
        }

        // skip export resolves the *Android* dependency graph (SKIP_ENABLED=1), which adds the
        // Skip/Android bridge pins on top of the iOS resolution committed to Package.resolved —
        // leaving the working tree dirty after every build. Snapshot the lockfile and restore it
        // afterward so the tree stays clean (this is what the old deploy script's
        // `trap cleanup_package_resolved` did). A byte snapshot, not `git restore`, so it also works
        // for a remote-cloned package and doesn't assume git is present.
        val resolvedLock = File(pkg, "Package.resolved")
        val lockBackup = if (resolvedLock.isFile) resolvedLock.readBytes() else null
        try {
            runExportWithRetry(pkg, command)
        } finally {
            if (lockBackup != null && resolvedLock.isFile &&
                !resolvedLock.readBytes().contentEquals(lockBackup)
            ) {
                resolvedLock.writeBytes(lockBackup)
            }
        }

        val prefix = namespacePrefix.get()
        val mode = buildMode.get()
        val aars = out.listFiles { f -> f.extension == "aar" }?.toList().orEmpty()
        aars.forEach { aar ->
            normalizeManifest(aar, manifestPackageFor(aar, mode, prefix))
        }

        if (pruneStaleTransforms.getOrElse(true)) {
            // Prune only AARs whose bytes PROVABLY changed: a previous hash exists and differs (or
            // the AAR disappeared). skip's underlying Android build is reproducible, so a re-export
            // very often reproduces byte-identical AARs whose transform entries are still LIVE —
            // deleting those hands dangling paths to a warm daemon (observed as "unresolved
            // reference" for every shared class in consumers). Crucially, an AAR with NO previous
            // hash (post-clean, or a first build against an already-populated cache) must never
            // prune: the just-regenerated bytes can be identical to what existing entries hold.
            val currentHashes = aars.associate { it.name to it.contentHash() }
            val staleNames = previousHashes.keys.filter { previousHashes[it] != currentHashes[it] }
            if (staleNames.isNotEmpty()) {
                runCatching { pruneStaleTransformEntries(staleNames, mode, prefix) }
                    .onFailure { logger.warn("skipSpm: pruning stale transform-cache entries failed (build unaffected): $it") }
            }
        }
    }

    /**
     * Deletes Gradle artifact-transform cache entries for the given AAR file names.
     *
     * AGP consumes an AAR by *exploding* it (classes.jar + every native `.so`) into a
     * content-addressed entry under `~/.gradle/caches/<version>/transforms/<hash>/transformed/`.
     * When an export changes an AAR's bytes, the entries for the previous bytes become garbage —
     * but Gradle's own cleanup only removes entries unused for ~7 days, which under active
     * shared-package development accumulates gigabytes per day (a single exploded umbrella AAR can
     * be ~0.5 GB). This runs right after a successful export, restricted by the caller to AARs
     * whose content actually CHANGED in this export (plus removed ones): their old-content entries
     * are stale by construction, while byte-identical re-exports keep their still-live entries —
     * deleting an entry the running daemon still references breaks the build (dangling classpath
     * paths, no re-transform). Only entries attributable to these AARs by output name are touched;
     * callers treat failures as non-fatal.
     */
    private fun pruneStaleTransformEntries(aarNames: Collection<String>, mode: String, prefix: String) {
        if (aarNames.isEmpty()) return
        val caches = File(gradleUserHomeDir.get(), "caches")
        // Version-scoped `caches/<gradleVersion>/transforms` (Gradle 8.8+) plus any legacy
        // cross-version `caches/transforms-N` (older Gradle) still on disk.
        val transformRoots = caches.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { dir ->
            if (dir.name.startsWith("transforms-")) dir
            else File(dir, "transforms").takeIf { it.isDirectory }
        }
        // A transform names its output after its input, so entries for `USLive-release.aar` show up
        // as `transformed/USLive-release/` (exploded), `USLive-release.aar`,
        // `USLive-release-runtime.jar`, … plus R-class entries named after the rewritten manifest
        // package (`com.foo.shared.uslive`, `com.foo.shared.uslive-r.txt`).
        val stems = aarNames.flatMap { name ->
            val base = name.removeSuffix(".aar")
            listOf(base, manifestPackageFor(base, mode, prefix))
        }.toSet()
        fun matches(name: String) = stems.any { name == it || name.startsWith("$it.") || name.startsWith("$it-") }

        // Never prune a young entry, even for changed bytes: a live daemon caches transform
        // identity→workspace in memory for its whole lifetime WITHOUT re-checking existence, so if
        // the content later reverts to an identity the daemon already loaded (branch flip-flop,
        // stash/pop — cheap with skip's reproducible output), a deleted entry resurfaces as a
        // dangling classpath path. Old entries are what actually bloats the cache (days of churn);
        // fresh churn self-prunes once it ages past this window on a later export.
        val ageCutoffMillis = System.currentTimeMillis() - MIN_PRUNE_AGE_HOURS * 60L * 60L * 1000L
        var pruned = 0
        var freedBytes = 0L
        transformRoots.forEach { root ->
            root.listFiles { f -> f.isDirectory }.orEmpty().forEach { entry ->
                val outputs = File(entry, "transformed").listFiles().orEmpty()
                if (outputs.isNotEmpty() && outputs.all { matches(it.name) } &&
                    entry.lastModified() < ageCutoffMillis
                ) {
                    val size = entry.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
                    if (entry.deleteRecursively()) {
                        pruned++
                        freedBytes += size
                    }
                }
            }
        }
        if (pruned > 0) {
            logger.lifecycle(
                "skipSpm: pruned $pruned stale transform-cache entries " +
                    "(${freedBytes / (1024 * 1024)} MB) for changed AARs: ${aarNames.sorted().joinToString(", ")}",
            )
        }
    }

    /** SHA-256 of the file's bytes, streamed. */
    private fun File.contentHash(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Absolute path to the `skip` CLI. Gradle resolves a bare command name against the *daemon's*
     * own PATH, which is reduced when Android Studio is launched from the Dock (launchd) — so a bare
     * "skip" isn't found, even though we widen PATH for skip's children below (that widening only
     * applies once skip has launched, so skip can find `gradle`). Honor an explicit `skip.path`
     * system property / `SKIP_PATH` env override, else probe the usual install locations, else fall
     * back to "skip" (terminal/CI, where the full shell PATH is present).
     */
    private fun resolveSkipExecutable(): String {
        System.getProperty("skip.path")?.takeIf { it.isNotBlank() }?.let { return it }
        System.getenv("SKIP_PATH")?.takeIf { it.isNotBlank() }?.let { return it }
        val home = System.getProperty("user.home").orEmpty()
        return listOf("/opt/homebrew/bin/skip", "/usr/local/bin/skip", "$home/.swiftly/bin/skip")
            .firstOrNull { File(it).canExecute() } ?: "skip"
    }

    /**
     * Runs `skip export`, retrying when it fails with a *transient* SPM/git fetch error. On a cold
     * checkout (e.g. CI) skip resolves the whole package graph from remote repos, so a single
     * network hiccup ("Couldn't fetch updates from remote repositories") would otherwise fail the
     * build. Compile/config failures are surfaced immediately — only network-shaped failures retry,
     * so a real Swift error never burns three full export runs.
     */
    private fun runExportWithRetry(pkg: File, command: List<String>) {
        var attempt = 1
        while (true) {
            // Tee the process output to the build console AND a buffer so we can classify a failure.
            val outBuf = ByteArrayOutputStream()
            val errBuf = ByteArrayOutputStream()
            val result = execOps.exec {
                workingDir(pkg.parentFile)
                commandLine(command)
                // skip's native build is gated on SKIP_ENABLED; mirror what the deploy script exported.
                environment("SKIP_ENABLED", "1")
                // skip (resolved to an absolute path above) shells out to the Homebrew `gradle`, so
                // widen the PATH skip passes to its children: the Gradle daemon's own PATH is reduced
                // when Android Studio launches it via launchd, which would otherwise hide `gradle`.
                val inheritedPath = System.getenv("PATH").orEmpty()
                environment(
                    "PATH",
                    listOf("/opt/homebrew/bin", "/usr/local/bin", inheritedPath)
                        .filter { it.isNotEmpty() }
                        .joinToString(":"),
                )
                isIgnoreExitValue = true
                standardOutput = TeeOutputStream(System.out, outBuf)
                errorOutput = TeeOutputStream(System.err, errBuf)
            }
            if (result.exitValue == 0) return

            val combined = outBuf.toString() + errBuf.toString()
            val looksTransient = TRANSIENT_FETCH_HINTS.any { combined.contains(it, ignoreCase = true) }
            if (looksTransient && attempt < MAX_EXPORT_ATTEMPTS) {
                val backoffSeconds = attempt * RETRY_BACKOFF_SECONDS
                logger.warn(
                    "skip export failed on a likely-transient fetch error " +
                        "(attempt $attempt/$MAX_EXPORT_ATTEMPTS); retrying in ${backoffSeconds}s…",
                )
                Thread.sleep(backoffSeconds * 1000L)
                attempt++
                continue
            }
            throw GradleException(
                "skip export failed (exit ${result.exitValue}) after $attempt attempt(s)." +
                    if (looksTransient) " Last failure looked like a network/fetch error." else "",
            )
        }
    }

    /** `AppData-debug.aar` + prefix `com.x.shared` → `com.x.shared.appdata`. */
    private fun manifestPackageFor(aar: File, mode: String, prefix: String): String =
        manifestPackageFor(aar.nameWithoutExtension, mode, prefix)

    /** `AppData-debug` + prefix `com.x.shared` → `com.x.shared.appdata`. */
    private fun manifestPackageFor(aarBaseName: String, mode: String, prefix: String): String {
        val base = aarBaseName.removeSuffix("-$mode")
        val suffix = base.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "module" }
        return "$prefix.$suffix"
    }

    /** Rewrite the AAR's `AndroidManifest.xml` `package` attribute in place (via zip filesystem). */
    private fun normalizeManifest(aar: File, newPackage: String) {
        FileSystems.newFileSystem(URI.create("jar:${aar.toURI()}"), emptyMap<String, Any>()).use { fs ->
            val manifest = fs.getPath("AndroidManifest.xml")
            if (Files.exists(manifest)) {
                val content = Files.readString(manifest)
                val rewritten = content.replace(Regex("package=\"[^\"]*\""), "package=\"$newPackage\"")
                if (rewritten != content) {
                    Files.writeString(manifest, rewritten)
                }
            }
        }
    }

    /** Fans writes out to two streams (the live console + a capture buffer); never closes either. */
    private class TeeOutputStream(
        private val primary: OutputStream,
        private val secondary: OutputStream,
    ) : OutputStream() {
        override fun write(b: Int) {
            primary.write(b); secondary.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            primary.write(b, off, len); secondary.write(b, off, len)
        }

        override fun flush() {
            primary.flush(); secondary.flush()
        }
    }

    private companion object {
        const val MAX_EXPORT_ATTEMPTS = 3
        const val RETRY_BACKOFF_SECONDS = 10

        /**
         * Minimum age before a stale transform entry may be pruned. Must comfortably exceed a
         * working session's daemon lifetime so an in-memory identity→workspace reference can never
         * point at a pruned dir (see [pruneStaleTransformEntries]); Gradle daemons idle out after
         * 3h, but active use keeps them alive all day.
         */
        const val MIN_PRUNE_AGE_HOURS = 24

        /** Substrings (apostrophe-free, so skip's curly `’` doesn't matter) that mark a retryable fetch failure. */
        val TRANSIENT_FETCH_HINTS = listOf(
            "fetch updates from remote", // skip/SPM: "Couldn't fetch updates from remote repositories"
            "remote repositor",
            "failed to fetch", "failed to clone", "error cloning",
            "could not resolve host", "resolve host",
            "failed to connect", "connection refused", "connection reset", "connection timed out",
            "timed out", "operation timed out",
            "network is unreachable", "network error",
            "unable to access", // git: "fatal: unable to access '…'"
            "ssl_error", "ssl error", "tls",
            "the requested url returned error",
        )
    }
}

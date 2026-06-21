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

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun export() {
        val pkg = packageDir.get().asFile
        val out = outputDir.get().asFile
        out.mkdirs()
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
        out.listFiles { f -> f.extension == "aar" }?.forEach { aar ->
            normalizeManifest(aar, manifestPackageFor(aar, mode, prefix))
        }
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
    private fun manifestPackageFor(aar: File, mode: String, prefix: String): String {
        val base = aar.nameWithoutExtension.removeSuffix("-$mode")
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

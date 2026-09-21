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
import java.io.IOException
import java.io.OutputStream
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.inject.Inject

/**
 * Runs `skip export` to build the SwiftPM package into Android AARs, then normalizes each AAR's
 * manifest namespace. Up-to-date when the Swift sources, the SPM manifest, and the ABIs are
 * unchanged — so it only re-runs when the shared package actually changes.
 *
 * `skip export` is itself incremental over the package's `.build/` scratch, and that state can go
 * stale in ways Gradle's input tracking cannot see: anything that re-resolves the package outside
 * this task (`skip android test`, an external `git restore` of Package.resolved) mutates the shared
 * `.build/`, after which an incremental export can reference vendored transpiler files that no
 * longer exist, or package "husk" AARs with no compiled classes. Both symptoms are detected here
 * and self-healed: the transpiler outputs (`.build/plugins/outputs`) are deleted and the export
 * retried once from scratch.
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

    /**
     * What to do when the `skip` CLI version drifts from the version the package declares
     * (see [selectSkipExecutable]): `"install"` (default), `"warn"`, `"fail"`, or `"off"`.
     */
    @get:Input
    abstract val skipVersionCheck: Property<String>

    @get:Internal
    abstract val offline: Property<Boolean>

    init {
        skipVersionCheck.convention("install")
        offline.convention(project.gradle.startParameter.isOffline)
    }

    /**
     * `bin` directory of the Gradle installation running this build (the wrapper dist when
     * launched via `gradlew`/Android Studio). Prepended to the PATH skip's children see, so the
     * nested per-module builds `skip export` spawns via a bare `gradle` run the same Gradle
     * version as the outer build — instead of whatever `gradle` happens to be on PATH (Homebrew,
     * an image-baked one), or nothing at all in CI containers. Not an input — it selects a
     * toolchain path, never changes the exported AARs.
     */
    @get:Internal
    abstract val gradleInstallBinDir: Property<String>

    /**
     * Whether skip's nested `gradle` builds may use the Gradle build cache (see
     * [SkipSpmExtension.childGradleBuildCache]). Default false. Not an input — caching policy,
     * not output content.
     */
    @get:Internal
    abstract val childGradleBuildCache: Property<Boolean>

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun export() {
        val pkg = packageDir.get().asFile
        val out = outputDir.get().asFile
        val expected = expectedSkipVersion(
            File(pkg, "Package.swift").takeIf { it.isFile }?.readText(),
            File(pkg, "Package.resolved").takeIf { it.isFile }?.readText(),
        )
        val installedExecutable = resolveSkipExecutable()
        val executable = selectSkipExecutable(expected, installedExecutable)
        val managedSkipBin = if (executable != installedExecutable) File(executable).parent else null
        try {
            exportWithSelfHealing(pkg, out, executable, managedSkipBin)
        } catch (failure: SkipExportFailure) {
            if (skipVersionCheck.get() != "install" || offline.get()) throw failure
            val required = failure.requiredVersion ?: throw failure
            val current = readSkipVersion(executable) ?: throw failure
            if (compareDottedVersions(required.version, current) <= 0) throw failure
            logger.warn(
                "skip export resolved Skip ${required.version}, newer than CLI $current; " +
                    "selecting that version before one final attempt.",
            )
            val upgraded = try {
                SkipCliInstaller.selectOrInstall(
                    required, current, false, { readSkipVersion(it.absolutePath) }, ::runBrew,
                )
            } catch (upgradeFailure: Exception) {
                throw GradleException("${failure.message} Skip CLI recovery failed: ${upgradeFailure.message}", failure)
            }
            logger.lifecycle("skipSpm: retrying export once with ${upgraded.absolutePath}.")
            // Exactly one export after upgrading: no additional fetch or stale-output retries.
            exportOnce(pkg, out, upgraded.absolutePath, upgraded.parent, retryTransient = false)
        }
    }

    private fun exportWithSelfHealing(pkg: File, out: File, executable: String, managedSkipBin: String?) {
        var selfCleaned = false
        var requiredVersion: SkipVersionRequirement? = null
        while (true) {
            try {
                exportOnce(pkg, out, executable, managedSkipBin)
                return
            } catch (failure: SkipExportFailure) {
                // A clean retry can fail before resolving again; retain the newer requirement seen earlier.
                val observed = failure.requiredVersion
                if (observed != null && (requiredVersion == null ||
                    compareDottedVersions(observed.version, requiredVersion.version) > 0)
                ) {
                    requiredVersion = observed
                }
                failure.requiredVersion = requiredVersion
                if (failure !is StaleTranspilerOutputsException) throw failure
                val stale = failure
                val transpilerOutputs = File(pkg, TRANSPILER_OUTPUTS_PATH)
                if (selfCleaned || !transpilerOutputs.isDirectory) {
                    throw SkipExportFailure(
                        "skip export failed on stale transpiler outputs and a clean re-export did not recover: " +
                            "${stale.message}. Run the `cleanSharedBuild` task (or `gradle clean`) to delete the " +
                            "package's entire .build/, then build again.",
                        stale,
                        requiredVersion,
                    )
                }
                selfCleaned = true
                logger.warn(
                    "skip export hit stale transpiler outputs (${stale.message}); " +
                        "deleting ${transpilerOutputs.absolutePath} and re-exporting from scratch. " +
                        "This happens when something re-resolved the package outside Gradle " +
                        "(e.g. `skip android test`, or a `git restore` of Package.resolved).",
                )
                // NOT deleteRecursively(): skipstone's outputs tree contains directory symlinks
                // back into the package's real Sources/ and .build/checkouts, and
                // File.deleteRecursively follows them — a self-heal would wipe the actual sources.
                deleteRecursivelyNoFollowLinks(transpilerOutputs)
            }
        }
    }

    /** One full export attempt: run skip, normalize the AAR namespaces, reject husks. */
    private fun exportOnce(
        pkg: File, out: File, executable: String, managedSkipBin: String?, retryTransient: Boolean = true,
    ) {
        out.mkdirs()
        // Drop stale AARs so a removed module's leftover can't linger and get consumed.
        out.listFiles { f -> f.extension == "aar" }?.forEach { it.delete() }

        val command = mutableListOf(
            executable, "export",
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
            runExportWithRetry(pkg, command, managedSkipBin, retryTransient)

            val prefix = namespacePrefix.get()
            val mode = buildMode.get()
            val aars = out.listFiles { f -> f.extension == "aar" }?.toList().orEmpty()
            aars.forEach { aar ->
                normalizeManifest(aar, manifestPackageFor(aar, mode, prefix))
            }

            // skip export can complete successfully while packaging "husk" AARs — a module whose
            // classes.jar is an empty zip. The breakage then surfaces far away (hundreds of unresolved
            // references when the consuming app compiles), so validate here. Metadata-only modules are
            // legitimate, though: e.g. SkipSwiftUI ships a classes.jar holding Kotlin metadata and zero
            // .class entries, so the check accepts Kotlin metadata as compiled output too.
            val husks = aars.filterNot(::aarHasCompiledOutput)
            if (husks.isNotEmpty()) {
                throw StaleTranspilerOutputsException(
                    "skip export produced husk AARs (no compiled classes): " + husks.joinToString { it.name },
                )
            }
        } catch (failure: SkipExportFailure) {
            // Android resolution may add/update Skip. Read its requirement before restoring the lockfile.
            failure.requiredVersion = expectedSkipVersion(
                File(pkg, "Package.swift").takeIf { it.isFile }?.readText(),
                resolvedLock.takeIf { it.isFile }?.readText(),
            )
            throw failure
        } finally {
            if (lockBackup != null && resolvedLock.isFile &&
                !resolvedLock.readBytes().contentEquals(lockBackup)
            ) {
                resolvedLock.writeBytes(lockBackup)
            }
        }
    }

    /** Select before the first export; failure recovery may upgrade the CLI once afterwards. */
    private fun selectSkipExecutable(expected: SkipVersionRequirement?, executable: String): String {
        val mode = skipVersionCheck.get()
        if (mode == "off" || expected == null) return executable
        // Missing or unparseable CLIs retain the previous export behavior.
        val current = readSkipVersion(executable) ?: return executable
        val mismatch = expected.mismatchWith(current)
        if (mode == "install" && mismatch != null) {
            val requirement = if (expected.exact) expected.version else ">= ${expected.version}"
            logger.lifecycle("skipSpm: selecting Skip CLI $requirement for this package (active: $current).")
            return SkipCliInstaller.selectOrInstall(
                expected, current, offline.get(), { readSkipVersion(it.absolutePath) }, ::runBrew,
            ).absolutePath
        }
        if (mismatch == null) return executable
        if (mode == "fail") {
            throw GradleException(
                "skipSpm: $mismatch (set skipSpm.skipVersionCheck = \"warn\" or \"off\" to demote this).",
            )
        }
        logger.warn("skipSpm: $mismatch")
        return executable
    }

    private fun runBrew(arguments: List<String>): String {
        val executable = System.getenv("SKIP_BREW_PATH")?.takeIf { it.isNotBlank() }
            ?: listOf("/opt/homebrew/bin/brew", "/usr/local/bin/brew", "/home/linuxbrew/.linuxbrew/bin/brew")
                .firstOrNull { File(it).canExecute() } ?: "brew"
        val output = ByteArrayOutputStream()
        val errors = ByteArrayOutputStream()
        val result = try {
            execOps.exec {
                commandLine(listOf(executable) + arguments)
                environment("HOMEBREW_NO_AUTO_UPDATE", "1")
                environment("HOMEBREW_NO_INSTALL_CLEANUP", "1")
                isIgnoreExitValue = true
                standardOutput = output
                errorOutput = errors
            }
        } catch (e: Exception) {
            throw GradleException("skipSpm: Homebrew is required to select an installed version or upgrade the Skip CLI.", e)
        }
        if (result.exitValue != 0) {
            throw GradleException("skipSpm: brew ${arguments.joinToString(" ")} failed: $output$errors")
        }
        return output.toString()
    }

    private fun readSkipVersion(executable: String): String? {
        val output = ByteArrayOutputStream()
        val result = runCatching {
            execOps.exec {
                commandLine(executable, "version")
                isIgnoreExitValue = true
                standardOutput = output
                errorOutput = output
            }
        }.getOrNull() ?: return null
        return if (result.exitValue == 0) parseSkipCliVersion(output.toString()) else null
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
     * build. Only network-shaped failures retry with the same CLI. If resolution reveals a newer Skip
     * requirement on failure, [export] may upgrade the CLI and run one final export.
     *
     * A failure whose output matches a stale-transpiler-outputs signature is thrown as
     * [StaleTranspilerOutputsException] instead (retrying it against the same `.build/` state can
     * never succeed); [export] self-heals it by cleaning the outputs and re-exporting.
     */
    private fun runExportWithRetry(
        pkg: File, command: List<String>, managedSkipBin: String?, retryTransient: Boolean,
    ) {
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
                // skip (resolved to an absolute path above) shells out to a bare `gradle` for its
                // nested per-module builds. Lead the PATH skip passes to its children with the
                // Gradle installation running THIS build ([gradleInstallBinDir]) so the nested
                // builds match the outer build's Gradle version; the Homebrew paths stay as
                // fallback for skip's other child tools, and because the daemon's own PATH is
                // reduced when Android Studio launches it via launchd.
                val inheritedPath = System.getenv("PATH").orEmpty()
                environment(
                    "PATH",
                    (listOfNotNull(gradleInstallBinDir.orNull, managedSkipBin) +
                        listOf("/opt/homebrew/bin", "/usr/local/bin", inheritedPath))
                        .filter { it.isNotEmpty() }
                        .joinToString(":"),
                )
                // The nested builds' expensive step (the Swift cross-compile) is an ad-hoc exec
                // Gradle can't cache, and on billed remote caches (e.g. Bitrise) every
                // cache-reading invocation costs money — so by default the children run with the
                // build cache off. The launcher absorbs `org.gradle.*` system properties from
                // GRADLE_OPTS and forwards them to its daemon as build options.
                if (!childGradleBuildCache.getOrElse(false)) {
                    val inheritedOpts = System.getenv("GRADLE_OPTS").orEmpty()
                    environment("GRADLE_OPTS", "$inheritedOpts -Dorg.gradle.caching=false".trim())
                }
                isIgnoreExitValue = true
                standardOutput = TeeOutputStream(System.out, outBuf)
                errorOutput = TeeOutputStream(System.err, errBuf)
            }
            if (result.exitValue == 0) return

            val combined = outBuf.toString() + errBuf.toString()
            if (looksLikeStaleTranspilerOutputs(combined)) {
                throw StaleTranspilerOutputsException(
                    "the transpiled module graph references files that no longer exist",
                )
            }
            val looksTransient = TRANSIENT_FETCH_HINTS.any { combined.contains(it, ignoreCase = true) }
            if (retryTransient && looksTransient && attempt < MAX_EXPORT_ATTEMPTS) {
                val backoffSeconds = attempt * RETRY_BACKOFF_SECONDS
                logger.warn(
                    "skip export failed on a likely-transient fetch error " +
                        "(attempt $attempt/$MAX_EXPORT_ATTEMPTS); retrying in ${backoffSeconds}s…",
                )
                Thread.sleep(backoffSeconds * 1000L)
                attempt++
                continue
            }
            throw SkipExportFailure(
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

/** Keeps the requirement observed during export after its lockfile has been restored. */
internal open class SkipExportFailure(
    message: String,
    cause: Throwable? = null,
    var requiredVersion: SkipVersionRequirement? = null,
) : GradleException(message, cause)

/** An export failure caused by stale incremental transpiler state, recoverable by a clean re-export. */
internal class StaleTranspilerOutputsException(message: String) : SkipExportFailure(message)

/** The skipstone transpiler's output tree, relative to the package dir. */
internal const val TRANSPILER_OUTPUTS_PATH = ".build/plugins/outputs"

/**
 * Classifies a failed `skip export`'s output as "the incremental transpiler outputs are stale".
 * Two known signatures:
 *  - SwiftPM can't load a vendored package manifest inside the skipstone outputs, e.g.
 *    `error: 'skip-keychain': the package manifest at '….build/plugins/outputs/…/skipstone/USLive/
 *    src/main/swift/Packages/skip-keychain/Package.swift' cannot be accessed (… doesn't exist)`.
 *    Requiring a transpiler-outputs path in the same output keeps a genuinely missing *user*
 *    manifest from triggering a pointless (and slow) clean re-export.
 *  - The generated Kotlin no longer resolves the skip-bridge *runtime* symbols (the bridge AARs
 *    fell out of the classpath), e.g. `Unresolved reference 'SwiftPeerBridged'`. Only bridge
 *    runtime symbols count — a plain unresolved reference is usually a real error in user code.
 */
internal fun looksLikeStaleTranspilerOutputs(output: String): Boolean {
    val touchesTranspilerOutputs =
        output.contains("/skipstone/") || output.contains(TRANSPILER_OUTPUTS_PATH)
    val missingVendoredManifest =
        output.contains("the package manifest at") && output.contains("cannot be accessed")
    if (touchesTranspilerOutputs && missingVendoredManifest) return true

    return STALE_BRIDGE_SYMBOLS.any { symbol ->
        // Kotlin 2.x quotes the symbol; 1.x uses a colon. Match both.
        output.contains("Unresolved reference '$symbol'") ||
            output.contains("Unresolved reference: $symbol")
    }
}

/** skip-bridge runtime symbols; unresolved in generated Kotlin ⇒ the bridge packages vanished mid-graph. */
private val STALE_BRIDGE_SYMBOLS = listOf(
    "SwiftPeerBridged",
    "SwiftProjecting",
    "SwiftObjectPointer",
    "SwiftPeer",
    "SwiftObjectNil",
    "SkipLogger",
    "sref",
)

/**
 * Deletes [root] recursively WITHOUT following directory symlinks: a symlink is deleted as a link,
 * never descended into. Kotlin's `File.deleteRecursively` (java.io semantics) follows them, which
 * is catastrophic here — skipstone's `.build/plugins/outputs` tree symlinks back into the package's
 * real `Sources/` and `.build/checkouts`. `Files.walkFileTree` does not follow symlinks unless
 * `FOLLOW_LINKS` is passed, and visits a symlink (even one pointing at a directory) via
 * `visitFile`, so deleting there removes just the link.
 */
internal fun deleteRecursivelyNoFollowLinks(root: File) {
    if (!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) return
    Files.walkFileTree(
        root.toPath(),
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                exc?.let { throw it }
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        },
    )
}

/**
 * True when the AAR has a classes.jar containing compiled output: `.class` files, or Kotlin
 * metadata for metadata-only modules (SkipSwiftUI packages a classes.jar with Kotlin metadata and
 * no `.class` entries — a valid export, not a husk). A true husk's classes.jar is an empty zip,
 * or holds nothing but plain resources.
 */
internal fun aarHasCompiledOutput(aar: File): Boolean {
    ZipFile(aar).use { zip ->
        val classesJar = zip.getEntry("classes.jar") ?: return false
        zip.getInputStream(classesJar).use { jar ->
            ZipInputStream(jar).use { entries ->
                return generateSequence { entries.nextEntry }.any { entry ->
                    COMPILED_OUTPUT_SUFFIXES.any { entry.name.endsWith(it) }
                }
            }
        }
    }
}

/** classes.jar entry suffixes that count as compiled output for husk detection. */
private val COMPILED_OUTPUT_SUFFIXES =
    listOf(".class", ".kotlin_module", ".kotlin_metadata", ".kotlin_builtins")

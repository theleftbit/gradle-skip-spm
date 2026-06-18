package dev.skip.spm

import org.gradle.api.DefaultTask
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
import java.io.File
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import javax.inject.Inject

/**
 * Runs `skip export` to build the SwiftPM package into Android AARs, then normalizes each AAR's
 * manifest namespace. Up-to-date when the Swift sources, the SPM manifest, and the ABIs are
 * unchanged — so it only re-runs when `polymarket-shared` actually changes.
 */
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
            "skip", "export",
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
        execOps.exec {
            workingDir(pkg.parentFile)
            commandLine(command)
        }

        val prefix = namespacePrefix.get()
        val mode = buildMode.get()
        out.listFiles { f -> f.extension == "aar" }?.forEach { aar ->
            normalizeManifest(aar, manifestPackageFor(aar, mode, prefix))
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
}

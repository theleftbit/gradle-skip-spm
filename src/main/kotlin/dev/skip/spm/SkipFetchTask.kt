package dev.skip.spm

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/**
 * Clones the SwiftPM package from [gitUrl] and checks out [ref] into [checkoutDir], so a remote
 * package can be exported without a local checkout. Up-to-date when [gitUrl]/[ref] are unchanged
 * and the checkout exists — so a pinned tag/commit is cloned once and never hits the network again.
 * (A moved branch ref won't auto-update; change the ref or rerun with `--rerun-tasks`.)
 */
abstract class SkipFetchTask : DefaultTask() {

    @get:Input
    abstract val gitUrl: Property<String>

    @get:Input
    abstract val ref: Property<String>

    /** Not an @OutputDirectory: skip export later writes `.build/` here, which would bust it. */
    @get:Internal
    abstract val checkoutDir: DirectoryProperty

    @get:Inject
    abstract val execOps: ExecOperations

    init {
        // No declared outputs; up-to-date when the inputs are unchanged and the checkout is present.
        outputs.upToDateWhen { File(checkoutDir.get().asFile, ".git").isDirectory }
    }

    @TaskAction
    fun fetch() {
        val dir = checkoutDir.get().asFile
        val url = gitUrl.get()
        val r = ref.get()
        if (!File(dir, ".git").isDirectory) {
            if (dir.exists()) dir.deleteRecursively()
            dir.parentFile?.mkdirs()
            execOps.exec { commandLine("git", "clone", url, dir.absolutePath) }
        } else {
            // Re-running because the ref changed: refresh refs so a newly-pushed tag/commit resolves.
            execOps.exec {
                workingDir(dir)
                commandLine("git", "fetch", "--tags", "--force", "origin")
            }
        }
        execOps.exec {
            workingDir(dir)
            commandLine("git", "checkout", "--force", r)
        }
    }
}

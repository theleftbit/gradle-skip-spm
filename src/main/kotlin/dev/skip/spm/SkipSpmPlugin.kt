package dev.skip.spm

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Builds a Skip (SwiftPM) package into Android AARs via `skip export` and wires them into the
 * consuming Android app build — AARs consumed from the output dir via a `fileTree` that is
 * `builtBy` the export task, and resolved in Android Studio on Gradle sync.
 *
 * Scaffold: this registers the `skipSpm` extension. The export tasks, the manifest-namespace
 * normalize, the `fileTree`/`builtBy` consumption wiring, and the gradle-idea-ext `afterSync`
 * trigger are layered in next (see TODOs).
 */
class SkipSpmPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.extensions.create("skipSpm", SkipSpmExtension::class.java)

        // TODO(export): register exportSharedAars{Debug,Release} as Exec tasks driving
        //   `skip export --module <module> --project <packageDir> --no-export-project
        //    -d <outputDir> --<mode> --arch <abi>...`, with inputs = the Swift sources and
        //   outputs = <outputDir>, so it only re-runs when the package changes.
        // TODO(normalize): rewrite each exported AAR's AndroidManifest package to
        //   `<namespacePrefix>.<module>` (Skip emits a shared namespace; this avoids collisions).
        // TODO(consume): add `fileTree(outputDir) { include("*.aar"); builtBy(exportTask) }` to
        //   the variant implementation configurations.
        // TODO(ide): register a gradle-idea-ext `afterSync` trigger so AS resolves the shared
        //   symbols on Gradle sync.
    }
}

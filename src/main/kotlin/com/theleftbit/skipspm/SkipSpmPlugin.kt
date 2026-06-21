package com.theleftbit.skipspm

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import org.gradle.plugins.ide.idea.IdeaPlugin
import org.gradle.plugins.ide.idea.model.IdeaModel
import org.jetbrains.gradle.ext.IdeaExtPlugin
import org.jetbrains.gradle.ext.ProjectSettings
import org.jetbrains.gradle.ext.TaskTriggersConfig

/**
 * Builds a Skip (SwiftPM) package into Android AARs via `skip export` and wires them into the
 * consuming Android app build:
 *  - the package comes from a local `packageDir` or is cloned from `packageGit`/`packageRef`,
 *  - `exportSharedAars{Debug,Release}` produce the AARs (incremental, keyed on the Swift sources),
 *  - each mapped variant's `api`/`implementation` config consumes them via `fileTree(...).builtBy(task)`,
 *  - a gradle-idea-ext `afterSync` trigger regenerates them on Gradle sync so AS resolves symbols.
 */
class SkipSpmPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val ext = project.extensions.create("skipSpm", SkipSpmExtension::class.java)
        ext.namespacePrefix.convention("shared")
        ext.outputDir.convention(project.layout.projectDirectory.dir("lib"))
        // Holds only the user's extra/override mappings; debug→debug and release→release are seeded
        // as a base when wiring consumption (below). A MapProperty `put` does NOT merge with a
        // convention, so seeding there rather than here is what keeps `put("internal","debug")` additive.
        ext.variantBuildMode.convention(emptyMap())
        ext.exposeAsApi.convention(false)

        // The package to export comes from either a local dir or a Git clone (validated below). For
        // the remote case the clone lives under <rootProject>/.skip-spm/<repo>; effectivePackageDir
        // resolves to the clone when packageGit is set, otherwise to the local packageDir.
        val cloneRoot = project.rootProject.layout.projectDirectory.dir(".skip-spm")
        val clonePath: Provider<Directory> = ext.packageGit.map { cloneRoot.dir(repoNameFromUrl(it)) }
        val effectivePackageDir: Provider<Directory> = clonePath.orElse(ext.packageDir)

        val fetchTask = project.tasks.register<SkipFetchTask>("fetchSharedPackage") {
            group = GROUP
            description = "Clone the Skip shared package from Git and check out the configured ref."
            gitUrl.set(ext.packageGit)
            ref.set(ext.packageRef)
            checkoutDir.set(clonePath)
        }

        val exportTasks: Map<String, TaskProvider<SkipExportTask>> = MODES.associateWith { mode ->
            project.tasks.register<SkipExportTask>(
                "exportSharedAars" + mode.replaceFirstChar { it.uppercase() },
            ) {
                group = GROUP
                description = "Export the Skip shared package into $mode Android AARs."
                packageDir.set(effectivePackageDir)
                sources.set(effectivePackageDir.map { it.dir("Sources") })
                manifests.from(
                    effectivePackageDir.map { it.file("Package.swift") },
                    effectivePackageDir.map { it.file("Package.resolved") },
                )
                module.set(ext.module)
                buildMode.set(mode)
                // Release can ship a wider ABI set than debug (releaseAbis); falls back to abis.
                abis.set(if (mode == "release") ext.releaseAbis.orElse(ext.abis) else ext.abis)
                namespacePrefix.set(ext.namespacePrefix)
                outputDir.set(ext.outputDir.dir(mode))
            }
        }

        // `skip export` builds into a SwiftPM `.build/` inside the package dir and writes the AARs to
        // `outputDir` — both live outside Gradle's buildDir, so neither `gradle clean` nor Android
        // Studio "Clean Project" would touch them. Wire both into `clean` so a clean is a real clean:
        // the AARs go (forcing the next build to re-export the shared package from scratch) and the
        // heavy `.build/` scratch (often ~1GB) is reclaimed. The incremental export still skips on
        // normal builds when the Swift sources are unchanged — only an explicit clean re-exports.
        val cleanSharedBuild = project.tasks.register<Delete>("cleanSharedBuild") {
            group = GROUP
            description = "Delete the shared package's SwiftPM .build/ and the exported AARs so a clean forces a full re-export."
            delete(effectivePackageDir.map { it.dir(".build").asFile })
            delete(ext.outputDir.map { it.asFile })
        }
        project.tasks.matching { it.name == "clean" }.configureEach {
            dependsOn(cleanSharedBuild)
        }

        fun aarsFor(mode: String): FileCollection {
            val dir = ext.outputDir.dir(mode).get().asFile
            return project.fileTree(dir).apply {
                include("*.aar")
                builtBy(exportTasks.getValue(mode))
            }
        }

        project.afterEvaluate {
            // Source: exactly one of packageDir (local) or packageGit (remote).
            val remote = ext.packageGit.isPresent
            require(remote != ext.packageDir.isPresent) {
                "skipSpm: set exactly one of packageDir (local) or packageGit (remote)."
            }
            if (remote) {
                require(ext.packageRef.isPresent) {
                    "skipSpm: packageRef (tag/branch/commit) is required when packageGit is set."
                }
                exportTasks.values.forEach { task -> task.configure { dependsOn(fetchTask) } }
            }

            // Consume the AARs: add the per-mode fileTree to each mapped <variant>Implementation config.
            // debug→debug and release→release are always mapped; variantBuildMode adds or overrides
            // extra variants (e.g. internal→debug). Seed the base map here so additive `put(...)` works.
            val mapping = linkedMapOf("debug" to "debug", "release" to "release")
            mapping.putAll(ext.variantBuildMode.get())
            // `api` exposes the shared types transitively (a library that re-exports them);
            // `implementation` keeps them internal (an app, or a module that wraps them).
            val configKind = if (ext.exposeAsApi.get()) "Api" else "Implementation"
            mapping.forEach { (variant, mode) ->
                require(mode in MODES) {
                    "skipSpm: variantBuildMode['$variant'] must be one of $MODES, was '$mode'"
                }
                val configuration = variant + configKind
                if (project.configurations.findByName(configuration) != null) {
                    project.dependencies.add(configuration, aarsFor(mode))
                }
            }
        }

        // IDE: regenerate the (debug) AARs on Gradle sync so Android Studio resolves shared symbols.
        val ideBootstrap = project.tasks.register("bootstrapSharedForIde") {
            group = GROUP
            description = "Generate shared AARs so Android Studio resolves shared symbols on sync."
            dependsOn(exportTasks.getValue("debug"))
        }
        registerIdeaSyncTrigger(project, ideBootstrap)
    }

    /** Apply gradle-idea-ext on the root project and run [task] after each Gradle sync. */
    private fun registerIdeaSyncTrigger(project: Project, task: TaskProvider<*>) {
        val root = project.rootProject
        // Apply both by class, not by string id: the plugin is on this plugin's classloader (a
        // transitive `implementation` dep), but we apply it to the *root* project, whose own plugin
        // registry doesn't know the `org.jetbrains.gradle.plugin.idea-ext` id. Class-based apply
        // resolves against the class we already hold, so it works regardless of the target's classpath.
        root.pluginManager.apply(IdeaPlugin::class.java)
        root.pluginManager.apply(IdeaExtPlugin::class.java)
        val ideaProject = root.extensions.getByType(IdeaModel::class.java).project
        val settings = (ideaProject as ExtensionAware).extensions.getByType(ProjectSettings::class.java)
        val triggers = (settings as ExtensionAware).extensions.getByType(TaskTriggersConfig::class.java)
        triggers.afterSync(task)
    }

    private companion object {
        const val GROUP = "skip"
        val MODES = listOf("debug", "release")

        /** `https://github.com/org/shared.git` → `shared`. */
        fun repoNameFromUrl(url: String): String =
            url.trimEnd('/').substringAfterLast('/').removeSuffix(".git").ifEmpty { "package" }
    }
}

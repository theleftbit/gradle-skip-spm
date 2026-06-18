package dev.skip.spm

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import org.gradle.plugins.ide.idea.IdeaPlugin
import org.gradle.plugins.ide.idea.model.IdeaModel
import org.jetbrains.gradle.ext.ProjectSettings
import org.jetbrains.gradle.ext.TaskTriggersConfig

/**
 * Builds a Skip (SwiftPM) package into Android AARs via `skip export` and wires them into the
 * consuming Android app build:
 *  - `exportSharedAars{Debug,Release}` produce the AARs (incremental, keyed on the Swift sources),
 *  - each mapped `<variant>Implementation` config consumes them via `fileTree(...).builtBy(task)`,
 *  - a gradle-idea-ext `afterSync` trigger regenerates them on Gradle sync so AS resolves symbols.
 */
class SkipSpmPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val ext = project.extensions.create("skipSpm", SkipSpmExtension::class.java)
        ext.namespacePrefix.convention("shared")
        ext.outputDir.convention(project.layout.projectDirectory.dir("lib"))
        ext.variantBuildMode.convention(mapOf("debug" to "debug", "release" to "release"))

        val exportTasks: Map<String, TaskProvider<SkipExportTask>> = MODES.associateWith { mode ->
            project.tasks.register<SkipExportTask>(
                "exportSharedAars" + mode.replaceFirstChar { it.uppercase() },
            ) {
                group = GROUP
                description = "Export the Skip shared package into $mode Android AARs."
                packageDir.set(ext.packageDir)
                sources.set(ext.packageDir.dir("Sources"))
                manifests.from(
                    ext.packageDir.file("Package.swift"),
                    ext.packageDir.file("Package.resolved"),
                )
                module.set(ext.module)
                buildMode.set(mode)
                abis.set(ext.abis)
                namespacePrefix.set(ext.namespacePrefix)
                outputDir.set(ext.outputDir.dir(mode))
            }
        }

        fun aarsFor(mode: String): FileCollection {
            val dir = ext.outputDir.dir(mode).get().asFile
            return project.fileTree(dir).apply {
                include("*.aar")
                builtBy(exportTasks.getValue(mode))
            }
        }

        // Consume the AARs: add the per-mode fileTree to each mapped <variant>Implementation config.
        project.afterEvaluate {
            ext.variantBuildMode.get().forEach { (variant, mode) ->
                require(mode in MODES) {
                    "skipSpm: variantBuildMode['$variant'] must be one of $MODES, was '$mode'"
                }
                val configuration = variant + "Implementation"
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
        root.pluginManager.apply(IdeaPlugin::class.java)
        root.pluginManager.apply("org.jetbrains.gradle.plugin.idea-ext")
        val ideaProject = root.extensions.getByType(IdeaModel::class.java).project
        val settings = (ideaProject as ExtensionAware).extensions.getByType(ProjectSettings::class.java)
        val triggers = (settings as ExtensionAware).extensions.getByType(TaskTriggersConfig::class.java)
        triggers.afterSync(task)
    }

    private companion object {
        const val GROUP = "skip"
        val MODES = listOf("debug", "release")
    }
}

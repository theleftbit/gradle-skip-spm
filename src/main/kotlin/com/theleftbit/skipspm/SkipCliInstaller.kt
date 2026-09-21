package com.theleftbit.skipspm

import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import java.io.File

/** Reuses installed Homebrew CLIs; new installations may only move forwards. */
internal object SkipCliInstaller {
    /** Called only when the active CLI does not meet [expected]. */
    fun selectOrInstall(
        expected: SkipVersionRequirement,
        currentVersion: String,
        offline: Boolean,
        readVersion: (File) -> String?,
        brew: (List<String>) -> String,
    ): File {
        val cellar = File(brew(listOf("--cellar")).trim(), "skip")
        val caskroom = File(brew(listOf("--prefix")).trim(), "Caskroom/skip")
        fun installed(): List<Pair<File, String>> =
            (cellar.listFiles().orEmpty().map { File(it, "bin/skip") } +
                caskroom.listFiles().orEmpty().map { File(it, "skip.artifactbundle/bin/skip") })
                .filter { it.canExecute() }
                .mapNotNull { file -> readVersion(file)?.let { file to it } }

        fun compatible(candidates: List<Pair<File, String>>): File? = candidates
            .filter { expected.mismatchWith(it.second) == null }
            .maxWithOrNull { a, b -> compareDottedVersions(a.second, b.second) }?.first

        val candidates = installed()
        compatible(candidates)?.let { return it }
        val newestInstalled = (candidates.map { it.second } + currentVersion)
            .maxWith(::compareDottedVersions)
        if (expected.exact && compareDottedVersions(expected.version, newestInstalled) <= 0) {
            throw GradleException(
                "skipSpm: Skip CLI ${expected.version} is not installed. Older versions may only be reused " +
                    "when already installed; automatic installation never downgrades from $newestInstalled.",
            )
        }
        if (offline) {
            throw GradleException("skipSpm: no compatible Skip CLI is installed. Run once without --offline to check for a newer Homebrew release.")
        }

        // Refresh first, then disable implicit updates in the command runner so the version checked
        // below is the one brew install/upgrade uses. Never request a historical formula or release.
        brew(listOf("update"))
        val info = JsonSlurper().parseText(brew(listOf("info", "--json=v2", "--formula", "skip"))) as? Map<*, *>
        val formula = (info?.get("formulae") as? List<*>)?.singleOrNull() as? Map<*, *>
        val latest = (formula?.get("versions") as? Map<*, *>)?.get("stable") as? String
        if (latest == null || !Regex("""\d+\.\d+\.\d+""").matches(latest)) {
            throw GradleException("skipSpm: Homebrew did not report a stable Skip CLI release.")
        }
        if (compareDottedVersions(latest, newestInstalled) <= 0 || expected.mismatchWith(latest) != null) {
            throw GradleException(
                "skipSpm: Homebrew's latest Skip CLI $latest cannot satisfy ${expected.version} " +
                    "with a forward-only installation (newest installed: $newestInstalled). " +
                    "Use an already installed compatible version or update the project's requirement.",
            )
        }
        val action = if (cellar.listFiles().orEmpty().any { it.isDirectory }) "upgrade" else "install"
        brew(listOf(action, "--formula", "skip"))
        return compatible(installed())
            ?: throw GradleException("skipSpm: Homebrew completed but no installed Skip CLI satisfies ${expected.version}.")
    }
}

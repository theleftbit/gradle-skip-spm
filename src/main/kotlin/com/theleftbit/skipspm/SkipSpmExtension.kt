package com.theleftbit.skipspm

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property

/**
 * Configuration for the `skipSpm` plugin.
 *
 * Point it at the SwiftPM package to export, either locally or from a Git repo:
 *
 * ```kotlin
 * skipSpm {
 *     // Local package (co-developed in the same repo):
 *     packageDir = file("../polymarket-shared")
 *     // …or a remote one (the plugin clones + pins it):
 *     // packageGit = "https://github.com/org/shared.git"
 *     // packageRef = "v1.2.3"
 *
 *     module          = "USLive"
 *     abis            = listOf("aarch64", "armv7")
 *     namespacePrefix = "com.polymarket.shared"
 *     // variantBuildMode.put("internal", "debug")   // custom build types
 * }
 * ```
 */
abstract class SkipSpmExtension {
    /**
     * Local SwiftPM package directory to export (e.g. `file("../polymarket-shared")`).
     * Set exactly one of [packageDir] or [packageGit].
     */
    abstract val packageDir: DirectoryProperty

    /**
     * Git URL of the SwiftPM package to export, instead of a local [packageDir]. The plugin clones
     * it under `<rootProject>/.skip-spm/<repo>` and checks out [packageRef]. Pin [packageRef] to a
     * tag or commit for reproducible, network-free incremental builds. Add `.skip-spm/` to
     * `.gitignore`. Assumes the package's `Package.swift` is at the repo root.
     */
    abstract val packageGit: Property<String>

    /** Git ref (tag, branch, or commit) to check out. Required when [packageGit] is set. */
    abstract val packageRef: Property<String>

    /** The umbrella Skip module to export (e.g. `USLive`). */
    abstract val module: Property<String>

    /** Android ABIs to compile, in skip's naming (e.g. `aarch64`, `armv7`). */
    abstract val abis: ListProperty<String>

    /**
     * Namespace prefix applied to each exported AAR's manifest as `<namespacePrefix>.<module>`,
     * so the modules don't collide. Needed because Skip currently emits a shared namespace across
     * all exported modules (tracked upstream); remove once Skip emits unique per-module namespaces.
     */
    abstract val namespacePrefix: Property<String>

    /** Root output dir; per-mode AARs land in `<outputDir>/<mode>`. Defaults to `<project>/lib`. */
    abstract val outputDir: DirectoryProperty

    /**
     * Extra or overriding variant→mode mappings. `debug→debug` and `release→release` are always
     * mapped; use this to map custom build types (e.g. `put("internal", "debug")`) or to override a
     * default. Each mode must be `debug` or `release`.
     */
    abstract val variantBuildMode: MapProperty<String, String>
}

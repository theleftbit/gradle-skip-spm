package dev.skip.spm

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property

/**
 * Configuration for the `skipSpm` plugin.
 *
 * ```kotlin
 * skipSpm {
 *     packageDir      = file("../polymarket-shared")
 *     module          = "USLive"
 *     abis            = listOf("aarch64", "armv7")
 *     namespacePrefix = "com.polymarket.shared"
 *     // variantBuildMode.put("internal", "debug")   // custom build types
 * }
 * ```
 */
abstract class SkipSpmExtension {
    /** The SwiftPM package directory to export (e.g. `file("../polymarket-shared")`). */
    abstract val packageDir: DirectoryProperty

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
     * Maps an Android build variant (by name) to a shared build mode (`debug` or `release`).
     * Defaults to `debug→debug`, `release→release`; add custom build types (e.g. `internal→debug`).
     */
    abstract val variantBuildMode: MapProperty<String, String>
}

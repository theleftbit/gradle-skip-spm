package dev.skip.spm

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
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
 * }
 * ```
 */
abstract class SkipSpmExtension {
    /** The SwiftPM package directory to export (e.g. `../polymarket-shared`). */
    abstract val packageDir: DirectoryProperty

    /** The umbrella Skip module to export (e.g. `USLive`). */
    abstract val module: Property<String>

    /** Android ABIs to compile, in skip's naming (e.g. `aarch64`, `armv7`). */
    abstract val abis: ListProperty<String>

    /**
     * Namespace prefix applied to each exported AAR's `AndroidManifest.xml`, so the modules
     * don't collide. Needed because Skip currently emits a shared namespace across all exported
     * modules (tracked upstream); each AAR is rewritten to `<namespacePrefix>.<module>`.
     */
    abstract val namespacePrefix: Property<String>
}

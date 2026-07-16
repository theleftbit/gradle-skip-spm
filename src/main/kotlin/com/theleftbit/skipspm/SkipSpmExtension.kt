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

    /** Android ABIs to compile, in skip's naming (e.g. `aarch64`, `armv7`). Used for every mode unless [releaseAbis] overrides release. */
    abstract val abis: ListProperty<String>

    /**
     * ABIs for the release-mode export only; falls back to [abis] when unset. Lets debug stay
     * arm64-only (fast local/CI builds) while release ships the full set (e.g. aarch64+armv7+x86_64).
     */
    abstract val releaseAbis: ListProperty<String>

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

    /**
     * When true, the AARs are added to each variant's `api` configuration instead of
     * `implementation`, so a library module that re-exports the shared types exposes them
     * transitively to its own consumers. Defaults to false — right for an application module, or one
     * that fully wraps the shared types behind its own API.
     */
    abstract val exposeAsApi: Property<Boolean>

    /**
     * Gradle paths of the projects that consume the AARs (e.g. `listOf(":core", ":app")`).
     * Defaults to just the project the plugin is applied to.
     *
     * Lets a multi-module app apply the plugin **once** — typically on the root project, keeping a
     * single shared export — and wire the AARs into every module that needs the shared types,
     * instead of each module hand-rolling `fileTree(...).builtBy(exportTask)`. Each listed project
     * gets the AARs added to every mapped variant's `api`/`implementation` config that exists there
     * (per [variantBuildMode] and [exposeAsApi]); projects without a mapped config (non-Android, or
     * missing that variant) are skipped silently.
     */
    abstract val consumers: ListProperty<String>

    /**
     * Prune Gradle's artifact-transform cache after each export. Defaults to true.
     *
     * AGP consumes an AAR by *exploding* it (classes.jar plus all the native `.so`) into a
     * content-addressed entry under `~/.gradle/caches/<version>/transforms/`. Each entry is as
     * large as the unpacked AARs (hundreds of MB), every content change strands the previous
     * entries as garbage, and Gradle's own cleanup only reaps entries unused for ~7 days — so
     * active shared-package development leaks gigabytes per day. When enabled, each export deletes
     * the transform entries of the AARs whose bytes provably *changed* in that export (a previous
     * AAR existed and hashed differently), and only entries older than ~24h — a live daemon caches
     * transform locations in memory for its whole lifetime without re-checking existence, so
     * anything younger (or anything that might match regenerated content: byte-identical
     * re-exports, post-clean exports with no baseline) is left alone. Old churn is what actually
     * bloats the cache, and it self-prunes on later exports once aged. Disable only if concurrent
     * builds of *another* checkout may be consuming the same AAR names from the same Gradle user
     * home mid-build.
     */
    abstract val pruneStaleTransforms: Property<Boolean>
}

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
     * CLI version policy: `"install"` (default) reuses installed CLIs or installs a newer stable
     * Homebrew release. `Package.resolved` takes priority over the manifest fallback.
     * A compatible active CLI is kept; an export revealing a newer requirement can retry once.
     * `"warn"` logs drift, `"fail"` rejects it, and `"off"` disables the check. Offline builds
     * never install or retry with another CLI after failure. See README for selection details.
     */
    abstract val skipVersionCheck: Property<String>

    /**
     * Whether the nested `gradle` builds `skip export` spawns may use the Gradle build cache.
     * Defaults to false: their expensive step (the Swift cross-compile) is an ad-hoc exec Gradle
     * can't cache anyway — only the bridge-Kotlin compile / packaging steps would hit — and on
     * billed remote caches (e.g. Bitrise) every cache-reading Gradle invocation costs money. The
     * outer build's own cache usage is unaffected. Set true to let the nested builds cache.
     */
    abstract val childGradleBuildCache: Property<Boolean>

    /**
     * Deprecated no-op. The plugin no longer prunes Gradle's artifact-transform cache: entries are
     * only distinguishable by AAR *name*, and every checkout/worktree of the same app produces the
     * same names — so a prune from one checkout could delete entries another checkout's live daemon
     * still referenced (dangling classpath, unresolved shared classes, no local cause). Only Gradle
     * itself knows which entries recent builds used; bound the cache with Gradle's own cleanup
     * instead — see the README's "Disk usage" section for the init-script recipe.
     */
    @Deprecated("No-op since 0.3.0; configure Gradle's own cache retention instead (see README).")
    abstract val pruneStaleTransforms: Property<Boolean>
}

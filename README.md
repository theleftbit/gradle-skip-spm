# gradle-skip-spm

[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/com.theleftbit.skipspm?logo=gradle)](https://plugins.gradle.org/plugin/com.theleftbit.skipspm)

A Gradle plugin that builds a [Skip](https://skip.dev) (SwiftPM) package into Android AARs via
`skip export` and consumes them in your Android app's Gradle build — so the shared Swift layer is
available to the app **and** resolves in Android Studio on a Gradle sync, with no manual export step.

## Usage

The plugin is published on the [Gradle Plugin Portal](https://plugins.gradle.org/plugin/com.theleftbit.skipspm),
so the default `gradlePluginPortal()` in your `settings.gradle.kts` resolves it — no extra repository needed:

```kotlin
// app/build.gradle.kts
plugins {
    id("com.android.application")
    id("com.theleftbit.skipspm") version "0.2.2"
}

skipSpm {
    // The SwiftPM package — either a local dir…
    packageDir      = file("../foo-shared")
    // …or a remote repo the plugin clones + pins (set one or the other, not both):
    // packageGit   = "https://github.com/org/shared.git"
    // packageRef   = "v1.2.3"                        // tag, branch, or commit

    module          = "Foo"                           // umbrella module to export
    abis            = listOf("aarch64", "armv7")
    namespacePrefix = "com.foo.bar"                   // see "Namespace" below
}
```

The plugin:
- registers `exportSharedAars{Debug,Release}` — incremental Exec tasks keyed on the Swift sources,
- consumes the resulting AARs via `fileTree(outputDir).builtBy(exportTask)` — in the applied
  project and in every project listed in `consumers` (see
  ["Multi-module apps"](#multi-module-apps) below),
- registers a `gradle-idea-ext` `afterSync` trigger so the IDE resolves the shared symbols on sync,
- prunes the previous AARs' stale transform-cache entries after each export (see
  ["Disk usage"](#disk-usage-gradles-transforms-cache) below).

## Multi-module apps

Applied to a single module, the plugin wires the AARs into that module's variants automatically.
In a multi-module app, don't apply the plugin per module (that would register duplicate export
tasks) and don't hand-roll `fileTree(...).builtBy(...)` in each consuming module — apply the plugin
**once**, e.g. on the root project, and list the consumers:

```kotlin
// root build.gradle.kts
plugins { id("com.theleftbit.skipspm") version "…" }

skipSpm {
    packageDir      = file("../foo-shared")
    module          = "Foo"
    abis            = listOf("aarch64")
    namespacePrefix = "com.foo.bar"

    exposeAsApi     = true    // libraries re-export the shared types to their own consumers
    consumers       = listOf(":core", ":featureA", ":app")
}
```

There is still a **single export per mode**; each listed project's mapped variant configs
(`debugApi`, `releaseImplementation`, …, per `variantBuildMode` + `exposeAsApi`) consume the same
`fileTree(outputDir/<mode>).builtBy(export)`, so even a cold single-module build
(`:featureA:assembleDebug`) triggers the export first. Projects without a mapped config (non-Android,
or missing that variant) are skipped silently; an unknown path fails the build. `consumers` defaults
to just the project the plugin is applied to, so single-module setups need no change.

## Package source

Point the plugin at the SwiftPM package either locally (`packageDir`, for a package co-developed in
the same repo) or remotely (`packageGit` + `packageRef`). For the remote case the plugin does a full
clone under `<rootProject>/.skip-spm/<repo>` and checks out the ref — so:

- add **`.skip-spm/`** to your `.gitignore`;
- **pin `packageRef` to a tag or commit**: the clone happens once, and the export then stays
  incremental and network-free. A moved *branch* ref won't auto-update (rerun with `--rerun-tasks`
  or bump the ref);
- the package's `Package.swift` is assumed to be at the repo root.

## Namespace

The `namespacePrefix` exists only because Skip currently emits a **shared** `AndroidManifest`
namespace across all exported modules, which collide when consumed together. The plugin rewrites
each AAR's manifest to `<namespacePrefix>.<module>`. If Skip emits unique per-module namespaces
upstream, this step (and the option) can be removed — tracked separately.

## Native libraries & app size (stripping)

`skip export` compiles the shared Swift to **native `.so`** — the umbrella module plus the Swift
runtime and Skip bridges (`libswiftCore.so`, `lib_FoundationICU.so`, `libSkipFuseUI.so`, …). These
are large, and Android ships them **unstripped by default**, which can add hundreds of MB. Stripping
is configured on the module that assembles the APK/AAB — your **application** module — even when the
plugin itself is applied to a `com.android.library` module (e.g. `:shared`): AGP strips when it
packages the final artifact, not in the library.

**To strip, two things must be true** in the application module: an NDK must be **findable**, and
`keepDebugSymbols` must be **absent** (it suppresses stripping). AGP's strip step shells out to the
NDK's `llvm-strip` — any NDK works.

Rather than pinning a specific `ndkVersion` (which drifts and may not be installed on a given
machine), **detect whatever NDK is installed and use it**, and **guard the release** so a *missing*
NDK fails loudly instead of silently shipping unstripped libs:

```kotlin
// app/build.gradle.kts  (the application module)
import java.io.File
import java.util.Properties

// Installed NDKs, from the SDK dir (local.properties `sdk.dir`, else ANDROID_HOME / ANDROID_SDK_ROOT).
val ndkRoot: File? = run {
    val props = Properties()
    val lp = rootProject.file("local.properties")
    if (lp.exists()) lp.inputStream().use { props.load(it) }
    val sdk = props.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    sdk?.let { File(it, "ndk") }
}
val installedNdks = ndkRoot?.listFiles().orEmpty().filter { it.isDirectory }.map { it.name }.sorted()

android {
    installedNdks.lastOrNull()?.let { ndkVersion = it } // highest installed; no hardcoded version
    // Do NOT set packaging.jniLibs.keepDebugSymbols.add("**/*.so") — its absence is what lets the strip run.
}

// Fail loudly when a release/internal build is requested but no NDK is installed. This is a
// CONFIGURATION-TIME check, not a task: a task action capturing `installedNdks`/`ndkRoot` can't be
// serialized by Gradle's configuration cache ("cannot serialize Gradle script object references").
// The installedNdks file read is itself a config-cache input, so adding/removing an NDK re-evaluates it.
val buildingStrippedVariant = gradle.startParameter.taskNames.any {
    val n = it.substringAfterLast(":").lowercase()
    // Aggregate lifecycle tasks (`assemble`, `bundle`, `build`) build ALL variants — incl.
    // release/internal — but their names don't contain the variant; the explicit variant tasks do.
    // Catch both, or `./gradlew assemble`/`build` would skip the guard and ship unstripped libs.
    n == "assemble" || n == "bundle" || n == "build" ||
        ((n.startsWith("bundle") || n.startsWith("assemble")) && ("release" in n || "internal" in n))
}
if (buildingStrippedVariant && installedNdks.isEmpty()) {
    error(
        "No Android NDK found (looked under ${ndkRoot ?: "an unset Android SDK dir"}) — install one via " +
            "Android Studio → SDK Manager → SDK Tools → NDK (any version) so release native libs are " +
            "stripped instead of silently shipping unstripped.",
    )
}
```

> ⚠️ **Why the guard:** with no findable NDK, AGP only logs `Unable to strip … packaging them as
> they are` and **ships the libs unstripped while the build stays GREEN**. Verify on the artifact:
> `unzip -l app-release.aab` → each `base/lib/<abi>/*.so` should be a few MB (stripped), not tens of MB.

Optionally, keep a symbol table for crash symbolication — this is **separate from stripping** (it
controls the `*.so.sym` files kept in the AAB's `BUNDLE-METADATA` for Play Console, never delivered
to users):

```kotlin
android {
    buildTypes {
        release {
            // "symbol_table" = function-name symbolication (recommended for Swift; FULL DWARF is
            // huge). "none" = smallest upload, no Play-side native symbolication. "full" = everything.
            ndk { debugSymbolLevel = "symbol_table" }
        }
    }
}
```

## Disk usage: Gradle's transforms cache

AGP consumes an AAR by **exploding** it — classes.jar plus every native `.so` — into a
content-addressed entry under `~/.gradle/caches/<gradle-version>/transforms/`. For a Skip export
one exploded umbrella AAR can be ~0.5 GB, and **every shared-package change creates brand-new
entries** while the previous ones sit until Gradle's own cleanup reaps entries unused for ~7 days.
Under active development that leaks gigabytes per day.

The plugin therefore prunes automatically (**`pruneStaleTransforms`, default `true`**): after each
export it compares every AAR's bytes against the previous export, and deletes the transform entries
of the AARs that provably **changed**, logging what it freed:

```
skipSpm: pruned 24 stale transform-cache entries (5842 MB) for changed AARs: USLive-release.aar, …
```

Two safety rules keep this from ever yanking an entry a build still needs — a live Gradle daemon
caches transform locations in memory for its whole lifetime *without re-checking they exist*, so a
wrongly deleted entry resurfaces as unresolved shared classes or a missing-file failure:

- **Only provably-stale content**: byte-identical re-exports (common — skip's underlying Android
  build is reproducible) and exports with no previous AARs to compare against (right after a clean,
  or a first build on a machine with a populated cache) never prune.
- **Only entries older than ~24 h**: young entries may still be referenced by a live daemon (or
  match content a branch switch flips back to). Old churn is what actually bloats the cache, and
  fresh churn self-prunes on a later export once it ages past the window.

For the same reason, never `rm -rf` the transforms cache while builds/IDE syncs are running — stop
the daemons first (`./gradlew --stop`).

Only entries whose transform *output name* is attributable to this package's AARs (e.g.
`USLive-release/`, `USLive-release-runtime.jar`, `com.foo.shared.uslive-r.txt`) are touched;
everything else in the cache is left alone, and a prune failure never fails the build. Set
`pruneStaleTransforms = false` only if concurrent builds of **another checkout** share the same
Gradle user home and might be reading same-named entries mid-build.

To also cap the rest of the cache, shorten Gradle's retention — this is Gradle-user-home-wide
configuration, so an init script is the only supported place (a project plugin can't set it):

```kotlin
// ~/.gradle/init.d/cache-retention.init.gradle.kts
beforeSettings {
    caches {
        // Transforms fall under "created resources"; the default is 7 days.
        createdResources.setRemoveUnusedEntriesAfterDays(2)
    }
}
```

## Developing against a real app

Use a composite build instead of publishing while iterating:

```kotlin
// the consuming repo's settings.gradle.kts
includeBuild("../gradle-skip-spm")
```

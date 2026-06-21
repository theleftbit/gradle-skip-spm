# gradle-skip-spm

A Gradle plugin that builds a [Skip](https://skip.dev) (SwiftPM) package into Android AARs via
`skip export` and consumes them in your Android app's Gradle build — so the shared Swift layer is
available to the app **and** resolves in Android Studio on a Gradle sync, with no manual export step.

> **Status:** early scaffold. Extracted from the Polymarket `mobile-app` "Gradle drives `skip export`"
> setup and being generalized for reuse — and a possible upstream contribution to Skip.

## Usage (target)

```kotlin
// app/build.gradle.kts
plugins {
    id("com.android.application")
    id("com.theleftbit.skipspm")
}

skipSpm {
    // The SwiftPM package — either a local dir…
    packageDir      = file("../polymarket-shared")
    // …or a remote repo the plugin clones + pins (set one or the other, not both):
    // packageGit   = "https://github.com/org/shared.git"
    // packageRef   = "v1.2.3"                        // tag, branch, or commit

    module          = "USLive"                       // umbrella module to export
    abis            = listOf("aarch64", "armv7")
    namespacePrefix = "com.polymarket.shared"        // see "Namespace" below
}
```

The plugin:
- registers `exportSharedAars{Debug,Release}` — incremental Exec tasks keyed on the Swift sources,
- consumes the resulting AARs via `fileTree(outputDir).builtBy(exportTask)`,
- registers a `gradle-idea-ext` `afterSync` trigger so the IDE resolves the shared symbols on sync.

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

Two things are required to actually strip:

```kotlin
// app/build.gradle.kts  (the application module)
android {
    // (1) A findable NDK. Install it (Android Studio → SDK Manager → SDK Tools → NDK) and pin the
    //     version. AGP's strip step uses the NDK's llvm-strip; the version string just points to it.
    ndkVersion = "28.2.13676358"

    packaging {
        // (2) Do NOT set jniLibs.keepDebugSymbols.add("**/*.so") — it suppresses stripping and ships
        //     the in-binary debug info to every user. (Its absence is what lets the strip run.)
    }
}
```

> ⚠️ **Silent failure:** if no NDK is found, AGP logs `Unable to strip … packaging them as they are`
> and **ships the libs unstripped while the build stays GREEN**. So make sure the pinned NDK is
> installed on **every** build machine — your laptop *and* your CI/release runners (or AGP will fetch
> it). Verify on the artifact: `unzip -l app-release.aab` → each `base/lib/<abi>/*.so` should be a few
> MB (stripped), not tens of MB.

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

## Developing against a real app

Use a composite build instead of publishing while iterating:

```kotlin
// the consuming repo's settings.gradle.kts
includeBuild("../gradle-skip-spm")
```

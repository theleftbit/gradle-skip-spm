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

**To strip, two things must be true** in the application module: an NDK must be **findable**, and
`keepDebugSymbols` must be **absent** (it suppresses stripping). AGP's strip step shells out to the
NDK's `llvm-strip` — any NDK works.

Rather than pinning a specific `ndkVersion` (which drifts and may not be installed on a given
machine), **detect whatever NDK is installed and use it**, and **guard the release** so a *missing*
NDK fails loudly instead of silently shipping unstripped libs:

```kotlin
// app/build.gradle.kts  (the application module)
import java.util.Properties

// Installed NDKs, from the SDK dir (local.properties `sdk.dir`, else ANDROID_HOME / ANDROID_SDK_ROOT).
val ndkRoot: File? = run {
    val props = Properties()
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { props.load(it) }
    (props.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT"))
        ?.let { File(it, "ndk") }
}
val installedNdks = ndkRoot?.listFiles().orEmpty().filter { it.isDirectory }.map { it.name }.sorted()

android {
    installedNdks.lastOrNull()?.let { ndkVersion = it } // highest installed; no hardcoded version
    // Do NOT set packaging.jniLibs.keepDebugSymbols.add("**/*.so") — its absence is what lets the strip run.
}

// Fail a release build loudly when no NDK is installed. A no-output task ALWAYS runs (never
// up-to-date), so it fires even when stripReleaseDebugSymbols is cached — a `doFirst` on the strip
// task would be silently skipped when that task is up-to-date. Debug builds don't depend on it.
val verifyNdkForStripping by tasks.registering {
    doLast {
        require(installedNdks.isNotEmpty()) {
            "No Android NDK found (looked under ${ndkRoot ?: "an unset SDK dir"}) — install one via " +
                "Android Studio → SDK Manager → SDK Tools → NDK (any version) so release native libs " +
                "are stripped instead of silently shipping unstripped."
        }
    }
}
tasks.matching { it.name == "bundleRelease" || it.name == "assembleRelease" }.configureEach {
    dependsOn(verifyNdkForStripping)
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

## Developing against a real app

Use a composite build instead of publishing while iterating:

```kotlin
// the consuming repo's settings.gradle.kts
includeBuild("../gradle-skip-spm")
```

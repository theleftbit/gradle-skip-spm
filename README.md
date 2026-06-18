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
    id("dev.skip.spm")
}

skipSpm {
    packageDir      = file("../polymarket-shared")  // the SwiftPM package
    module          = "USLive"                       // umbrella module to export
    abis            = listOf("aarch64", "armv7")
    namespacePrefix = "com.polymarket.shared"        // see "Namespace" below
}
```

The plugin:
- registers `exportSharedAars{Debug,Release}` — incremental Exec tasks keyed on the Swift sources,
- consumes the resulting AARs via `fileTree(outputDir).builtBy(exportTask)`,
- registers a `gradle-idea-ext` `afterSync` trigger so the IDE resolves the shared symbols on sync.

## Namespace

The `namespacePrefix` exists only because Skip currently emits a **shared** `AndroidManifest`
namespace across all exported modules, which collide when consumed together. The plugin rewrites
each AAR's manifest to `<namespacePrefix>.<module>`. If Skip emits unique per-module namespaces
upstream, this step (and the option) can be removed — tracked separately.

## Developing against a real app

Use a composite build instead of publishing while iterating:

```kotlin
// the consuming repo's settings.gradle.kts
includeBuild("../gradle-skip-spm")
```

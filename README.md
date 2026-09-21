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
- self-heals stale skip incremental state (a failed export against desynced `.build/` outputs is
  retried once from scratch) and rejects silently-broken "husk" AARs.

Exploded-AAR transform-cache growth is left to Gradle's own cleanup — configure it once, see
["Disk usage"](#disk-usage-gradles-transforms-cache) below.

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

## Staleness self-healing

`skip export` is incremental over the package's `.build/` scratch, and Gradle's input tracking
can't see inside it. Anything that re-resolves the package **outside** the export task — running
`skip android test` against the same package, or restoring `Package.resolved` (e.g. via `git
restore`) after skip's resolution changed it — can leave the transpiler outputs
(`.build/plugins/outputs`) referencing vendored files that no longer exist. The next incremental
export then fails with errors like:

- `the package manifest at '….build/plugins/outputs/…/skipstone/…/Packages/<pkg>/Package.swift'
  cannot be accessed (doesn't exist)`
- cascading `Unresolved reference 'SwiftPeerBridged'` (and other skip-bridge runtime symbols) in
  the generated Kotlin
- "husk" AARs that package successfully but whose `classes.jar` holds no compiled output
  (metadata-only modules are fine: SkipSwiftUI's `classes.jar` carries Kotlin metadata and zero
  `.class` entries, and is accepted)

The export task detects all three signatures, deletes `.build/plugins/outputs`, and retries the
export once from scratch (expect that one build to take as long as a clean export). If even the
clean re-export fails, the build fails with a pointer to `cleanSharedBuild` / `gradle clean`,
which deletes the package's entire `.build/`.

## Toolchain version check

The `skip` CLI (Homebrew) and the skipstone transpiler (the `skip` SwiftPM package your
`Package.swift` pins) ship from the same repo and version stream, and drift between them causes
cryptic export failures far from the cause — transpile errors against a newer `skip-fuse-ui`,
unresolved bridge symbols in the generated Kotlin. Before each export the task compares
`skip version` against the version the package declares:

- the **`Package.resolved` Skip pin takes priority**, as in previous plugin versions. It is the
  exact CLI target regardless of whether `Package.swift` declares `from:` or `exact:`;
- only when no resolved Skip version is available does **`Package.swift` provide a fallback**:
  `exact:` requires that version; `from:` / `.upToNextMajor(from:)` require at least that version.

For example, a resolved pin of `1.9.11` selects CLI `1.9.11` even if the manifest declares
`from: "1.9.7"` and CLI `1.9.12` is installed. Updating the CLI never changes the resolved pin.
If export fails, a newer Skip requirement resolved during that export can trigger one CLI update and retry.

By default (`skipVersionCheck = "install"`), the active CLI is kept when it meets the requirement.
Otherwise the selection works as follows:

1. Select the newest compatible CLI among the active executable and installations in Homebrew's
   Skip Cellar or legacy Skip Caskroom, including an older version when the project requires it.
   Invoke it by absolute path for this export; selecting an older version does not relink the
   global `skip` command.
2. If none matches, refresh Homebrew and check its latest stable Skip formula. Install or upgrade
   only if that release is **newer than the installed CLIs** and meets the project's requirement.
3. If an older exact version is missing, fail with a clear error. Never download historical
   releases or install an older formula. A missing exact version that is no longer Homebrew's
   latest release also fails, even when it would be newer than the installed CLI.

A compatible active CLI, including an explicit `skip.path` / `SKIP_PATH` override, is used without
querying Homebrew.
New installations use stable Homebrew releases only. Prereleases must be installed manually;
already installed prereleases may be selected, with the stable release ranked above prereleases
of the same version. Homebrew is required to enumerate installed versions or install a new version;
`SKIP_BREW_PATH` can override its executable for a custom installation.

With `--offline`, only already installed CLIs can be selected. The plugin sets
`HOMEBREW_NO_INSTALL_CLEANUP=1` for its Homebrew commands to retain old installations. Other
Homebrew commands outside the plugin can still remove them; runners that need to preserve Skip
versions should also export `HOMEBREW_NO_CLEANUP_FORMULAE=skip` in their environment (see the
[Homebrew FAQ](https://docs.brew.sh/FAQ#how-do-i-keep-old-versions-of-a-formula-when-upgrading)).
Installing/upgrading can update the globally linked formula, but selecting an existing CLI
never changes global links. Swift SDK, Android NDK, Java, Gradle, and Git credentials remain
the runner's responsibility.

Before export, installation only runs when no installed CLI meets the requirement. The active
CLI must report a parseable version. If export fails, the task reads the dependency requirement
again **before restoring `Package.resolved`**: Android resolution can introduce a newer Skip pin
than the one available before export. Only when this requirement is newer than the running CLI
does install mode select/install it and retry export **once**. The resolved pin still takes
priority over the manifest fallback. A failed build with no newer requirement does not trigger
an update, even if Homebrew has a newer release. Requirements observed before stale-output cleanup
are retained if the clean retry fails before resolving again, including failures from husk AARs.
This is a version comparison, not a diagnosis of the error: a newer requirement can trigger an
update even when an unrelated compilation error also exists. That error may persist on retry.
Compiler messages or suggestions to run `skip upgrade` alone do not trigger an update.
If no suitable newer CLI is available or upgrading fails, the task reports the original failure
with the recovery error. The final attempt has no further fetch, stale-output, or CLI-upgrade
retries. `--offline` and legacy `warn`/`fail`/`off` modes never recover by upgrading the CLI.
As before, each export restores changed `Package.resolved` contents only when the file existed
before the attempt and still exists afterward. Deleted files remain absent; newly generated
lockfiles are kept.
A missing CLI or unparseable version keeps the previous export behavior. Only a managed CLI
adds its own directory to the child PATH; otherwise the original environment is preserved.

The previous modes remain available: `"warn"` logs drift, `"fail"` rejects it, and `"off"`
disables version checking. These legacy modes retain their handling of unparseable CLI
versions. The check only runs when an export actually runs. Automatic installation requires a
Skip version in the manifest or lockfile, including one produced during Android resolution.

## Nested `gradle` builds

`skip export` shells out to a bare `gradle` for its nested per-module builds. Since 0.5.0 the
plugin leads the PATH those children see with **the Gradle installation running the outer build**
(the wrapper dist when launched via `gradlew`/Android Studio), so the nested builds always match
the repo's pinned Gradle version — no Homebrew-vs-wrapper drift, and no "provide `gradle` on
PATH" shim in CI containers where no system Gradle exists.

The children also run with the **Gradle build cache off** by default: their expensive step (the
Swift cross-compile) is an ad-hoc exec Gradle can't cache anyway, and on billed remote caches
(e.g. Bitrise) every cache-reading invocation costs money. The outer build's own caching is
unaffected. Opt the children back in with:

```kotlin
skipSpm {
    childGradleBuildCache = true
}
```

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
entries** while the previous ones sit until Gradle's cleanup reaps entries unused for ~7 days
(the default). Under active development that accumulates gigabytes per day.

**The plugin does not prune this cache itself** (versions ≤ 0.2.x did; removed in 0.3.0, and the
`pruneStaleTransforms` option is now a deprecated no-op). Transform entries are only attributable
to an AAR by *name* — and every checkout or git worktree of the same app exports identically-named
AARs, so a plugin-side prune from one checkout could delete an entry that another checkout's live
Gradle daemon still referenced. Daemons cache transform locations in memory for their whole
lifetime *without re-checking they exist*, so the victim build fails with unresolved shared classes
or dangling classpath paths and no local cause. Only Gradle itself keeps the cross-build usage
journal that makes deletion safe.

Instead, shorten Gradle's own retention for "created resources" (the bucket transforms live in).
Gradle's cleanup never removes an entry any recent build used — from *any* checkout sharing the
user home — which is exactly the guarantee a plugin can't provide. This is Gradle-user-home-wide
configuration, so Gradle only accepts it in an **init script** (not in a project's
`settings.gradle.kts` or `build.gradle.kts`):

```kotlin
// ~/.gradle/init.d/cache-retention.init.gradle.kts
beforeSettings {
    caches {
        // Transforms fall under "created resources"; the default retention is 7 days.
        createdResources.setRemoveUnusedEntriesAfterDays(2)
    }
}
```

By default Gradle runs this cleanup at most once every 24 h, at the end of a build session; add
`cleanup.set(Cleanup.ALWAYS)` inside the `caches {}` block to run it after every session if disk
pressure is severe.

Never `rm -rf` the transforms cache by hand while builds or IDE syncs are running — stop the
daemons first (`./gradlew --stop`).

## Developing against a real app

Use a composite build instead of publishing while iterating:

```kotlin
// the consuming repo's settings.gradle.kts
includeBuild("../gradle-skip-spm")
```

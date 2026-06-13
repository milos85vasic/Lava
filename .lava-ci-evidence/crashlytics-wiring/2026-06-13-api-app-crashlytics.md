# `:api-app` Firebase Crashlytics Wiring — Implementation Evidence

- **Date:** 2026-06-13
- **Base commit:** `f38222d8` (worktree branch `worktree-agent-a3447838359390cc5`)
- **Plan:** `docs/research/2026-06-13-api-app-crashlytics-wiring-plan.md` (Option A — shared module)
- **Constitutional bindings:** §6.AC (Comprehensive Non-Fatal Telemetry), §6.O (Crashlytics coverage), §6.H (Credential Security — no secret committed), Decoupled Reusable Architecture (no copy-paste between :app and :api-app), Seventh Law clause 1 (Bluff-Audit stamp).

## What changed

### New shared module `:core:analytics-firebase` (Decoupled Reusable Architecture)
The Firebase-backed `AnalyticsTracker` implementation, its Hilt DI module, the resilient
initializer, and the no-op fallback were app-private to `:app`. They are now a single shared
module both `:app` and `:api-app` consume — copy-pasting between the two apps is the forbidden
bluff vector the rule names.

- `core/analytics-firebase/build.gradle.kts` — new. `lava.android.library` + `lava.android.hilt`;
  `api(project(":core:common"))` + `api(platform(libs.firebase.bom))` + `api(libs.firebase.{analytics,crashlytics,perf})`
  (exposed via `api` so consuming apps get the `Firebase.crashlytics/analytics/performance` ktx accessors transitively).
  The google-services + crashlytics **gradle plugins are NOT applied here** — those process each app's
  `google-services.json` + upload the per-app mapping file, so they belong in the application modules
  (inherited via `lava.android.application`).
- Moved (via `git mv`, history preserved) from `app/src/main/.../firebase/` → `core/analytics-firebase/src/main/kotlin/lava/analytics/firebase/`:
  `FirebaseAnalyticsTracker.kt`, `FirebaseProvidesModule.kt`, `FirebaseInitializer.kt`, `NoOpAnalyticsTracker.kt`.
  Package `digital.vasic.lava.client.firebase` → `lava.analytics.firebase`. `FirebaseInitializer` +
  `FirebaseProvidesModule` made `public` (were `internal`) so both apps' Application classes + Hilt graphs reach them.
  `FirebaseInitializer.initialize(...)` already takes applicationId/versionName/versionCode as params → app-agnostic, no `BuildConfig` reference inside the module.
- Moved tests: `FirebaseAnalyticsTrackerTest.kt` + `FirebaseInitializerTest.kt` → the new module's test source.
- `settings.gradle.kts` — `include(":core:analytics-firebase")`.

### `:app` — keeps compiling, analytics tests stay green
- `app/build.gradle.kts` — replaced the 4 `implementation(libs.firebase.*)` lines with
  `implementation(project(":core:analytics-firebase"))` (Firebase artifacts now transitive via the module's `api(...)`).
- `LavaApplication.kt` — import `digital.vasic.lava.client.firebase.FirebaseInitializer` → `lava.analytics.firebase.FirebaseInitializer`. No behavior change.
- `Challenge13FirebaseColdStartResilienceTest.kt` — KDoc reference to the moved validation-test path updated (doc-sync, no code change).

### `:api-app` — Crashlytics wired (the §6.AC/§6.O gap closed)
- `api-app/build.gradle.kts` — added `implementation(project(":core:analytics-firebase"))` + `implementation(project(":core:common"))`.
- `ApiApplication.kt` — injects `AnalyticsTracker` (Hilt binding from the shared module) + calls
  `FirebaseInitializer.initialize(...)` in `onCreate()` (mirrors `LavaApplication`), and sets
  Crashlytics custom key `artifact=api-app` so api-app vs client crashes are separable in the shared
  Firebase project. Telemetry routes through the **Kotlin bridge** — NO Firebase REST key in the Go `.so` (§6.H surface reduction per the plan's recommendation).
- The google-services + crashlytics gradle plugins were already applied to `:api-app` via
  `AndroidApplicationConventionPlugin`; `api-app/google-services.json` already registers
  `digital.vasic.lava.api` + `…api.dev`. Only CODE was missing.

### §6.H — no secret committed
`git ls-files | grep google-services.json` → NONE. Both `app/google-services.json` and
`api-app/google-services.json` remain gitignored (`**/google-services.json`). For the local build only,
the operator's gitignored `.env`, `keystores/`, and both `google-services.json` were symlinked from the
parent checkout `/Volumes/T7/Projects/Lava` — `git check-ignore` confirms all four stay ignored; they
do NOT appear in `git status`.

## Anti-bluff falsifiability (Seventh Law clause 1)

New test `core/analytics-firebase/src/test/.../FirebaseProvidesModuleTest.kt` exercises the EXACT
`FirebaseProvidesModule.analyticsTracker(...)` factory both apps' Hilt graphs invoke. SUT is the real
module + the real `FirebaseAnalyticsTracker`; only the `FirebaseCrashlytics` SDK boundary is a mock.

```
Bluff-Audit: FirebaseProvidesModuleTest
  Mutation: in FirebaseProvidesModule.analyticsTracker, replace the body with
    `return NoOpAnalyticsTracker` (drop the real-impl branch).
  Observed-Failure: resolvesToFirebaseBackedTrackerAndForwardsNonFatal FAILS —
    `java.lang.AssertionError: AnalyticsTracker binding must resolve to
    FirebaseAnalyticsTracker when a Crashlytics sink is available (got NoOpAnalyticsTracker)`,
    and the subsequent `verify { crashlytics.recordException(...) }` finds 0 invocations.
  Reverted: yes.
```

(Rehearsal result is filled in below after execution.)

## Build + test results

### Safety gate — `./gradlew :app:assembleDebug :api-app:assembleDebug --max-workers=2 --console=plain`

```
BUILD SUCCESSFUL in 3m 2s
942 actionable tasks: 34 executed, 908 up-to-date
> Task :api-app:packageDebug
> Task :api-app:assembleDebug
```

Both APKs assembled. `:app` proved the google-services plugin accepts the existing JSON,
the manifest merges, and `:app` still builds with the extracted module. `:api-app` assembled
with the embedded Go `.so` (`ALL REQUESTED ABIS BUILT OK`, source-hash
`6980230aad5c4b3a8f6cf96cf2382210364b3c73a8ffaf37d070c9e8f064d200`) + the new Firebase wiring;
`:api-app:compileDebugJavaWithJavac` (Hilt-generated) proves the `AnalyticsTracker` binding from
`:core:analytics-firebase` resolves in api-app's Hilt graph.

**Worktree setup note (environmental, not part of the change):** this isolated worktree began with
the Go `vasic-digital` submodules + `tracker_sdk` un-checked-out, and the gitignored secrets absent.
To run the build I `git submodule update --init`'d the 16 Go submodules the embed `.so` requires and
symlinked the operator's gitignored `.env` / `keystores/` / both `google-services.json` from the
parent checkout. The first two build attempts failed for these environmental reasons (missing `.env`,
then `submodules/cache/go.mod: no such file or directory`) — NOT from the Firebase change. After the
environment was provisioned, the build is GREEN.

### Unit tests + falsifiability — `:core:analytics-firebase:testDebugUnitTest` + `:app:testDebugUnitTest`

```
BUILD SUCCESSFUL in 56s
```

Per-class results (from `build/test-results/.../TEST-*.xml`):
- `lava.analytics.firebase.FirebaseAnalyticsTrackerTest` — tests=7 failures=0 errors=0
- `lava.analytics.firebase.FirebaseInitializerTest` — tests=5 failures=0 errors=0
- `lava.analytics.firebase.FirebaseProvidesModuleTest` — tests=2 failures=0 errors=0 (NEW)
- `:app` `NavTeardownCrashReporterTest` — tests=6 failures=0 (still green after the move)
- `:app` `ApiKeyClientTest` — tests=3 failures=0 (still green after the move)

One module-build fix was required during testing: the moved tests call `android.util.Log` /
`android.os.Bundle`, which threw `Method w in android.util.Log not mocked` on the plain JVM.
`:app` had `testOptions { unitTests.isReturnDefaultValues = true }`; I added the identical setting
to `core/analytics-firebase/build.gradle.kts` so the moved tests stay behaviorally identical (no
bluff drift). This setting affects unit-test config only, not the assembled APK — the safety-gate
build above is unaffected.

**Falsifiability rehearsal (Seventh Law clause 1) — EXECUTED:**
```
Mutation: FirebaseProvidesModule.analyticsTracker — `return NoOpAnalyticsTracker` before the
          `return FirebaseAnalyticsTracker(...)` line (drop the real-impl branch).
Observed-Failure (verbatim):
  FirebaseProvidesModuleTest > resolvesToFirebaseBackedTrackerAndForwardsNonFatal FAILED
      java.lang.AssertionError at FirebaseProvidesModuleTest.kt:58
  2 tests completed, 1 failed
  BUILD FAILED in 31s
Reverted: yes (confirmed GREEN after revert).
```
Line 58 is the `assertTrue("...must resolve to FirebaseAnalyticsTracker...")` assertion — the test
fails exactly when the api-app's injected `AnalyticsTracker` would silently degrade to the no-op
that never reports telemetry. This is the §6.AC/§6.O gap the wiring closes.

### spotlessCheck

`:core:analytics-firebase:spotlessCheck` — PASS. My touched `:app`/`:api-app` files
(`ApiApplication.kt`, `LavaApplication.kt`, `Challenge13…Test.kt`) are spotless-clean: a
`:app:spotlessApply` left them unchanged beyond my own edits (verified by `git diff --stat`).

`:app:spotlessKotlinCheck` reported pre-existing violations in THREE files I did NOT touch —
`Challenge06DownloadTorrentFileTest.kt`, `Challenge39DynamicProviderDiscoveryTest.kt`,
`Challenge40JackettIndexerProviderTest.kt` (import-order + argument-wrapping debt present in the
base commit `f38222d8`; `git diff HEAD` shows them unmodified by me). These are NOT part of this
change and were reverted out of the diff (`git checkout --`) to keep the change scoped — they are
pre-existing `:app` spotless debt, tracked separately.

## Summary

The §6.AC/§6.O gap is closed: `:api-app` now has a Firebase-backed `AnalyticsTracker` Hilt binding +
a resilient Crashlytics initializer, sharing ONE implementation with `:app` via the new
`:core:analytics-firebase` module (no copy-paste). `:app` still builds and its analytics tests stay
green. The wiring is proven by an executed, reverted falsifiability rehearsal. No secret committed.

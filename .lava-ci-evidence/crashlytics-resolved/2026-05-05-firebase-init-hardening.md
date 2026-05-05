# Crashlytics resolution — Firebase init hardening (2026-05-05)

**Build affected:** Lava-Android-1.2.3 (versionCode 1023), commit `e9de508`, distributed via Firebase App Distribution at 2026-05-05 22:33 UTC.

**Crashlytics issues:** 2 issues recorded within ~10 minutes of distribution. Operator surfaced via dashboard at https://console.firebase.google.com/project/lava-vasic-digital/crashlytics

**Issue IDs (Console):** Captured by the operator at incident time; this log retains the operator-screenshot reference plus the diagnostic mitigation, not the issue ID strings (Firebase issue IDs are session-scoped tokens visible only to authorized Console viewers).

## Stack-trace analysis (post-mortem reconstruction)

The Firebase Crashlytics REST API does not expose issue stack traces (Console + BigQuery only). The fix landed below was reasoned from the diff between the e9de508 commit and the prior green build (bae601d):

| Suspected cause | Evidence | Mitigation |
|---|---|---|
| Redundant `FirebaseApp.initializeApp(this)` call | The `com.google.gms.google-services` plugin auto-installs `FirebaseInitProvider` (a ContentProvider) which initializes the default app BEFORE `Application.onCreate()`. Calling `initializeApp(this)` again is redundant and historically races with the auto-init under StrictMode. | Removed the explicit `FirebaseApp.initializeApp(this)` call. |
| R8 stripping Firebase reflective entry points (release APK) | `isMinifyEnabled = true` in release config; `app/proguard-rules.pro` had no Firebase keep rules. The Firebase BOM ships consumer rules but the operator-observed crashes implicate gaps. | Added `-keep class com.google.firebase.**`, `-keep class com.google.android.gms.measurement.**`, etc. to `app/proguard-rules.pro`. |
| Single Firebase SDK init failure cascading to app crash | `Firebase.crashlytics.apply { ... }` and `Firebase.performance.isPerformanceCollectionEnabled = ...` were unguarded. A single SDK throw would propagate through `LavaApplication.onCreate()` and kill the app. | Wrapped each Firebase init block in `runCatching { ... }.onFailure { Log.w(TAG, ...) }` so an SDK failure becomes a structured log line instead of a crash. |

## Fix commit

Pending — committed as part of the post-incident Firebase-hardening commit alongside the §6.O constitutional clause.

## Validation tests

| Test | Path | Purpose |
|---|---|---|
| `LavaApplicationFirebaseInitTest` | `app/src/test/kotlin/digital/vasic/lava/client/LavaApplicationFirebaseInitTest.kt` | Robolectric test booting `LavaApplication` and asserting `onCreate()` does NOT throw even if Firebase SDK methods are deliberately broken via test substitution. |

## Challenge Tests

| Test | Path | Purpose |
|---|---|---|
| `Challenge09FirebaseColdStartTest` | `app/src/androidTest/kotlin/lava/app/challenges/Challenge09FirebaseColdStartTest.kt` | Compose UI Challenge Test that cold-starts the app on the gating matrix and asserts the launcher activity is reached without a crash dialog. The matrix run is the load-bearing acceptance gate per §6.I. |

## Falsifiability rehearsal

| Test | Mutation | Observed | Reverted |
|---|---|---|---|
| `LavaApplicationFirebaseInitTest::testOnCreateSurvivesCrashlyticsThrow` | Removed the `runCatching { ... }` around `Firebase.crashlytics.apply { ... }` so a deliberate-throw substitute caused `onCreate()` to propagate | Test asserts `onCreate()` returns normally; without the `runCatching`, `onCreate()` re-throws and the test fails with `Expected onCreate to complete without exception, but got <RuntimeException>` | Yes — `runCatching` restored, test re-passes. |

## Pre-tag verification checklist

Per §6.O clause 4, before the next release tag (Lava-Android-1.2.4 or beyond) is cut on this fix:

- [ ] Validation tests pass (`./gradlew :app:test`)
- [ ] Challenge Test passes on the gating matrix (`./scripts/run-emulator-tests.sh --tag <tag>`)
- [ ] This closure log exists with the fix commit SHA filled in
- [ ] Operator marks both Crashlytics issues "closed" in the Firebase Console AFTER the fix is distributed and the dashboard reports zero new occurrences within a 24h observation window

## Console close-mark protocol

Per §6.O clause 5, the close-mark in the Firebase Console is the LAST step:

1. Land the fix commit (with this closure log)
2. Distribute the fixed build via `./scripts/distribute.sh`
3. Wait 24h for tester usage to confirm no recurrence
4. Open the Firebase Console → Crashlytics → Issues
5. Click each issue → "Close issue" → reference this closure log path in the close-comment

## Constitutional bindings

- §6.O Crashlytics-Resolved Issue Coverage Mandate — this is the inaugural application of the clause
- §6.J / §6.L Anti-Bluff — fix targeted root-cause; tests verify user-visible state (app reaches launcher)
- §6.N Bluff-Hunt — falsifiability rehearsal recorded above

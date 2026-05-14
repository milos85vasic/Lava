# Crashlytics issue 7df61fdba64f9928b067624d6db395ca — closure log

**Issue ID:** `7df61fdba64f9928b067624d6db395ca`
**Title:** `kotlinx.coroutines.JobCancellationException`
**Subtitle:** "StandaloneCoroutine was cancelled"
**Type:** NON_FATAL
**First seen:** 1.2.21 (1041)
**Last seen:** 1.2.21 (1041)
**Events at closure:** 8
**Impacted users at closure:** 1
**State at closure:** OPEN (operator marks closed in Console after 1.2.22 ships)

## Root cause

`viewModelScope.launch { ... }` blocks throw `JobCancellationException` when the ViewModel is cleared while a coroutine is in flight. The previous `analytics.recordNonFatal(throwable, ...)` calls forwarded these to Crashlytics's non-fatal feed. Cancellations are structured-concurrency teardown signals, NOT real failure modes — the user is closing the screen, the work isn't supposed to complete. Reporting them creates noise that masks real issues.

## Fix (commit `2bf5ecad` follow-up — actual fix lands in this 1.2.22 cycle)

`FirebaseAnalyticsTracker.recordNonFatal` now filters throwables that ARE or WRAP a `CancellationException` (chains via `cause` up to depth 32). The early-return logs at DEBUG level for diagnostics but does NOT call `setCustomKey` or `recordException`.

## Validation tests

`app/src/test/.../FirebaseAnalyticsTrackerTest.kt` — 3 new test cases:
- `recordNonFatal filters CancellationException (Crashlytics 7df61fdb)` — direct CancellationException
- `recordNonFatal filters when CancellationException is wrapped` — wrapped via cause chain
- `recordNonFatal still reports real exceptions (cancellation filter does not over-filter)` — discrimination test ensures non-cancellation exceptions still reach Crashlytics

All 3 PASS via `./gradlew :app:testDebugUnitTest --tests "FirebaseAnalyticsTrackerTest"` BUILD SUCCESSFUL.

## Closure protocol

Operator marks issue closed in Firebase Console after 1.2.22 ships and no further events appear in the cancellation-class.

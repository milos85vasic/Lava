package lava.common.analytics

import kotlinx.coroutines.CancellationException

/**
 * §6.AC + Crashlytics issue `7df61fdba64f9928b067624d6db395ca` (8 events /
 * 1 user / 1.2.21 — "kotlinx.coroutines.JobCancellationException —
 * StandaloneCoroutine was cancelled").
 *
 * Root cause of that issue: a broad `catch (e: Exception) { ... }` inside a
 * `viewModelScope.launch { }` body catches Kotlin's structured-concurrency
 * [CancellationException] (thrown when the scope is cancelled — e.g. the user
 * navigates away mid-operation, or the ViewModel is cleared). Two bugs follow
 * from swallowing it:
 *
 *  1. **Telemetry pollution** — the cancellation is recorded as a non-fatal,
 *     even though it is normal teardown, not a failure mode. This is the
 *     visible Crashlytics symptom.
 *  2. **Broken cooperative cancellation** — the catch body keeps running on a
 *     coroutine that has been told to stop, typically reducing a spurious
 *     `Failure` UI state on a screen the user has already left.
 *
 * The idiomatic Kotlin fix is to RE-THROW [CancellationException] before any
 * generic error handling. Call this at the TOP of every `catch (e: Exception)`
 * / `catch (t: Throwable)` block that wraps suspending work, BEFORE recording a
 * non-fatal or reducing an error state:
 *
 * ```
 * try {
 *     doSuspendingWork()
 * } catch (e: Exception) {
 *     e.rethrowIfCancellation()           // cooperative cancellation honoured
 *     analytics.recordNonFatal(e, ctx)    // only REAL failures reach here
 *     reduce { state.copy(error = ...) }
 * }
 * ```
 *
 * Detection walks the cause chain (a CancellationException is sometimes wrapped
 * by a higher-level throwable) with a bounded depth to avoid cyclic-cause
 * pathologies. The sink-level filter in `FirebaseAnalyticsTracker.recordNonFatal`
 * remains as defense-in-depth, but rethrowing here is the correct layer because
 * it also restores cooperative cancellation, which the sink filter cannot.
 */
fun Throwable.rethrowIfCancellation() {
    var t: Throwable? = this
    var depth = 0
    while (t != null && depth < MAX_CAUSE_DEPTH) {
        if (t is CancellationException) throw this
        t = t.cause
        depth++
    }
}

private const val MAX_CAUSE_DEPTH = 32

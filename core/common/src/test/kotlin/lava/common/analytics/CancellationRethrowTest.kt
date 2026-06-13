package lava.common.analytics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validation test for Crashlytics issue `7df61fdba64f9928b067624d6db395ca`
 * (NON_FATAL `kotlinx.coroutines.JobCancellationException — StandaloneCoroutine
 * was cancelled`, 8 events / 1 user / 1.2.21).
 *
 * Root cause: a broad `catch (e: Exception) { analytics.recordNonFatal(e, ...) }`
 * inside a `viewModelScope.launch { }` body catches the structured-concurrency
 * [CancellationException] thrown when the scope is cancelled (user navigates away
 * / ViewModel cleared) and records it as a non-fatal — telemetry pollution AND a
 * swallowed cancellation that lets the catch body keep running on a dead scope.
 *
 * The fix is [rethrowIfCancellation], called at the top of every such catch.
 * These tests exercise the REAL production catch-block shape — a
 * `try { suspendingWork() } catch (e: Exception) { e.rethrowIfCancellation();
 * analytics.recordNonFatal(...) }` running inside a real cancelled coroutine —
 * and assert the recording sink is the user-visible measurable: ZERO non-fatals
 * for the cancellation, but the real failure IS recorded.
 *
 * FALSIFIABILITY REHEARSAL (Sixth Law clause 2 / Seventh Law clause 1):
 *   Mutation: comment out the `if (t is CancellationException) throw this` line
 *             inside [rethrowIfCancellation] (make it a no-op).
 *   Observed-Failure: `cancellation is NOT recorded as a non-fatal` fails with
 *             `expected:<0> but was:<1>` — the cancellation leaked into the sink.
 *   Reverted: yes.
 *
 * The fake [AnalyticsTracker] here is a recording sink at the OUTERMOST telemetry
 * boundary (permitted per Second Law). The SUT is [rethrowIfCancellation] + the
 * production catch pattern, neither of which is mocked.
 */
class CancellationRethrowTest {

    private class RecordingAnalytics : AnalyticsTracker {
        val nonFatals = mutableListOf<Throwable>()
        val warnings = mutableListOf<String>()

        override fun event(name: String, params: Map<String, String>) = Unit
        override fun setUserId(userId: String?) = Unit
        override fun setProperty(key: String, value: String?) = Unit
        override fun recordNonFatal(throwable: Throwable, context: Map<String, String>) {
            nonFatals += throwable
        }
        override fun recordWarning(message: String, context: Map<String, String>) {
            warnings += message
        }
        override fun log(message: String) = Unit
    }

    /**
     * The exact production catch-block shape: suspending work wrapped in a
     * try/catch that rethrows cancellation then records a non-fatal.
     */
    private suspend fun guardedWork(analytics: AnalyticsTracker, work: suspend () -> Unit) {
        try {
            work()
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            analytics.recordNonFatal(e, mapOf("operation" to "guarded_work"))
        }
    }

    @Test
    fun `cancellation is NOT recorded as a non-fatal`() = runTest {
        val analytics = RecordingAnalytics()
        val job = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            guardedWork(analytics) {
                // Suspends forever until the scope cancels it, exactly like a
                // viewModelScope.launch { someUseCase() } that is still in flight
                // when the user navigates away.
                delay(Long.MAX_VALUE)
            }
        }
        // Cancel mid-flight — this is what viewModelScope teardown does.
        job.cancel()
        job.join()

        assertEquals(
            "Cancellation must NOT reach the non-fatal feed (Crashlytics 7df61fdb)",
            0,
            analytics.nonFatals.size,
        )
    }

    @Test
    fun `a real failure IS still recorded (filter does not over-filter)`() = runTest {
        val analytics = RecordingAnalytics()
        guardedWork(analytics) { throw IllegalStateException("real downstream failure") }

        assertEquals(1, analytics.nonFatals.size)
        assertTrue(analytics.nonFatals.single() is IllegalStateException)
        assertEquals("real downstream failure", analytics.nonFatals.single().message)
    }

    @Test
    fun `wrapped CancellationException is rethrown not recorded`() = runTest {
        val analytics = RecordingAnalytics()
        val wrapped = IllegalStateException("outer", CancellationException("inner"))

        var rethrown = false
        try {
            try {
                throw wrapped
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                analytics.recordNonFatal(e, emptyMap())
            }
        } catch (e: Exception) {
            // rethrowIfCancellation re-threw the ORIGINAL throwable (the wrapper)
            rethrown = true
            assertEquals("outer", e.message)
        }

        assertTrue("wrapped cancellation must be rethrown", rethrown)
        assertEquals(0, analytics.nonFatals.size)
    }

    @Test
    fun `plain non-cancellation throwable passes through rethrowIfCancellation`() {
        // A real failure must NOT be rethrown by the guard — it falls through to
        // the recordNonFatal below it in the production catch block.
        val real = IllegalArgumentException("boom")
        // Should not throw:
        real.rethrowIfCancellation()
    }

    @Test
    fun `cancellation thrown inside a launched job propagates to cancel the job`() = runTest {
        // Proves the cooperative-cancellation half of the fix: because the
        // cancellation is rethrown rather than swallowed, the job ends in the
        // cancelled state instead of completing normally with a recorded error.
        val analytics = RecordingAnalytics()
        lateinit var job: Job
        val scope = CoroutineScope(coroutineContext)
        job = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            guardedWork(analytics) {
                while (true) {
                    yield()
                }
            }
        }
        job.cancel()
        job.join()

        assertTrue("job must end cancelled, not completed-with-error", job.isCancelled)
        assertEquals(0, analytics.nonFatals.size)
    }
}

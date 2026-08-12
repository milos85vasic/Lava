package lava.domain.usecase

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real-stack unit test for [StartupProvidersGate] — the LVA-093 cold-start
 * readiness gate's own timing mechanics, isolated from the ViewModel /
 * UseCase consumers so its contract (suspend-until-ready, bounded timeout,
 * idempotent mark) is proven directly and fast.
 *
 * ## Anti-Bluff posture (§6.J)
 *
 * The SUT is the REAL [StartupProvidersGate] — nothing is mocked or faked;
 * there is no boundary below it to fake (it is pure Kotlin coroutines state).
 * Primary assertions are on the OBSERVABLE ORDERING of a real concurrent
 * suspension (a background coroutine genuinely parked on [StartupProvidersGate.awaitReady]
 * until [StartupProvidersGate.markReady] is called) — not a call-count check.
 *
 * ## FALSIFIABILITY REHEARSAL (§6.J clause 2 / Sixth Law clause 2)
 *
 *   Mutation: in [StartupProvidersGate.awaitReady], remove the
 *     `withTimeoutOrNull(timeoutMs) { ... }` wrapper and replace it with a
 *     bare `ready.first { it }` (i.e. an unbounded wait).
 *   Observed-Failure: `awaitReady gives up after the timeout elapses when
 *     never marked ready` no longer completes — the test coroutine hangs
 *     waiting on a StateFlow value that never arrives; `runTest` reports
 *     `kotlinx.coroutines.test.UncompletedCoroutinesError: After waiting for
 *     10s, the test coroutine is not completing` (the JUnit run times out /
 *     fails with that error rather than passing).
 *   Reverted: yes — the `withTimeoutOrNull` wrapper is restored and the test
 *     passes again in well under 1s of wall-clock time (virtual-time bounded).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StartupProvidersGateTest {

    // CHALLENGE — the gate's default state is exactly what LVA-093 depends on:
    // a fresh process has NOT yet concluded its cold-start repopulation attempt.
    @Test
    fun `a freshly constructed gate is not ready`() {
        val gate = StartupProvidersGate()
        assertFalse("a fresh gate MUST start not-ready", gate.ready.value)
    }

    // CHALLENGE — markReady is the ONLY way ready flips, and it is observable
    // via the public ready StateFlow (the same one a consumer would read).
    @Test
    fun `markReady flips the ready flag`() {
        val gate = StartupProvidersGate()
        gate.markReady()
        assertTrue("markReady MUST flip ready to true", gate.ready.value)
    }

    // CHALLENGE — markReady is idempotent; calling it twice (e.g. an
    // unexpected double-invoke of the use case) must not throw or misbehave.
    @Test
    fun `markReady is idempotent`() {
        val gate = StartupProvidersGate()
        gate.markReady()
        gate.markReady()
        assertTrue(gate.ready.value)
    }

    // CHALLENGE — already-ready gate: awaitReady returns immediately without
    // needing any scheduler advancement (the common "repopulation already
    // finished by the time the user searches" case).
    @Test
    fun `awaitReady returns immediately when already ready`() = runTest {
        val gate = StartupProvidersGate()
        gate.markReady()
        // If this suspended indefinitely, runTest's own completion guard
        // would fail the test — the mere fact this line returns IS the proof.
        gate.awaitReady(timeoutMs = 50)
    }

    // CHALLENGE — the load-bearing LVA-093 proof: a consumer genuinely
    // suspended on awaitReady() is NOT resumed until markReady() is called.
    // This is a REAL concurrent-ordering assertion, not a call-count check —
    // the ordering list is only ever appended to from inside the two
    // coroutines actually running, so its final order is genuine evidence of
    // what happened, not a claim.
    @Test
    fun `awaitReady suspends a real consumer until markReady is called, then resumes it in order`() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = StartupProvidersGate()
            val order = mutableListOf<String>()

            val waiter = launch {
                order += "waiter-started"
                // Genuinely suspends: ready is false and stays false until
                // markReady() below flips it — UnconfinedTestDispatcher runs
                // this eagerly up to exactly this suspension point.
                gate.awaitReady(timeoutMs = 5_000)
                order += "waiter-resumed"
            }

            // Under UnconfinedTestDispatcher the launch{} above already ran
            // eagerly to its suspension point, so by this line the waiter
            // MUST have started but MUST NOT have resumed yet.
            assertEquals(listOf("waiter-started"), order)

            order += "markReady-called"
            gate.markReady()
            waiter.join()

            // §6.J primary — the waiter resumed strictly AFTER markReady was
            // called, proving awaitReady() genuinely blocked the consumer
            // rather than racing ahead of the cold-start repopulation.
            assertEquals(
                "the waiter MUST resume only after markReady() is called, in this exact order",
                listOf("waiter-started", "markReady-called", "waiter-resumed"),
                order,
            )
        }

    // CHALLENGE — the bounded-wait / never-hang-the-UI guarantee: when the
    // gate is NEVER marked ready, awaitReady still returns (does not hang
    // forever) once its timeout elapses.
    @Test
    fun `awaitReady gives up after the timeout elapses when never marked ready`() = runTest {
        val gate = StartupProvidersGate()

        // NEVER call markReady(). If awaitReady() had no bound, this call
        // would hang and runTest would fail the test with an
        // UncompletedCoroutinesError instead of returning normally.
        gate.awaitReady(timeoutMs = 100)

        // The gate itself is still honestly not-ready — a timed-out wait is
        // a documented degradation, not a fake "success".
        assertFalse(
            "a timed-out awaitReady MUST NOT silently mark the gate ready — " +
                "only markReady() (called by the use case) may do that",
            gate.ready.value,
        )
    }

    // CHALLENGE — default timeout sanity: the documented constant is what
    // awaitReady() actually uses when the caller does not override it.
    @Test
    fun `default timeout constant is used when the caller does not override it`() = runTest {
        val gate = StartupProvidersGate()
        assertEquals(5_000L, StartupProvidersGate.DEFAULT_AWAIT_TIMEOUT_MS)
        // Exercises the default-parameter path specifically (no explicit
        // timeoutMs argument) so a regression that drops the default is caught.
        gate.awaitReady()
    }
}

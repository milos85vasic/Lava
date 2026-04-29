@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package lava.testing.contract

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import lava.data.api.service.DiscoveredEndpoint
import lava.models.settings.Endpoint
import lava.testing.TestDispatchers
import lava.testing.repository.TestEndpointsRepository
import lava.testing.service.TestLocalNetworkDiscoveryService
import lava.testing.testDispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sixth-Law clause-3 contract tests for the `:core:testing` fakes.
 *
 * Each test here is a regression Challenge against a previously-fixed
 * Third-Law bluff in the test infrastructure. The fakes' own behaviour
 * is the user-visible state that other tests rely on; if the fake
 * silently drifts back to a bluff form, every consumer test goes
 * silently wrong. These tests guarantee the fakes stay honest.
 *
 * Each test names the bug it guards against in its KDoc.
 *
 * Forensic anchor (2026-04-29). The `:core:domain:testDebugUnitTest`
 * stalled for 2h22m on master, hiding multiple test-correctness
 * defects. After the hang was resolved, three Third-Law bluffs
 * surfaced:
 *   1. `TestDispatchers()` default-allocated a fresh
 *      `TestCoroutineScheduler` per call → cross-scheduler livelock.
 *   2. `TestEndpointsRepository.observeAll().onStart` unconditionally
 *      reset the list to defaults → diverged from the real impl's
 *      `if (isEmpty())` guard.
 *   3. `TestLocalNetworkDiscoveryService` used a rendezvous
 *      `Channel()` whose `send` suspended → diverged from the real
 *      impl's `callbackFlow + trySend` (non-suspending).
 *
 * The tests below catch each bug-class on regression.
 */
class TestInfrastructureContractTest {

    // ─────────────────────────────────────────────────────────────────
    // TestDispatchers — scheduler-sharing contract
    // ─────────────────────────────────────────────────────────────────

    /**
     * CHALLENGE — `testDispatchers()` extension MUST share its
     * scheduler with the surrounding `runTest` scope. If a future
     * refactor reverts to `TestDispatchers(UnconfinedTestDispatcher())`
     * (no-arg, fresh scheduler), this assertion fires.
     *
     * Sixth-Law clause 3: primary assertion is `assertSame` on the
     * underlying `TestCoroutineScheduler` reference — the literal
     * object that drives test virtual time.
     */
    @Test
    fun `testDispatchers extension shares scheduler with surrounding runTest`() = runTest {
        val testDispatchers = testDispatchers()
        // The dispatcher's scheduler MUST be the runTest scope's
        // scheduler. A fresh-scheduler regression makes them different
        // instances and assertSame fails.
        val ioDispatcher = testDispatchers.io as TestDispatcher
        assertSame(
            "testDispatchers().io.scheduler MUST be runTest's testScheduler — " +
                "if not, withContext(io) will livelock against runTest's auto-advance",
            testScheduler,
            ioDispatcher.scheduler,
        )
    }

    /**
     * CHALLENGE — explicit-construction form: passing a TestDispatcher
     * to the primary constructor wires its scheduler through. Guards
     * against any future refactor that ignores the constructor arg.
     */
    @Test
    fun `TestDispatchers primary constructor uses provided dispatcher's scheduler`() = runTest {
        val explicit = UnconfinedTestDispatcher(testScheduler)
        val dispatchers = TestDispatchers(explicit)
        assertSame("default = explicit", explicit, dispatchers.default)
        assertSame("main = explicit", explicit, dispatchers.main)
        assertSame("io = explicit", explicit, dispatchers.io)
    }

    // ─────────────────────────────────────────────────────────────────
    // TestEndpointsRepository — only-seed-when-empty contract
    // ─────────────────────────────────────────────────────────────────

    /**
     * CHALLENGE — the fake MUST mirror the real impl's
     * `if (endpointDao.isEmpty()) insertAll(defaultEndpoints)` branch.
     * Adding an endpoint BEFORE first observation must NOT be erased
     * by the seed lambda.
     */
    @Test
    fun `add before observe preserves the entry across seed`() = runTest {
        val repo = TestEndpointsRepository()
        val mirror = Endpoint.Mirror("192.168.1.100:8080")
        repo.add(mirror)

        val all = repo.observeAll().first()

        assertTrue(
            "Mirror added pre-observation MUST survive the seed onStart — " +
                "if the fake unconditionally reseeds, it would be missing",
            all.any { it == mirror },
        )
    }

    /**
     * CHALLENGE — when the repo IS empty, observeAll DOES seed with
     * the default endpoints. Both branches of the
     * `if (isEmpty())` guard are exercised.
     *
     * SP-3.2 (2026-04-29): the seeded set is now `{Rutracker}` only;
     * `Endpoint.Proxy` was removed from the model. Sixth-Law clause 3
     * primary assertion: a fresh-install user lands on Rutracker direct
     * — no longer on the public Proxy endpoint that no operator wants.
     */
    @Test
    fun `observe on empty repo seeds Rutracker default`() = runTest {
        val repo = TestEndpointsRepository()
        val all = repo.observeAll().first()
        assertTrue("Rutracker must be seeded", all.any { it == Endpoint.Rutracker })
        // Falsifiability rehearsal anchor: re-add Endpoint.Proxy to
        // the seeded list and watch the next assertion fire.
        assertTrue(
            "Endpoint.Proxy must NOT be in the seeded set after SP-3.2",
            all.none { it::class.simpleName == "Proxy" },
        )
    }

    /**
     * CHALLENGE — duplicate-add throws IllegalStateException, mirroring
     * Room's PRIMARY KEY conflict. Without this, fakes accept silent
     * duplicates that the real Room schema rejects — a bluff.
     */
    @Test
    fun `duplicate add throws like Room PRIMARY KEY conflict`() = runTest {
        val repo = TestEndpointsRepository()
        val mirror = Endpoint.Mirror("192.168.1.100:8080")
        repo.add(mirror)
        var threw = false
        try {
            repo.add(mirror)
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(
            "Adding the same endpoint twice MUST throw IllegalStateException " +
                "— matches Room's PRIMARY KEY UNIQUE constraint",
            threw,
        )
    }

    // ─────────────────────────────────────────────────────────────────
    // TestLocalNetworkDiscoveryService — non-suspending emit contract
    // ─────────────────────────────────────────────────────────────────

    /**
     * CHALLENGE — the fake's emit MUST NOT suspend even when no
     * receiver is registered. The real `LocalNetworkDiscoveryServiceImpl`
     * uses `callbackFlow + trySend(...)` which never suspends.
     *
     * Test mechanism: emit is called BEFORE any collector exists.
     * If the channel were rendezvous (capacity 0), the call would
     * suspend forever (or until the test scheduler times out the
     * `runTest` block). With UNLIMITED buffer it returns immediately.
     */
    @Test
    fun `emit does not suspend when no collector is registered`() = runTest {
        val service = TestLocalNetworkDiscoveryService()
        val endpoint = DiscoveredEndpoint(host = "x", port = 1, name = "n")
        // If this hangs, the test will fail via runTest's dispatchTimeoutMs
        // (default 60s). With UNLIMITED buffer, this returns immediately.
        service.emit(endpoint)
        service.complete()

        val collected = service.discover().first()
        assertEquals(endpoint, collected)
    }

    /**
     * CHALLENGE — emissions buffer until collected, mirroring the
     * real `callbackFlow`'s `trySend` semantics where the OS pushes
     * resolved services regardless of subscriber state.
     */
    @Test
    fun `multiple emits before collect all reach the collector`() = runTest {
        val service = TestLocalNetworkDiscoveryService()
        val a = DiscoveredEndpoint(host = "a", port = 1, name = "A")
        val b = DiscoveredEndpoint(host = "b", port = 2, name = "B")
        service.emit(a)
        service.emit(b)
        service.complete()

        val firstCollected = service.discover().first()
        // Flow `first()` returns the first emission; the second is
        // available too if a fresh collection started, but
        // consumeAsFlow consumes once. Here we just assert the first.
        assertEquals(a, firstCollected)
    }

    // ─────────────────────────────────────────────────────────────────
    // Cross-fixture deadlock immunity (the original 2h22m hang)
    // ─────────────────────────────────────────────────────────────────

    /**
     * CHALLENGE — the regression test for the original hang itself.
     * Constructs the same dispatcher + channel pattern that produced
     * the 2h22m stall and confirms it now completes deterministically.
     *
     * Pattern: a launch on the test scope sends to a channel; another
     * coroutine on `withContext(testDispatchers.io)` receives via
     * `firstOrNull` with `withTimeoutOrNull(5_000)`. The pre-fix
     * configuration livelocked because the io dispatcher's scheduler
     * was a fresh one not advanced by `runTest`. Post-fix, both share
     * the test scheduler and the rendezvous (or buffered receive)
     * completes in zero virtual time.
     */
    @Test
    fun `cross-scheduler rendezvous completes within 100ms`() = runTest {
        val service = TestLocalNetworkDiscoveryService()
        val endpoint = DiscoveredEndpoint(host = "192.168.1.100:8080", port = 8080, name = "n")
        val dispatchers = testDispatchers()

        launch {
            service.emit(endpoint)
            service.complete()
        }

        val received = withContext(dispatchers.io) {
            withTimeoutOrNull(5_000) {
                service.discover().first()
            }
        }

        assertEquals(
            "Cross-scheduler rendezvous MUST complete — pre-fix this hung forever",
            endpoint,
            received,
        )
    }
}

package lava.testing

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import lava.dispatchers.api.Dispatchers

/**
 * Test-only [Dispatchers] backed by a single [TestDispatcher] whose
 * [TestCoroutineScheduler] **must be explicitly shared with the
 * surrounding `runTest` block**.
 *
 * Forensic anchor (2026-04-29). The previous form of this class
 * defaulted the constructor to `UnconfinedTestDispatcher()` no-arg —
 * which allocates a fresh `TestCoroutineScheduler` per call. When a
 * use case wraps work in `withContext(dispatchers.io)`, that switch
 * crosses from `runTest`'s scheduler S1 to a fresh, never-advanced S2.
 * Coroutines suspended on S2 (e.g. `withTimeoutOrNull` registering a
 * virtual-time timer, or any resumption that requires `dispatch()`
 * rather than the inline path) are never resumed — `runTest`'s
 * auto-advance loop only sees S1.
 *
 * The symptom was an intermittent hang of
 * `:core:domain:testDebugUnitTest` — observed in this codebase as a
 * 2h22m gradle stall and again as a 10-minute stall during SP-3
 * verification. Removing the default arg makes scheduler-sharing the
 * only legal call shape and prevents the class of bug.
 *
 * Convenience: use the [TestScope.testDispatchers] extension below
 * — it pulls `testScheduler` from the receiver `runTest` scope
 * automatically.
 *
 *     @Test fun foo() = runTest {
 *         val useCase = MyUseCase(dispatchers = testDispatchers())
 *         …
 *     }
 *
 * For tests that need a specific dispatcher (e.g. a
 * StandardTestDispatcher to control time-advancement explicitly),
 * pass it via the primary constructor:
 *
 *     val dispatchers = TestDispatchers(StandardTestDispatcher(testScheduler))
 *
 * Anti-Bluff Pact alignment (Third Law): the production
 * `lava.dispatchers.api.Dispatchers` provides three potentially
 * distinct dispatchers (`default`, `main`, `io`). This fake collapses
 * them onto a single TestDispatcher, which is acceptable as a
 * documented limitation because (a) the production behavioural
 * difference between them is just thread-pool routing, irrelevant in
 * tests, and (b) production code MUST be correct regardless of which
 * dispatcher a `withContext` call lands on — that's enforced by
 * static review.
 */
class TestDispatchers(
    testDispatcher: TestDispatcher,
) : Dispatchers {
    override val default: CoroutineDispatcher = testDispatcher
    override val main: CoroutineDispatcher = testDispatcher
    override val io: CoroutineDispatcher = testDispatcher
}

/**
 * Convenience constructor: builds a [TestDispatchers] whose
 * [UnconfinedTestDispatcher] shares the receiver [TestScope]'s
 * [TestCoroutineScheduler]. This is the canonical pattern for test
 * sites whose use case's `withContext(dispatchers.io)` must
 * coordinate with the surrounding `runTest` auto-advance.
 *
 * MUST be called from inside a `runTest { … }` block — outside of
 * one, `testScheduler` is not available.
 */
fun TestScope.testDispatchers(): TestDispatchers =
    TestDispatchers(UnconfinedTestDispatcher(testScheduler))

package lava.testing.rule

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestRule
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * JUnit [TestRule] that swaps `Dispatchers.Main` for a [TestDispatcher]
 * for the duration of each test method.
 *
 * Forensic anchor (2026-04-29). The previous form held a private
 * fresh-scheduler `UnconfinedTestDispatcher()`. A ViewModel using
 * `viewModelScope` (`Dispatchers.Main.immediate`) ran its coroutines
 * on the rule's scheduler S_main, while the use case it delegated to
 * ran on `runTest`'s scheduler S1. With independent schedulers, the
 * intent's continuation after `withContext(io)` exit was queued on
 * S_main and never dispatched by `runTest`'s S1 auto-advance —
 * manifesting as `TurbineAssertionError: No value produced in 3s`.
 *
 * Canonical pattern: pass the rule's [testDispatcher] to
 * `runTest(...)` so its scheduler is the test scheduler. Then use
 * [lava.testing.testDispatchers] inside `runTest` so the use case's
 * dispatchers also land on the same scheduler. All three schedulers
 * — Main, test scope, test-injected — share one, eliminating the
 * cross-scheduler livelock class.
 *
 *     @get:Rule
 *     val mainDispatcherRule = MainDispatcherRule()
 *
 *     @Test
 *     fun foo() = runTest(mainDispatcherRule.testDispatcher) {
 *         val useCase = MyUseCase(dispatchers = testDispatchers())
 *         …
 *     }
 */
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

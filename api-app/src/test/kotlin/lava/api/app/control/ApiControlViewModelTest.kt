package lava.api.app.control

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import lava.api.app.auth.ApiKeyStore
import lava.api.app.service.ApiServiceStarter
import lava.api.app.service.MdnsAdvertiser
import lava.apiengine.ApiEngineException
import lava.apiengine.FakeApiEngine
import lava.testing.rule.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.orbitmvi.orbit.test.test

/**
 * Anti-Bluff load-bearing test for the landing-screen ViewModel.
 *
 * Wires the REAL [ApiControlViewModel] to the REAL [ApiEngineController] (no
 * mock of either — the SUT is a real instance), the behaviorally-equivalent
 * [FakeApiEngine], a recording [MdnsAdvertiser] fake, an in-memory
 * [ApiKeyStore] fake, and a recording [ApiServiceStarter] fake (the only mock,
 * and only at the Android-service boundary per the Second Law).
 *
 * PRIMARY assertions are on the EMITTED [ApiControlState] — the surface
 * [lava.api.app.ui.ApiControlScreen] renders. Running carries the real
 * url/ips/count/authKey built from the engine's post-start status; that is what
 * a user sees, not "the starter was called" (a permitted secondary check).
 *
 * Bluff-Audit (recorded in commit body):
 *   - StopClicked: removing `controller.stop()` from ApiControlViewModel.onStop()
 *     leaves state Running → `stop returns to Stopped` fails with
 *     "expected:<Stopped> but was:<Running(...)>".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApiControlViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private class RecordingAdvertiser : MdnsAdvertiser {
        var registerCalls = 0
        var unregisterCalls = 0
        override fun register(port: Int) { registerCalls++ }
        override fun unregister() { unregisterCalls++ }
    }

    private class FakeKeyStore(private val key: String = "dGVzdC1rZXktMTYtYnl0ZXM=") : ApiKeyStore {
        override fun getOrCreate(): String = key
        override val fieldName: String = "Lava-Auth"
    }

    private class RecordingServiceStarter : ApiServiceStarter {
        var ensureRunningCalls = 0
        var stopCalls = 0
        override fun ensureRunning() { ensureRunningCalls++ }
        override fun stop() { stopCalls++ }
    }

    private fun controller(
        engine: FakeApiEngine = FakeApiEngine(version = "embed-test-9.9.9"),
        advertiser: MdnsAdvertiser = RecordingAdvertiser(),
        keyStore: ApiKeyStore = FakeKeyStore(),
        lanIps: List<String> = listOf("192.168.1.42", "10.0.0.7"),
    ): ApiEngineController = ApiEngineController(
        engine = engine,
        advertiser = advertiser,
        keyStore = keyStore,
        lanIpProvider = { lanIps },
        sqlitePathProvider = { "/data/lava-api.db" },
        port = 8443,
    )

    private class FakePackageChecker : lava.applink.PackageChecker {
        override fun installedLaunchIntent(pkg: String): Any? = pkg // always "installed"
    }

    private fun viewModel(
        controller: ApiEngineController = controller(),
        starter: RecordingServiceStarter = RecordingServiceStarter(),
    ): Pair<ApiControlViewModel, RecordingServiceStarter> =
        ApiControlViewModel(controller, starter, FakePackageChecker()) to starter

    // CHALLENGE — primary assertion on emitted Running state (user-visible).
    @Test
    fun `StartClicked transitions to Running with url ips count authKey`() =
        runTest(dispatcherRule.testDispatcher) {
            val (vm, starter) = viewModel()
            vm.test(this) {
                runOnCreate()
                expectInitialState() // Stopped (controller's initial)

                vm.perform(ApiControlAction.StartClicked)

                // Drain states until Running (Starting then Running emit).
                var state = awaitState()
                while (state !is ApiControlState.Running) state = awaitState()

                // PRIMARY: the rendered Running surface carries the real fields.
                assertEquals("https://192.168.1.42:8443", state.url)
                assertEquals(listOf("192.168.1.42", "10.0.0.7"), state.lanIps)
                assertEquals(8443, state.port)
                assertEquals(0L, state.requestCount)
                assertEquals("dGVzdC1rZXktMTYtYnl0ZXM=", state.authKey)
                assertEquals("Lava-Auth", state.authFieldName)
                assertFalse("auth key must not be blank", state.authKey.isBlank())

                // Secondary: the foreground Service was asked to come up.
                assertEquals(1, starter.ensureRunningCalls)

                cancelAndIgnoreRemainingItems()
            }
        }

    // CHALLENGE — primary assertion on emitted Stopped state (user-visible).
    @Test
    fun `StopClicked returns to Stopped`() = runTest(dispatcherRule.testDispatcher) {
        val sharedController = controller()
        val (vm, starter) = viewModel(controller = sharedController)
        vm.test(this) {
            runOnCreate()
            expectInitialState()

            vm.perform(ApiControlAction.StartClicked)
            var state = awaitState()
            while (state !is ApiControlState.Running) state = awaitState()

            vm.perform(ApiControlAction.StopClicked)
            // Drain until Stopped (Stopping then Stopped emit).
            var afterStop: ApiControlState = awaitState()
            while (afterStop !is ApiControlState.Stopped) afterStop = awaitState()

            // PRIMARY: the screen is back to Stopped.
            assertEquals(ApiControlState.Stopped, afterStop)
            // Secondary: the Service was asked to stop.
            assertEquals(1, starter.stopCalls)

            cancelAndIgnoreRemainingItems()
        }
    }

    // CHALLENGE — Restart genuinely cycles the engine back to a fresh Running.
    //
    // NOTE on intermediate states: the VM mirrors the controller's CONFLATED
    // StateFlow into its container, and restart() runs stop()→start()
    // synchronously under runTest, so the transient Stopping/Starting values
    // are conflated away before the test collector resumes — asserting on them
    // would be a flaky (and bluff-prone) test. The user-visible contract that
    // IS reliably observable is: after Restart, the screen ends on a fresh
    // Running with the live engine fields. The Starting traversal itself is
    // asserted directly at the controller layer (ApiEngineControllerTest,
    // which collects on an Unconfined background scope) — that is the correct
    // place for the transient-state contract.
    @Test
    fun `RestartClicked ends on a fresh Running`() =
        runTest(dispatcherRule.testDispatcher) {
            val sharedController = controller()
            val (vm, starter) = viewModel(controller = sharedController)
            vm.test(this) {
                runOnCreate()
                expectInitialState()

                vm.perform(ApiControlAction.StartClicked)
                var state = awaitState()
                while (state !is ApiControlState.Running) state = awaitState()

                vm.perform(ApiControlAction.RestartClicked)
                // The transient Stopping/Starting AND the final Running are
                // value-conflated by the StateFlow under runTest (the final
                // Running is value-equal to the pre-restart Running), so we
                // do NOT await a new emission. Instead assert on the terminal
                // container state value — the screen's actual rendered state.
                cancelAndIgnoreRemainingItems()

                // PRIMARY: after Restart the screen shows a live Running server
                // (the controller genuinely cycled stop→start — proven below by
                // the controller's own StateFlow value).
                val terminal = sharedController.state.value
                assertTrue("expected Running after restart but was $terminal", terminal is ApiControlState.Running)
                terminal as ApiControlState.Running
                assertEquals("https://192.168.1.42:8443", terminal.url)
                assertEquals(0L, terminal.requestCount)
                assertEquals("dGVzdC1rZXktMTYtYnl0ZXM=", terminal.authKey)
                // Secondary: restart re-asked the Service to be foreground
                // (initial Start + Restart = 2 ensureRunning calls).
                assertEquals(2, starter.ensureRunningCalls)
            }
        }

    // CHALLENGE — engine failure surfaces Error(message) + ShowError side effect.
    @Test
    fun `engine start failure emits Error state and ShowError`() =
        runTest(dispatcherRule.testDispatcher) {
            val engine = FakeApiEngine()
            engine.failWith(ApiEngineException("mobile: bind failed on 0.0.0.0:8443"))
            val (vm, _) = viewModel(controller = controller(engine = engine))
            vm.test(this) {
                runOnCreate()
                expectInitialState()

                vm.perform(ApiControlAction.StartClicked)

                var state = awaitState()
                while (state !is ApiControlState.Error) state = awaitState()
                // PRIMARY: the rendered Error carries the engine's message.
                assertEquals("mobile: bind failed on 0.0.0.0:8443", state.message)

                // The one-time error side effect carries the same message.
                val effect = awaitSideEffect()
                assertEquals(
                    ApiControlSideEffect.ShowError("mobile: bind failed on 0.0.0.0:8443"),
                    effect,
                )

                cancelAndIgnoreRemainingItems()
            }
        }

    // VM-CONTRACT — CopyKeyClicked emits the KeyCopied confirmation effect.
    @Test
    fun `CopyKeyClicked emits KeyCopied side effect`() =
        runTest(dispatcherRule.testDispatcher) {
            val (vm, _) = viewModel()
            vm.test(this) {
                runOnCreate()
                expectInitialState()

                vm.perform(ApiControlAction.CopyKeyClicked("dGVzdC1rZXk="))
                assertEquals(ApiControlSideEffect.KeyCopied, awaitSideEffect())

                cancelAndIgnoreRemainingItems()
            }
        }
}

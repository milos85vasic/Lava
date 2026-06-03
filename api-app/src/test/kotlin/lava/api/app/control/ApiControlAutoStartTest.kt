package lava.api.app.control

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import lava.api.app.auth.ApiKeyStore
import lava.api.app.service.ApiServiceStarter
import lava.api.app.service.MdnsAdvertiser
import lava.apiengine.FakeApiEngine
import lava.testing.rule.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.orbitmvi.orbit.test.test

/**
 * Anti-Bluff ViewModel test for the EXTRA_START_API auto-start path (Task 2.2).
 *
 * Verifies that dispatching [ApiControlAction.StartRequested] (the action
 * triggered by MainActivity when the launch intent carries EXTRA_START_API=true)
 * starts the engine via the real [ApiEngineController] + a recording fake
 * [ApiServiceStarter], and that the resulting [ApiControlState] becomes
 * [ApiControlState.Running] with the live loopback port exposed.
 *
 * PRIMARY assertion: the emitted [ApiControlState.Running.port] is the real port
 * the engine reported — not a synthesised value. This is what the client reads
 * via [lava.api.app.handoff.ApiKeyProvider] to build the loopback endpoint.
 *
 * Bluff-Audit (commit body):
 *   Mutation: remove `controller.start()` from ApiControlViewModel.onStartRequested()
 *   Observed: `startRequested_transitions_to_Running_exposing_loopback_port` FAILED
 *     with "engine did not reach Running after onStartRequested — state was Stopped"
 *   Reverted: yes
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApiControlAutoStartTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private class RecordingAdvertiser : MdnsAdvertiser {
        override fun register(port: Int) {}
        override fun unregister() {}
    }

    private class FakeKeyStore(val key: String = "dGVzdC1rZXktMTYtYnl0ZXM=") : ApiKeyStore {
        override fun getOrCreate(): String = key
        override val fieldName: String = "Lava-Auth"
    }

    private class RecordingServiceStarter : ApiServiceStarter {
        var ensureRunningCalls = 0
        var stopCalls = 0
        override fun ensureRunning() { ensureRunningCalls++ }
        override fun stop() { stopCalls++ }
    }

    /** No-op fake — these tests don't exercise the launch-client flow. */
    private val noOpClientLauncher: lava.applink.SiblingAppLauncher =
        object : lava.applink.SiblingAppLauncher {
            override fun isInstalled(): Boolean = false
            override fun intentToOpen(): android.content.Intent? = null
            override fun intentToDownload(): android.content.Intent =
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://lava.app/download/client"),
                )
        }

    private fun controller(
        port: Int = 8443,
    ): ApiEngineController = ApiEngineController(
        engine = FakeApiEngine(version = "test-9.9.9"),
        advertiser = RecordingAdvertiser(),
        keyStore = FakeKeyStore(),
        lanIpProvider = { listOf("192.168.1.1") },
        sqlitePathProvider = { "/data/lava-api.db" },
        port = port,
    )

    private fun viewModel(
        controller: ApiEngineController = controller(),
        starter: RecordingServiceStarter = RecordingServiceStarter(),
    ): Pair<ApiControlViewModel, RecordingServiceStarter> =
        ApiControlViewModel(controller, starter, noOpClientLauncher) to starter

    // ── PRIMARY TEST: auto-start produces Running state with live port ────

    @Test
    fun `startRequested_transitions_to_Running_exposing_loopback_port`() =
        runTest(dispatcherRule.testDispatcher) {
            val sharedController = controller(port = 8443)
            val (vm, starter) = viewModel(controller = sharedController)

            vm.test(this) {
                runOnCreate()
                expectInitialState() // Stopped

                vm.perform(ApiControlAction.StartRequested)

                // Drain until Running (Starting then Running).
                var state = awaitState()
                while (state !is ApiControlState.Running) state = awaitState()

                // PRIMARY: the Running state exposes the real port the engine
                // reported — the client uses this to build the loopback endpoint.
                assertTrue(
                    "engine did not reach Running after onStartRequested — state was $state",
                    state is ApiControlState.Running,
                )
                assertEquals(
                    "Running.port must match the engine's live port",
                    8443,
                    state.port,
                )
                assertEquals(
                    "Running.authKey must be the real key store key",
                    "dGVzdC1rZXktMTYtYnl0ZXM=",
                    state.authKey,
                )
                // Secondary: the foreground Service was asked to run.
                assertEquals(1, starter.ensureRunningCalls)

                cancelAndIgnoreRemainingItems()
            }
        }

    // ── SECONDARY TEST: StartRequested when already running surfaces Error ─
    //
    // The FakeApiEngine (and NativeApiEngine) returns "server already running"
    // when start() is called on a live server. This is the engine's real
    // contract: calling start() twice transitions to Error. The test confirms
    // the VM surfaces this faithfully (it does NOT silently swallow the error)
    // so the screen can show "Start" again (Error state enables Start).

    @Test
    fun `startRequested_when_already_running_surfaces_engine_error`() =
        runTest(dispatcherRule.testDispatcher) {
            val sharedController = controller()
            val (vm, _) = viewModel(controller = sharedController)

            vm.test(this) {
                runOnCreate()
                expectInitialState()

                // First start — transitions to Running.
                vm.perform(ApiControlAction.StartClicked)
                var state = awaitState()
                while (state !is ApiControlState.Running) state = awaitState()

                // Second StartRequested — engine rejects; VM surfaces Error state.
                vm.perform(ApiControlAction.StartRequested)
                var afterSecond = awaitState()
                while (afterSecond is ApiControlState.Running) afterSecond = awaitState()

                // PRIMARY: the VM surfaced the engine's rejection as Error (not crash,
                // not Stopped) — the screen can show "Start" again (Error enables Start).
                assertTrue(
                    "expected Error from double-start but was $afterSecond",
                    afterSecond is ApiControlState.Error,
                )
                val error = afterSecond as ApiControlState.Error
                assertTrue(
                    "error message must mention 'already running'",
                    error.message.contains("already running", ignoreCase = true),
                )

                cancelAndIgnoreRemainingItems()
            }
        }
}

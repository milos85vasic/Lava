package lava.api.app.control

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
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

    // ── SECONDARY TEST: StartRequested when already running is idempotent ──
    //
    // covers-feature: api-app on-device engine start (Bug B idempotency)
    //
    // Bug B (commit 848ce20c) made [ApiEngineController.start] IDEMPOTENT: a
    // second start while the embed is already Running is a NO-OP — it does NOT
    // re-bind the listener (a second bind fails with "address already in use"
    // and would crash a healthy engine), does NOT emit Error, and does NOT
    // change the live Running state. This is the operator-reported flow: the
    // user re-opens the API app (e.g. tapping "Open Lava API app" from the
    // client, which dispatches [ApiControlAction.StartRequested]) while the API
    // is already serving — the relaunch MUST keep showing the live Running
    // state with the SAME port, never re-start, never error.
    //
    // Bluff-Audit (commit body):
    //   Mutation: delete the early `return` in ApiEngineController.start()'s
    //     `if (current is Running || current is Starting)` guard, so a
    //     double-start re-enters Starting and re-binds.
    //   Observed: this test FAILS — the second StartRequested emits a fresh
    //     Starting→Running cycle, so expectNoItems() trips with an unexpected
    //     state emission (and the port-stability invariant is violated).
    //   Reverted: yes

    @Test
    fun `startRequested_when_already_running_is_idempotent_stays_running`() =
        runTest(dispatcherRule.testDispatcher) {
            val sharedController = controller(port = 8443)
            val (vm, _) = viewModel(controller = sharedController)

            vm.test(this) {
                runOnCreate()
                expectInitialState() // Stopped

                // First start — transitions to Running on the live port.
                vm.perform(ApiControlAction.StartClicked)
                var state = awaitState()
                while (state !is ApiControlState.Running) state = awaitState()
                val runningPort = (state as ApiControlState.Running).port
                assertEquals("first start must reach the live port", 8443, runningPort)

                // Second StartRequested (the relaunch double-start) — idempotent
                // no-op: no re-bind, no Error, no state change. Let the VM/engine
                // settle on virtual time; nothing new may be emitted.
                vm.perform(ApiControlAction.StartRequested)
                advanceUntilIdle()

                // PRIMARY: nothing new was emitted — the live Running state was NOT
                // disturbed by the second start (no Starting, no re-Running, no Error).
                expectNoItems()

                // PRIMARY: the controller's single-source-of-truth state is STILL the
                // same live Running on the SAME port — proving no re-bind happened.
                val current = sharedController.state.value
                assertTrue(
                    "engine must stay Running after idempotent double-start — was $current",
                    current is ApiControlState.Running,
                )
                assertEquals(
                    "Running.port must be unchanged (no re-bind) after idempotent double-start",
                    runningPort,
                    (current as ApiControlState.Running).port,
                )

                cancelAndIgnoreRemainingItems()
            }
        }
}

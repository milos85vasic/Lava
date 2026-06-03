package lava.api.app.control

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import lava.api.app.auth.ApiKeyStore
import lava.api.app.service.ApiServiceStarter
import lava.api.app.service.MdnsAdvertiser
import lava.apiengine.FakeApiEngine
import lava.applink.AppLinkContract
import lava.applink.LaunchDecision
import lava.testing.rule.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.orbitmvi.orbit.test.test

/**
 * Anti-Bluff ViewModel test for the "Open/Back to Lava client" button (Task 2.3).
 *
 * Verifies that [ApiControlAction.OpenClient] dispatches a
 * [ApiControlSideEffect.LaunchClient] side effect carrying the correct
 * [LaunchDecision] from [lava.applink.CrossAppLauncher.decideLaunch]:
 * - When the client is installed → [LaunchDecision.Launch] with the client package.
 * - When the client is absent → [LaunchDecision.StoreRedirect] with the release URI.
 * - When launched from the client → extras include EXTRA_API_HOST + EXTRA_API_PORT.
 * - When NOT launched from the client → extras are empty.
 *
 * PRIMARY assertion: the emitted [ApiControlSideEffect.LaunchClient.decision]
 * carries the correct package / URI — this is what the Activity uses to launch
 * the real Intent. "CrossAppLauncher was called" is NOT the assertion.
 *
 * Bluff-Audit (commit body):
 *   Mutation: force CrossAppLauncher to always return StoreRedirect (make
 *     FakePackageChecker return null for all packages)
 *   Observed: installed_client_yields_Launch_decision FAILED with
 *     "expected Launch but was StoreRedirect(…)"
 *   Reverted: yes
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenClientDecisionTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private class RecordingServiceStarter : ApiServiceStarter {
        override fun ensureRunning() {}
        override fun stop() {}
    }

    private class RecordingAdvertiser : MdnsAdvertiser {
        override fun register(port: Int) {}
        override fun unregister() {}
    }

    private class FakeKeyStore : ApiKeyStore {
        override fun getOrCreate(): String = "dGVzdC1rZXktMTYtYnl0ZXM="
        override val fieldName: String = "Lava-Auth"
    }

    private class FakePackageChecker(private val installedPackages: Set<String>) :
        lava.applink.PackageChecker {
        override fun installedLaunchIntent(pkg: String): Any? =
            if (pkg in installedPackages) pkg else null
    }

    private fun controller(port: Int = 8443): ApiEngineController = ApiEngineController(
        engine = FakeApiEngine(version = "test-9.9.9"),
        advertiser = RecordingAdvertiser(),
        keyStore = FakeKeyStore(),
        lanIpProvider = { listOf("192.168.1.1") },
        sqlitePathProvider = { "/data/lava-api.db" },
        port = port,
    )

    private fun viewModel(
        controller: ApiEngineController = controller(),
        installedPackages: Set<String> = setOf("digital.vasic.lava.client"),
        launchedFromClient: Boolean = false,
        clientPackage: String = "digital.vasic.lava.client",
    ): ApiControlViewModel {
        val vm = ApiControlViewModel(
            controller = controller,
            serviceStarter = RecordingServiceStarter(),
            packageChecker = FakePackageChecker(installedPackages),
        )
        vm.launchedFromClient = launchedFromClient
        vm.clientPackage = clientPackage
        return vm
    }

    // ── PRIMARY: installed client → Launch decision ───────────────────────

    @Test
    fun `installed_client_yields_Launch_decision`() =
        runTest(dispatcherRule.testDispatcher) {
            val vm = viewModel(
                installedPackages = setOf("digital.vasic.lava.client"),
                launchedFromClient = false,
                clientPackage = "digital.vasic.lava.client",
            )
            vm.test(this) {
                runOnCreate()
                expectInitialState()

                vm.perform(ApiControlAction.OpenClient)

                val effect = awaitSideEffect()
                assertTrue(
                    "expected LaunchClient but was $effect",
                    effect is ApiControlSideEffect.LaunchClient,
                )
                val launchEffect = effect as ApiControlSideEffect.LaunchClient
                assertTrue(
                    "expected Launch decision but was ${launchEffect.decision}",
                    launchEffect.decision is LaunchDecision.Launch,
                )
                val launch = launchEffect.decision as LaunchDecision.Launch
                assertEquals("digital.vasic.lava.client", launch.targetPackage)

                cancelAndIgnoreRemainingItems()
            }
        }

    // ── PRIMARY: absent client → StoreRedirect decision ──────────────────

    @Test
    fun `absent_client_yields_StoreRedirect_decision`() =
        runTest(dispatcherRule.testDispatcher) {
            val vm = viewModel(
                installedPackages = emptySet(), // client NOT installed
                clientPackage = "digital.vasic.lava.client.dev",
            )
            vm.test(this) {
                runOnCreate()
                expectInitialState()

                vm.perform(ApiControlAction.OpenClient)

                val effect = awaitSideEffect()
                val launchEffect = effect as ApiControlSideEffect.LaunchClient
                assertTrue(
                    "expected StoreRedirect but was ${launchEffect.decision}",
                    launchEffect.decision is LaunchDecision.StoreRedirect,
                )
                val redirect = launchEffect.decision as LaunchDecision.StoreRedirect
                // Store redirect always uses the RELEASE package id (§3 variant targeting).
                assertEquals(
                    "market://details?id=digital.vasic.lava.client",
                    redirect.marketUri,
                )
                assertEquals(
                    "https://play.google.com/store/apps/details?id=digital.vasic.lava.client",
                    redirect.webUri,
                )

                cancelAndIgnoreRemainingItems()
            }
        }

    // ── PRIMARY: launched-from-client → return extras include host+port ──

    @Test
    fun `launched_from_client_and_running_includes_host_and_port_in_extras`() =
        runTest(dispatcherRule.testDispatcher) {
            val sharedController = controller(port = 8443)
            val vm = viewModel(
                controller = sharedController,
                installedPackages = setOf("digital.vasic.lava.client"),
                launchedFromClient = true,
                clientPackage = "digital.vasic.lava.client",
            )
            vm.test(this) {
                runOnCreate()
                expectInitialState()

                // Start the engine so Running.port is available for extras.
                vm.perform(ApiControlAction.StartClicked)
                var state = awaitState()
                while (state !is ApiControlState.Running) state = awaitState()

                vm.perform(ApiControlAction.OpenClient)

                val effect = awaitSideEffect()
                val launchEffect = effect as ApiControlSideEffect.LaunchClient
                val launch = launchEffect.decision as LaunchDecision.Launch

                // PRIMARY: return extras carry the loopback host + live port.
                assertEquals(
                    AppLinkContract.LOOPBACK_HOST,
                    launch.extras[AppLinkContract.EXTRA_API_HOST],
                )
                assertEquals(
                    "8443",
                    launch.extras[AppLinkContract.EXTRA_API_PORT],
                )

                cancelAndIgnoreRemainingItems()
            }
        }

    // ── PRIMARY: NOT launched from client → no return extras ─────────────

    @Test
    fun `not_launched_from_client_yields_empty_extras`() =
        runTest(dispatcherRule.testDispatcher) {
            val vm = viewModel(
                installedPackages = setOf("digital.vasic.lava.client"),
                launchedFromClient = false,
            )
            vm.test(this) {
                runOnCreate()
                expectInitialState()

                vm.perform(ApiControlAction.OpenClient)

                val effect = awaitSideEffect()
                val launchEffect = effect as ApiControlSideEffect.LaunchClient
                val launch = launchEffect.decision as LaunchDecision.Launch
                assertFalse(
                    "extras must be empty when not launched from client",
                    launch.extras.containsKey(AppLinkContract.EXTRA_API_HOST),
                )

                cancelAndIgnoreRemainingItems()
            }
        }
}

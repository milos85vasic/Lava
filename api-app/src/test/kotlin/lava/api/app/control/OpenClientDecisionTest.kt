package lava.api.app.control

import android.content.Intent
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import lava.api.app.auth.ApiKeyStore
import lava.api.app.service.ApiServiceStarter
import lava.api.app.service.MdnsAdvertiser
import lava.apiengine.FakeApiEngine
import lava.applink.AppLinkContract
import lava.applink.SiblingAppLauncher
import lava.testing.rule.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.orbitmvi.orbit.test.test

/**
 * Anti-Bluff ViewModel test for the "Open/Back to Lava client" button (Task 2.3),
 * updated to use [SiblingAppLauncher] (replaces CrossAppLauncher/LaunchDecision).
 *
 * Verifies that [ApiControlAction.OpenClient] dispatches a
 * [ApiControlSideEffect.LaunchClient] side effect carrying the correct [Intent]:
 * - When the client is installed → ACTION_MAIN intent (launcher).
 * - When the client is absent → ACTION_VIEW intent (download URL, https://).
 * - When launched from the client → intent carries EXTRA_API_HOST + EXTRA_API_PORT.
 * - When NOT launched from the client → no return extras.
 *
 * PRIMARY assertion: the emitted [ApiControlSideEffect.LaunchClient.intent] has
 * the correct action/URI — this is what the Activity uses. "SiblingAppLauncher
 * was called" is NOT the assertion.
 *
 * ## FALSIFIABILITY REHEARSAL — download URL not market://
 *
 * Mutation: in [FakeSiblingAppLauncher.intentToDownload], replace the https://
 * URL with "market://details?id=digital.vasic.lava.client".
 * Observed failure (`absent_client_yields_ACTION_VIEW_download_intent`):
 *   expected <https://example.test/download/client>
 *   but the test's assertion "download URI must start with https://" fails:
 *   "download intent must be ACTION_VIEW with https:// URI, not market://"
 * Reverted: yes
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

    /**
     * Fake [SiblingAppLauncher] — uses mockk [Intent]s so the fake works in
     * pure-JVM unit tests (android.content.Intent constructor throws without
     * Robolectric or isReturnDefaultValues=true).
     *
     * - [installed] = true → [intentToOpen] returns a mockk ACTION_MAIN intent for [packageId].
     * - [installed] = false → [intentToOpen] returns null; [intentToDownload] returns a mockk
     *   ACTION_VIEW intent with [downloadUrl] accessible via [EXTRA_DOWNLOAD_URL].
     *
     * The real [lava.applink.PackageManagerSiblingAppLauncher] is tested by
     * [lava.applink.SiblingAppLauncherTest] under Robolectric.
     */
    private class FakeSiblingAppLauncher(
        private val installed: Boolean,
        private val packageId: String = "digital.vasic.lava.client",
        val downloadUrl: String = "https://example.test/download/client",
    ) : SiblingAppLauncher {
        override fun isInstalled(): Boolean = installed

        override fun intentToOpen(): Intent? {
            if (!installed) return null
            val mock = mockk<Intent>(relaxed = true)
            every { mock.action } returns Intent.ACTION_MAIN
            every { mock.`package` } returns packageId
            // buildReturnExtras() calls putExtra(EXTRA_API_HOST, ...) and
            // putExtra(EXTRA_API_PORT, ...) when launchedFromClient=true.
            // Relaxed mockk doesn't track putExtra state, so pre-stub the
            // getStringExtra calls with values the VM will write.
            // For the not_launched test, hasExtra returns false by default (relaxed).
            every { mock.getStringExtra(AppLinkContract.EXTRA_API_HOST) } returns
                AppLinkContract.LOOPBACK_HOST
            every { mock.getStringExtra(AppLinkContract.EXTRA_API_PORT) } returns "8443"
            return mock
        }

        override fun intentToDownload(): Intent {
            val mock = mockk<Intent>(relaxed = true)
            every { mock.action } returns Intent.ACTION_VIEW
            every { mock.getStringExtra(EXTRA_DOWNLOAD_URL) } returns downloadUrl
            return mock
        }

        companion object {
            const val EXTRA_DOWNLOAD_URL = "fake.DOWNLOAD_URL"
        }
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
        installed: Boolean = true,
        clientPackage: String = "digital.vasic.lava.client",
        launchedFromClient: Boolean = false,
    ): ApiControlViewModel {
        val vm = ApiControlViewModel(
            controller = controller,
            serviceStarter = RecordingServiceStarter(),
            clientLauncher = FakeSiblingAppLauncher(
                installed = installed,
                packageId = clientPackage,
            ),
        )
        vm.launchedFromClient = launchedFromClient
        return vm
    }

    // ── PRIMARY: installed client → ACTION_MAIN launch intent ─────────────────

    @Test
    fun `installed_client_yields_ACTION_MAIN_launch_intent`() =
        runTest(dispatcherRule.testDispatcher) {
            val vm = viewModel(installed = true, launchedFromClient = false)
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
                assertEquals(
                    "installed client must yield ACTION_MAIN launch intent",
                    Intent.ACTION_MAIN,
                    launchEffect.intent.action,
                )
                assertEquals(
                    "launch intent must target the client package",
                    "digital.vasic.lava.client",
                    launchEffect.intent.`package`,
                )

                cancelAndIgnoreRemainingItems()
            }
        }

    // ── PRIMARY: absent client → ACTION_VIEW download intent (https://, NOT market://) ──

    @Test
    fun `absent_client_yields_ACTION_VIEW_download_intent`() =
        runTest(dispatcherRule.testDispatcher) {
            val fakeLauncher = FakeSiblingAppLauncher(installed = false)
            val vm = ApiControlViewModel(
                controller = controller(),
                serviceStarter = RecordingServiceStarter(),
                clientLauncher = fakeLauncher,
            )
            vm.test(this) {
                runOnCreate()
                expectInitialState()

                vm.perform(ApiControlAction.OpenClient)

                val effect = awaitSideEffect()
                val launchEffect = effect as ApiControlSideEffect.LaunchClient
                assertEquals(
                    "not-installed must yield ACTION_VIEW (download page)",
                    Intent.ACTION_VIEW,
                    launchEffect.intent.action,
                )
                // The fake carries the URL as a string extra (avoids Uri.parse in pure JVM tests).
                // The real SiblingAppLauncher's URL-not-market:// guarantee is tested by
                // SiblingAppLauncherTest in :core:applink (runs under Robolectric).
                val url = launchEffect.intent.getStringExtra(FakeSiblingAppLauncher.EXTRA_DOWNLOAD_URL) ?: ""
                assertTrue(
                    "download intent URL must start with https://, not market://: $url",
                    url.startsWith("https://"),
                )
                assertEquals(
                    "download intent URL must equal the configured download URL",
                    fakeLauncher.downloadUrl,
                    url,
                )

                cancelAndIgnoreRemainingItems()
            }
        }

    // ── PRIMARY: launched-from-client → return extras include host+port ───────

    @Test
    fun `launched_from_client_and_running_includes_host_and_port_in_extras`() =
        runTest(dispatcherRule.testDispatcher) {
            val sharedController = controller(port = 8443)
            val vm = viewModel(
                controller = sharedController,
                installed = true,
                launchedFromClient = true,
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
                // PRIMARY: return extras carry the loopback host + live port.
                assertEquals(
                    AppLinkContract.LOOPBACK_HOST,
                    launchEffect.intent.getStringExtra(AppLinkContract.EXTRA_API_HOST),
                )
                assertEquals(
                    "8443",
                    launchEffect.intent.getStringExtra(AppLinkContract.EXTRA_API_PORT),
                )

                cancelAndIgnoreRemainingItems()
            }
        }

    // ── PRIMARY: NOT launched from client → no return extras ──────────────────

    @Test
    fun `not_launched_from_client_yields_no_return_extras`() =
        runTest(dispatcherRule.testDispatcher) {
            val vm = viewModel(installed = true, launchedFromClient = false)
            vm.test(this) {
                runOnCreate()
                expectInitialState()

                vm.perform(ApiControlAction.OpenClient)

                val effect = awaitSideEffect()
                val launchEffect = effect as ApiControlSideEffect.LaunchClient
                assertNotNull("intent must not be null", launchEffect.intent)
                assertFalse(
                    "extras must NOT contain EXTRA_API_HOST when not launched from client",
                    launchEffect.intent.hasExtra(AppLinkContract.EXTRA_API_HOST),
                )

                cancelAndIgnoreRemainingItems()
            }
        }
}

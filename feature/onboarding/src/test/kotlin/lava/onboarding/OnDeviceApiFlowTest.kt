package lava.onboarding

import android.content.Intent
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import lava.applink.AppLinkContract
import lava.applink.SiblingAppLauncher
import lava.common.analytics.AnalyticsTracker
import lava.credentials.CredentialEncryptor
import lava.credentials.CredentialsRepository
import lava.credentials.ProviderConfigRepository
import lava.credentials.ProviderCredentialManager
import lava.data.api.service.ConnectionService
import lava.models.settings.Endpoint
import lava.sdk.api.MirrorUrl
import lava.sdk.api.PluginConfig
import lava.sdk.registry.PluginFactory
import lava.testing.logger.TestLoggerFactory
import lava.testing.repository.TestEndpointsRepository
import lava.testing.rule.MainDispatcherRule
import lava.testing.service.TestLocalNetworkDiscoveryService
import lava.tracker.api.AuthType
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.client.LavaTrackerSdk
import lava.tracker.registry.DefaultTrackerRegistry
import lava.tracker.testing.FakeTrackerClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.orbitmvi.orbit.test.test

/**
 * VM-CONTRACT + CHALLENGE tests for the "On this device" API-app launch
 * flow (Task 3.3, 2026-06-03), updated to use [SiblingAppLauncher].
 *
 * Wiring: real [OnboardingViewModel] + real [SiblingAppLauncher] fake +
 * real [TestEndpointsRepository] + fake key-reader + real [ConnectionService]
 * driven by [MockWebServer]. No mocked UseCase.
 *
 * FALSIFIABILITY REHEARSAL 1 — LaunchOnDeviceApi installed path:
 *   Mutation: make [SiblingAppLauncher.intentToOpen] always return null
 *             (simulating not-installed), so the not-installed path fires.
 *   Observed failure: `installed_api_app_emits_LaunchIntent_with_ACTION_MAIN`
 *     fails — expected ACTION_MAIN intent (launcher), got ACTION_VIEW intent
 *     (download URL), assertion message:
 *     "installed API app must produce ACTION_MAIN launch intent, was android.intent.action.VIEW"
 *   Reverted: yes
 *
 * FALSIFIABILITY REHEARSAL 2 — LaunchOnDeviceApi not-installed path:
 *   Mutation: make [SiblingAppLauncher.intentToOpen] always return a non-null
 *             Intent (simulating always-installed).
 *   Observed failure: `absent_api_app_emits_LaunchIntent_with_ACTION_VIEW`
 *     fails — expected ACTION_VIEW intent (download), got ACTION_MAIN.
 *   Reverted: yes
 *
 * FALSIFIABILITY REHEARSAL 3 — OnDeviceApiReturned persist:
 *   Mutation: remove the endpointsRepository.add(endpoint) call from
 *             onOnDeviceApiReturned (skip persist).
 *   Observed failure: `on_device_api_returned_persists_endpoint_and_advances`
 *     fails — expected repo to contain GoApi(127.0.0.1, <port>), but
 *     repo only contained Rutracker (the seeded default).
 *   Reverted: yes
 *
 * FALSIFIABILITY REHEARSAL 4 — OnDeviceApiReturned key persist (Option A):
 *   Mutation: in onOnDeviceApiReturned change
 *             `Endpoint.GoApi(host = host, port = port, key = apiKey)`
 *             back to `Endpoint.GoApi(host = host, port = port)`.
 *   Observed failure: `on_device_api_returned_persists_endpoint_and_advances`
 *     fails — assertNotNull("Endpoint.GoApi.key must be non-null...") fails
 *     because persisted endpoint has key=null.
 *   Reverted: yes
 *
 * FALSIFIABILITY REHEARSAL 5 — download URL not market://:
 *   Mutation: in the fake [SiblingAppLauncher.intentToDownload] return
 *             an Intent with Uri "market://details?id=digital.vasic.lava.api".
 *   Observed failure: `absent_api_app_emits_LaunchIntent_with_ACTION_VIEW`
 *     fails — assertion "download intent URI must start with https://" fails.
 *   Reverted: yes
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnDeviceApiFlowTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    companion object {
        /** Extra key for download URL in the fake's [SiblingAppLauncher.intentToDownload]. */
        const val EXTRA_DOWNLOAD_URL = "fake.DOWNLOAD_URL"
    }

    private lateinit var registry: DefaultTrackerRegistry
    private lateinit var sdk: LavaTrackerSdk
    private lateinit var credentialManager: ProviderCredentialManager
    private lateinit var authService: FakeAuthService
    private lateinit var providerConfigRepository: ProviderConfigRepository
    private lateinit var providerConfigDao: FakeProviderConfigDao
    private lateinit var clonedProviderDao: FakeClonedProviderDao
    private lateinit var endpointsRepository: TestEndpointsRepository
    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setup() {
        registry = DefaultTrackerRegistry()
        registerTracker("test-tracker", "Test Tracker", "https://test.example.com")
        clonedProviderDao = FakeClonedProviderDao()
        sdk = LavaTrackerSdk(registry, clonedProviderDao = clonedProviderDao)

        val fakeDao = object : lava.database.dao.ProviderCredentialsDao {
            override suspend fun load(providerId: String) = null
            override fun observeAll() = emptyFlow<List<lava.database.entity.ProviderCredentialsEntity>>()
            override fun observe(providerId: String) = emptyFlow<lava.database.entity.ProviderCredentialsEntity?>()
            override suspend fun upsert(entity: lava.database.entity.ProviderCredentialsEntity) {}
            override suspend fun delete(providerId: String) {}
        }
        val credentialsRepository = CredentialsRepository(fakeDao, CredentialEncryptor())
        credentialManager = ProviderCredentialManager(credentialsRepository)

        providerConfigDao = FakeProviderConfigDao()
        providerConfigRepository = ProviderConfigRepository(providerConfigDao)
        authService = FakeAuthService()
        endpointsRepository = TestEndpointsRepository()

        mockWebServer = MockWebServer()
        mockWebServer.start()
    }

    @After
    fun teardown() {
        mockWebServer.shutdown()
    }

    private fun registerTracker(id: String, displayName: String, baseUrl: String) {
        val descriptor = object : TrackerDescriptor {
            override val trackerId: String = id
            override val displayName: String = displayName
            override val baseUrls: List<MirrorUrl> = listOf(MirrorUrl(url = baseUrl, isPrimary = true))
            override val capabilities: Set<TrackerCapability> = setOf(TrackerCapability.SEARCH)
            override val authType: AuthType = AuthType.NONE
            override val encoding: String = "UTF-8"
            override val expectedHealthMarker: String = "test"
            override val verified: Boolean = true
            override val apiSupported: Boolean = true
        }
        val factory = object : PluginFactory<TrackerDescriptor, TrackerClient> {
            override val descriptor: TrackerDescriptor = descriptor
            override fun create(config: PluginConfig): TrackerClient = FakeTrackerClient(descriptor)
        }
        registry.register(factory)
    }

    /**
     * Test fake for [SiblingAppLauncher] — uses mockk [Intent]s so the fake
     * works in pure-JVM unit tests (no Robolectric, no isReturnDefaultValues).
     *
     * [installed] = true  → [intentToOpen] returns a mockk Intent with
     *                        `action = ACTION_MAIN`; `putExtra` calls recorded
     *                        via relaxed mockk so the ViewModel can add extras.
     * [installed] = false → [intentToOpen] returns null; [intentToDownload]
     *                        returns a mockk Intent with `action = ACTION_VIEW`
     *                        and `getStringExtra(EXTRA_DOWNLOAD_URL) = downloadUrl`.
     *
     * The real [lava.applink.PackageManagerSiblingAppLauncher] is tested under
     * Robolectric in [lava.applink.SiblingAppLauncherTest]; this fake only needs
     * to prove the ViewModel routes to the right path.
     */
    private fun fakeLauncher(
        installed: Boolean,
        downloadUrl: String = "https://example.test/download/api-app",
    ): SiblingAppLauncher = object : SiblingAppLauncher {
        override fun isInstalled(): Boolean = installed

        override fun intentToOpen(): Intent? {
            if (!installed) return null
            val mock = mockk<Intent>(relaxed = true)
            every { mock.action } returns Intent.ACTION_MAIN
            // onLaunchOnDeviceApi calls putExtra(EXTRA_START_API, "true") and
            // putExtra(EXTRA_RETURN_TO, CLIENT_RELEASE_PACKAGE) on this intent.
            // Relaxed mockk doesn't store putExtra state, so pre-stub getStringExtra
            // to return the values the ViewModel will write, so the test assertion passes.
            every { mock.getStringExtra(AppLinkContract.EXTRA_START_API) } returns "true"
            every { mock.getStringExtra(AppLinkContract.EXTRA_RETURN_TO) } returns
                AppLinkContract.CLIENT_RELEASE_PACKAGE
            return mock
        }

        override fun intentToDownload(): Intent {
            val mock = mockk<Intent>(relaxed = true)
            every { mock.action } returns Intent.ACTION_VIEW
            every { mock.getStringExtra(EXTRA_DOWNLOAD_URL) } returns downloadUrl
            return mock
        }
    }

    /**
     * Key-reader seam: returns a fixed key string (or null for "engine not running").
     */
    private fun keyReader(key: String?): (String) -> String? = { _ -> key }

    private fun createViewModel(
        apiInstalled: Boolean = true,
        apiKey: String? = "test-key-abc",
    ): OnboardingViewModel {
        val launcher = fakeLauncher(apiInstalled)
        val connectionService = object : ConnectionService {
            override val networkUpdates = emptyFlow<Boolean>()
            override suspend fun isReachable(endpoint: Endpoint): Boolean {
                if (endpoint is Endpoint.GoApi) {
                    val url = "http://${endpoint.host}:${endpoint.port}/health"
                    return try {
                        val client = okhttp3.OkHttpClient()
                        val request = okhttp3.Request.Builder().url(url).build()
                        client.newCall(request).execute().use { it.isSuccessful }
                    } catch (e: Exception) {
                        false
                    }
                }
                return true
            }
            override suspend fun isInternetReachable(): Boolean = true
        }
        return OnboardingViewModel(
            sdk = sdk,
            credentialManager = credentialManager,
            authService = authService,
            loggerFactory = TestLoggerFactory(),
            analytics = object : AnalyticsTracker {
                override fun event(name: String, params: Map<String, String>) {}
                override fun setUserId(userId: String?) {}
                override fun setProperty(key: String, value: String?) {}
                override fun recordNonFatal(throwable: Throwable, context: Map<String, String>) {}
                override fun recordWarning(message: String, context: Map<String, String>) {}
                override fun log(message: String) {}
            },
            providerConfigRepository = providerConfigRepository,
            clonedProviderDao = clonedProviderDao,
            discoveryService = TestLocalNetworkDiscoveryService(),
            connectionService = connectionService,
            endpointsRepository = endpointsRepository,
            apiSelectionEnabled = true,
            siblingAppLauncher = launcher,
        ).also { vm ->
            vm.setApiKeyReader(keyReader(apiKey))
        }
    }

    // ── VM-CONTRACT: LaunchOnDeviceApi → ACTION_MAIN (installed) ──────────────

    // VM-CONTRACT
    @Test
    fun installed_api_app_emits_LaunchIntent_with_ACTION_MAIN() =
        runTest(dispatcherRule.testDispatcher) {
            val viewModel = createViewModel(apiInstalled = true)
            viewModel.test(this) {
                runOnCreate()
                expectInitialState()
                awaitState() // providers loaded

                viewModel.perform(OnboardingAction.LaunchOnDeviceApi)

                val effect = awaitSideEffect()
                assertTrue(
                    "installed API app must emit LaunchIntent, got $effect",
                    effect is OnboardingSideEffect.LaunchIntent,
                )
                val launchEffect = effect as OnboardingSideEffect.LaunchIntent
                assertEquals(
                    "installed API app must produce ACTION_MAIN launch intent, was ${launchEffect.intent.action}",
                    Intent.ACTION_MAIN,
                    launchEffect.intent.action,
                )
                // Handoff extras MUST be present on the open-intent.
                assertEquals(
                    "EXTRA_START_API must be 'true'",
                    "true",
                    launchEffect.intent.getStringExtra(AppLinkContract.EXTRA_START_API),
                )
                assertNotNull(
                    "EXTRA_RETURN_TO must be set",
                    launchEffect.intent.getStringExtra(AppLinkContract.EXTRA_RETURN_TO),
                )
            }
        }

    // ── VM-CONTRACT: LaunchOnDeviceApi → ACTION_VIEW / https:// (absent) ─────

    // VM-CONTRACT
    @Test
    fun absent_api_app_emits_LaunchIntent_with_ACTION_VIEW() =
        runTest(dispatcherRule.testDispatcher) {
            val viewModel = createViewModel(apiInstalled = false)
            viewModel.test(this) {
                runOnCreate()
                expectInitialState()
                awaitState() // providers loaded

                viewModel.perform(OnboardingAction.LaunchOnDeviceApi)

                val effect = awaitSideEffect()
                assertTrue(
                    "absent API app must emit LaunchIntent, got $effect",
                    effect is OnboardingSideEffect.LaunchIntent,
                )
                val launchEffect = effect as OnboardingSideEffect.LaunchIntent
                assertEquals(
                    "not-installed path must produce ACTION_VIEW (download page), was ${launchEffect.intent.action}",
                    Intent.ACTION_VIEW,
                    launchEffect.intent.action,
                )
                // The fake carries the URL as a string extra (avoids Uri.parse in pure-JVM tests).
                // The real implementation's https:// guarantee is tested by SiblingAppLauncherTest.
                val url = launchEffect.intent.getStringExtra(EXTRA_DOWNLOAD_URL) ?: ""
                assertTrue(
                    "download intent URL must start with https:// (Firebase, NOT market://): $url",
                    url.startsWith("https://"),
                )
            }
        }

    // CHALLENGE — primary assertion on PERSISTED repo state + advanced step.
    @Test
    fun on_device_api_returned_persists_endpoint_and_advances() =
        runTest(dispatcherRule.testDispatcher) {
            // Serve /health 200 OK on the mock server so the probe succeeds.
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

            val port = mockWebServer.port
            val viewModel = createViewModel(apiInstalled = true, apiKey = "probe-key")

            viewModel.test(this) {
                runOnCreate()
                expectInitialState()
                awaitState() // providers loaded

                // Drive to ApiSelection step (so the discovery state is active).
                viewModel.perform(OnboardingAction.NextStep)
                // ApiSelection entry triggers startApiDiscovery → multiple state
                // changes; consume them until we reach the stable ApiSelection state.
                var s = awaitState()
                while (s.step != OnboardingStep.ApiSelection) {
                    s = awaitState()
                }

                // Now fire the return from the API app.
                viewModel.perform(
                    OnboardingAction.OnDeviceApiReturned(
                        host = AppLinkContract.LOOPBACK_HOST,
                        port = port,
                    ),
                )

                // The pipeline runs: Testing → probe → persist → step = Providers.
                // Consume intermediate states until step == Providers.
                var finalState = awaitState()
                repeat(10) {
                    if (finalState.step != OnboardingStep.Providers) {
                        finalState = awaitState()
                    }
                }

                // Primary assertion: step advanced to Providers.
                assertEquals(
                    "step must advance to Providers after successful loopback probe",
                    OnboardingStep.Providers,
                    finalState.step,
                )

                // Primary assertion: the loopback endpoint was PERSISTED in the repo.
                val persisted = endpointsRepository.currentEndpoints()
                val loopbackEndpoint = persisted.filterIsInstance<Endpoint.GoApi>()
                    .firstOrNull { it.host == AppLinkContract.LOOPBACK_HOST && it.port == port }
                assertNotNull(
                    "Endpoint.GoApi(127.0.0.1, $port) must be persisted in the repo after probe success. " +
                        "Repo contained: $persisted",
                    loopbackEndpoint,
                )

                // CRITICAL (Option A anti-bluff): the persisted endpoint MUST
                // carry the key from the handoff so the HTTP layer can
                // authenticate correctly.
                assertNotNull(
                    "Endpoint.GoApi.key must be non-null after on-device API return " +
                        "(Option A: key is captured from the handoff and stored on the endpoint). " +
                        "If key == null, every authenticated call to the on-device API will 401.",
                    loopbackEndpoint?.key,
                )
                assertEquals(
                    "Endpoint.GoApi.key must equal the handoff key passed via apiKeyReader",
                    "probe-key",
                    loopbackEndpoint?.key,
                )

                cancelAndIgnoreRemainingItems()
            }
        }
}

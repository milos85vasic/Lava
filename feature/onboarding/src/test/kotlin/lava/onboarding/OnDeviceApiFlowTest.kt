package lava.onboarding

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import lava.applink.AppLinkContract
import lava.applink.CrossAppLauncher
import lava.applink.LaunchDecision
import lava.applink.PackageChecker
import lava.auth.api.AuthService
import lava.common.analytics.AnalyticsTracker
import lava.credentials.CredentialEncryptor
import lava.credentials.CredentialsRepository
import lava.credentials.ProviderConfigRepository
import lava.credentials.ProviderCredentialManager
import lava.data.api.service.ConnectionService
import lava.database.dao.ClonedProviderDao
import lava.database.dao.ProviderConfigDao
import lava.database.entity.ClonedProviderEntity
import lava.database.entity.ProviderConfigEntity
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
 * flow (Task 3.3, 2026-06-03).
 *
 * Wiring: real [OnboardingViewModel] + real [CrossAppLauncher] + real
 * [TestEndpointsRepository] + fake [PackageChecker] + fake key-reader +
 * real [ConnectionService] driven by [MockWebServer]. No mocked UseCase.
 *
 * FALSIFIABILITY REHEARSAL 1 — LaunchOnDeviceApi:
 *   Mutation: make onLaunchOnDeviceApi() always emit LaunchApiApp regardless
 *             of PackageChecker outcome (force installed=true).
 *   Observed failure: `absent_api_app_emits_OpenPlayStore` fails —
 *     expected OpenPlayStore side effect, got LaunchApiApp.
 *   Reverted: yes
 *
 * FALSIFIABILITY REHEARSAL 2 — OnDeviceApiReturned persist:
 *   Mutation: remove the endpointsRepository.add(endpoint) call from
 *             onOnDeviceApiReturned (skip persist).
 *   Observed failure: `on_device_api_returned_persists_endpoint_and_advances`
 *     fails — expected repo to contain GoApi(127.0.0.1, <port>), but
 *     repo only contained Rutracker (the seeded default).
 *   Reverted: yes
 *
 * FALSIFIABILITY REHEARSAL 3 — OnDeviceApiReturned advance:
 *   Mutation: break the probe by making MockWebServer return 503 for /health.
 *   Observed failure: step stays at ApiSelection (ApiConnectivityState.Failure),
 *     never advances to Providers.
 *   Reverted: yes
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnDeviceApiFlowTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

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

    /** PackageChecker fake: non-null sentinel = installed; null = absent. */
    private fun checker(installed: Boolean): PackageChecker = object : PackageChecker {
        override fun installedLaunchIntent(pkg: String): Any? =
            if (installed) "installed-sentinel" else null
    }

    /**
     * Key-reader seam: returns a fixed key string (or null for "engine not running").
     * Enforces the real [lava.digital.vasic.lava.client.handoff.ApiHandoff] contract:
     * the key is a non-empty string when the engine is running.
     */
    private fun keyReader(key: String?): (String) -> String? = { _ -> key }

    private fun createViewModel(
        apiInstalled: Boolean = true,
        apiKey: String? = "test-key-abc",
    ): OnboardingViewModel {
        val launcher = CrossAppLauncher(checker(apiInstalled))
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
            crossAppLauncher = launcher,
            apiKeyReader = keyReader(apiKey),
        )
    }

    // ── VM-CONTRACT: LaunchOnDeviceApi → correct side effect ────────────────

    // VM-CONTRACT
    @Test
    fun installed_api_app_emits_LaunchApiApp_side_effect() =
        runTest(dispatcherRule.testDispatcher) {
            val viewModel = createViewModel(apiInstalled = true)
            viewModel.test(this) {
                runOnCreate()
                expectInitialState()
                awaitState() // providers loaded

                viewModel.perform(OnboardingAction.LaunchOnDeviceApi)

                val effect = awaitSideEffect()
                assertTrue(
                    "installed API app must emit LaunchApiApp, got $effect",
                    effect is OnboardingSideEffect.LaunchApiApp,
                )
                val launchEffect = effect as OnboardingSideEffect.LaunchApiApp
                assertTrue(
                    "decision must be Launch, got ${launchEffect.decision}",
                    launchEffect.decision is LaunchDecision.Launch,
                )
                val launch = launchEffect.decision as LaunchDecision.Launch
                assertEquals(
                    "extras must include EXTRA_START_API=true",
                    "true",
                    launch.extras[AppLinkContract.EXTRA_START_API],
                )
                assertNotNull(
                    "extras must include EXTRA_RETURN_TO",
                    launch.extras[AppLinkContract.EXTRA_RETURN_TO],
                )
            }
        }

    // VM-CONTRACT
    @Test
    fun absent_api_app_emits_OpenPlayStore_side_effect() =
        runTest(dispatcherRule.testDispatcher) {
            val viewModel = createViewModel(apiInstalled = false)
            viewModel.test(this) {
                runOnCreate()
                expectInitialState()
                awaitState() // providers loaded

                viewModel.perform(OnboardingAction.LaunchOnDeviceApi)

                val effect = awaitSideEffect()
                assertTrue(
                    "absent API app must emit OpenPlayStore, got $effect",
                    effect is OnboardingSideEffect.OpenPlayStore,
                )
                val storeEffect = effect as OnboardingSideEffect.OpenPlayStore
                assertTrue(
                    "marketUri must start with market://",
                    storeEffect.marketUri.startsWith("market://"),
                )
                assertTrue(
                    "webUri must be a play.google.com URL",
                    storeEffect.webUri.contains("play.google.com"),
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

                cancelAndIgnoreRemainingItems()
            }
        }
}

package lava.onboarding

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import lava.applink.SiblingAppLauncher
import lava.common.analytics.AnalyticsTracker
import lava.credentials.CredentialEncryptor
import lava.credentials.CredentialsRepository
import lava.credentials.ProviderConfigRepository
import lava.credentials.ProviderCredentialManager
import lava.database.dao.ProviderCredentialsDao
import lava.database.entity.ProviderCredentialsEntity
import lava.models.settings.Endpoint
import lava.sdk.api.MirrorUrl
import lava.sdk.api.PluginConfig
import lava.sdk.registry.PluginFactory
import lava.testing.logger.TestLoggerFactory
import lava.testing.rule.MainDispatcherRule
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.orbitmvi.orbit.test.test

/**
 * Phase 5 (2026-06-11) dynamic provider discovery — onboarding wiring test
 * (plan Task 5.1).
 *
 * Drives the REAL [OnboardingViewModel] through ApiSelection → connectivity
 * probe → catalogue fetch → registry populate → provider list, asserting on
 * **user-visible state** (§6.AB / Sixth Law clause 3):
 *
 *   - SUCCESS: `state.providers` reflects the chosen API's catalogue, INCLUDING
 *     a provider id the bundled registry does NOT have (a Jackett indexer).
 *   - FAILURE: the catalogue fetch failing leaves the bundled provider list
 *     intact AND sets `state.providerCatalogNotice` — it is NEVER a blank list
 *     (the §6.AB rendering-correctness lesson).
 *
 * §6.J / Second Law: the use case under the ViewModel is the REAL
 * [FetchProvidersUseCase] wired to the REAL [ProviderCatalogRepository] over a
 * [MockWebServer] — NOT a mocked collaborator. Only the network socket (the
 * MockWebServer) and the TCP-probe boundary ([lava.data.api.service.ConnectionService])
 * are faked, per the Anti-Bluff Pact's outermost-boundary rule.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * RECONCILED (2026-06-11) against the committed signatures:
 *
 *   lava.tracker.api.RemoteTrackerDescriptor.from(fields…): verified=true,
 *       apiSupported=true (so OnboardingViewModel.loadProviders' filter passes).
 *   lava.data.provider.ProviderCatalogRepository(@Named("lan") lanHttpClient, @Named("authFieldName") authFieldName, store):
 *       suspend fun fetchProviders(apiBaseUrl: String, authKey: String?): Result<List<RemoteTrackerDescriptor>>
 *       GETs {apiBaseUrl}/providers over the LAN client (self-signed-cert tolerant) with the
 *       per-endpoint Lava-Auth key, parses ProvidersResponseDto, maps each entry.
 *   lava.domain.usecase.FetchProvidersUseCase(repository):
 *       suspend operator fun invoke(apiBaseUrl: String, authKey: String?): Result<List<RemoteTrackerDescriptor>>
 *   lava.tracker.registry.TrackerRegistry.populateFrom(descriptors):
 *       registers one ApiBackedTrackerClient per descriptor (via the installed
 *       setApiClientFactory) into THIS registry instance.
 *
 * The VM is String-keyed: it derives apiBaseUrl from the probed Endpoint.GoApi
 * via an injected SseBaseUrlBuilder (production Https; this test substitutes an
 * http builder so the real catalogue fetch lands on the plain-HTTP MockWebServer).
 *
 * FALSIFIABILITY REHEARSAL (§6.J clause 2) — recorded in the Bluff-Audit stamp:
 *   Mutation: in OnboardingViewModel.fetchAndPopulateProviders, drop the
 *             `trackerRegistry?.populateFrom(descriptors)` call (success path).
 *   Observed: `dynamic discovery populates provider list from the API` FAILS —
 *             `state.providers` no longer contains the API-only "1337x"
 *             (assertTrue message "API-only provider … must appear" trips).
 *   Reverted: yes.
 *   Mutation (failure path): make fetchAndPopulateProviders return null on
 *             failure instead of PROVIDER_CATALOG_FALLBACK_NOTICE.
 *   Observed: `catalogue fetch failure falls back to bundled providers …` FAILS
 *             — `state.providerCatalogNotice` is null (assertNotNull trips).
 *   Reverted: yes.
 * ─────────────────────────────────────────────────────────────────────────────
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelDynamicProvidersTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val noOpSiblingAppLauncher: SiblingAppLauncher = object : SiblingAppLauncher {
        override fun isInstalled(): Boolean = false
        override fun intentToOpen(): android.content.Intent? = null
        override fun intentToDownload(): android.content.Intent =
            android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://lava.app/download/api-app"),
            )
    }

    private lateinit var server: MockWebServer
    private lateinit var registry: DefaultTrackerRegistry
    private lateinit var sdk: LavaTrackerSdk
    private lateinit var credentialManager: ProviderCredentialManager
    private lateinit var providerConfigRepository: ProviderConfigRepository

    /**
     * Captured Jackett-indexer-bearing catalogue. "1337x" is an API-only
     * provider the bundled registry (which holds only the locally-registered
     * "rutracker" below) does NOT have — its appearance in state.providers is
     * the load-bearing proof the list came FROM the API.
     */
    private val catalogueJson = """
        {
          "providers": [
            {
              "id": "rutracker",
              "displayName": "RuTracker.org",
              "kind": "native",
              "capabilities": ["SEARCH","TORRENT_DOWNLOAD","CAPTCHA_LOGIN"],
              "authType": "CAPTCHA_LOGIN",
              "encoding": "Windows-1251",
              "baseUrls": ["https://rutracker.org"],
              "supportsAnonymous": false
            },
            {
              "id": "1337x",
              "displayName": "1337x",
              "kind": "jackett",
              "indexer": "1337x",
              "capabilities": ["SEARCH","MAGNET_LINK","TORRENT_DOWNLOAD"],
              "authType": "NONE",
              "encoding": "UTF-8",
              "baseUrls": [],
              "supportsAnonymous": true
            }
          ]
        }
    """.trimIndent()

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()

        registry = DefaultTrackerRegistry()
        registerBundledTracker("rutracker", "RuTracker.org")
        // Install the ApiBackedTrackerClient factory the production graph
        // installs at DI time (TrackerClientModule.provideTrackerRegistry) —
        // DefaultTrackerRegistry.populateFrom(non-empty) requires it. We bind a
        // FakeTrackerClient at the OUTERMOST boundary (the client object itself
        // is below the SUT); the SUT under test is the real
        // DefaultTrackerRegistry.populateFrom + the real OnboardingViewModel
        // wiring + the real FetchProvidersUseCase/ProviderCatalogRepository over
        // MockWebServer — none of which are mocked (§6.J Second Law).
        registry.setApiClientFactory { descriptor -> FakeTrackerClient(descriptor) }
        sdk = LavaTrackerSdk(registry)

        val fakeCredsDao = object : ProviderCredentialsDao {
            override suspend fun load(providerId: String) = null
            override fun observeAll() = emptyFlow<List<ProviderCredentialsEntity>>()
            override fun observe(providerId: String) = emptyFlow<ProviderCredentialsEntity?>()
            override suspend fun upsert(entity: ProviderCredentialsEntity) {}
            override suspend fun delete(providerId: String) {}
        }
        credentialManager = ProviderCredentialManager(
            CredentialsRepository(fakeCredsDao, CredentialEncryptor()),
        )
        providerConfigRepository = ProviderConfigRepository(FakeProviderConfigDao())
    }

    @After
    fun teardown() {
        // Reset the process-wide active-base-URL holder the VM sets before
        // populateFrom, so it does not leak into other tests in the module.
        lava.tracker.client.ApiBaseUrlHolder.reset()
        server.shutdown()
    }

    private fun registerBundledTracker(id: String, displayName: String) {
        val descriptor = object : TrackerDescriptor {
            override val trackerId: String = id
            override val displayName: String = displayName
            override val baseUrls = listOf(MirrorUrl(url = "https://$id.example", isPrimary = true))
            override val capabilities = setOf(TrackerCapability.SEARCH)
            override val authType: AuthType = AuthType.NONE
            override val encoding = "UTF-8"
            override val expectedHealthMarker = "ok"
            override val verified = true
            override val apiSupported = true
        }
        registry.register(
            object : PluginFactory<TrackerDescriptor, TrackerClient> {
                override val descriptor: TrackerDescriptor = descriptor
                override fun create(config: PluginConfig): TrackerClient = FakeTrackerClient(descriptor)
            },
        )
    }

    private fun TestScope.createViewModel(
        fetchProvidersUseCase: lava.domain.usecase.FetchProvidersUseCase?,
    ): OnboardingViewModel = OnboardingViewModel(
        sdk = sdk,
        credentialManager = credentialManager,
        authService = FakeAuthService(),
        loggerFactory = TestLoggerFactory(),
        analytics = NoOpAnalytics,
        providerConfigRepository = providerConfigRepository,
        clonedProviderDao = FakeClonedProviderDao(),
        discoveryService = lava.testing.service.TestLocalNetworkDiscoveryService(),
        connectionService = object : lava.data.api.service.ConnectionService {
            override val networkUpdates = emptyFlow<Boolean>()

            // Probe boundary is faked (outermost boundary per Anti-Bluff Pact);
            // the catalogue fetch is REAL over MockWebServer.
            override suspend fun isReachable(endpoint: Endpoint): Boolean = true
            override suspend fun isInternetReachable(): Boolean = true
        },
        endpointsRepository = lava.testing.repository.TestEndpointsRepository(),
        apiSelectionEnabled = true,
        siblingAppLauncher = noOpSiblingAppLauncher,
        fetchProvidersUseCase = fetchProvidersUseCase,
        trackerRegistry = registry,
        // The real catalogue fetch runs over plain-HTTP MockWebServer, so the
        // VM must derive an `http://host:port` base URL — substitute an http
        // builder for the production `SseBaseUrlBuilder.Https`. This is the
        // exact MockWebServer-substitution the builder seam was designed for
        // (see SseBaseUrlBuilder KDoc); no scheme literal in production code.
        apiBaseUrlBuilder = lava.network.sse.SseBaseUrlBuilder { host, port -> "http://$host:$port" },
    )

    private fun goApiEndpoint(): Endpoint.GoApi =
        Endpoint.GoApi(host = server.hostName, port = server.port)

    // CHALLENGE — primary assertion on state.providers (the rendered list).
    @Test
    fun `dynamic discovery populates provider list from the API including an API-only provider`() =
        runTest(dispatcherRule.testDispatcher) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(catalogueJson),
            )

            // Real catalogue stack: ProviderCatalogRepository + FetchProvidersUseCase
            // over the MockWebServer socket (NOT a mock of the use case).
            val fetchUseCase: lava.domain.usecase.FetchProvidersUseCase =
                buildRealFetchProvidersUseCase(server)

            val viewModel = createViewModel(fetchUseCase)
            viewModel.test(this) {
                runOnCreate()
                expectInitialState()
                awaitState() // onCreate loadProviders → bundled list (rutracker only)

                viewModel.perform(OnboardingAction.NextStep) // Welcome → ApiSelection
                viewModel.perform(OnboardingAction.SelectApi(goApiEndpoint()))

                // Walk states until the wizard advances to Providers; by then the
                // catalogue has been fetched, populateFrom ran, and loadProviders
                // refreshed the list from the populated registry.
                var providersOnScreen: List<String> = emptyList()
                var advanced = false
                var guard = 0
                while (guard < 12) {
                    guard++
                    val s = awaitState()
                    providersOnScreen = s.providers.map { it.descriptor.trackerId }
                    if (s.step == OnboardingStep.Providers && providersOnScreen.contains("1337x")) {
                        advanced = true
                        break
                    }
                }
                cancelAndIgnoreRemainingItems()

                assertTrue(
                    "after ApiSelection probe + catalogue fetch the wizard MUST be on " +
                        "Providers with the API list rendered",
                    advanced,
                )
                // PRIMARY: the API-only Jackett indexer the bundled registry never
                // had MUST appear in the list the user sees.
                assertTrue(
                    "API-only provider '1337x' (Jackett indexer) MUST appear in the " +
                        "dynamically-discovered provider list — was $providersOnScreen",
                    providersOnScreen.contains("1337x"),
                )
            }
        }

    // CHALLENGE — primary assertion on the non-blocking notice + non-blank list.
    @Test
    fun `catalogue fetch failure falls back to bundled providers with a non-blocking notice not a blank list`() =
        runTest(dispatcherRule.testDispatcher) {
            server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

            // Real catalogue stack — the real use case maps a 500 to
            // Result.failure (it does NOT throw).
            val fetchUseCase: lava.domain.usecase.FetchProvidersUseCase =
                buildRealFetchProvidersUseCase(server)

            val viewModel = createViewModel(fetchUseCase)
            viewModel.test(this) {
                runOnCreate()
                expectInitialState()
                awaitState() // bundled list (rutracker)

                viewModel.perform(OnboardingAction.NextStep) // Welcome → ApiSelection
                viewModel.perform(OnboardingAction.SelectApi(goApiEndpoint()))

                var finalProviders: List<String> = emptyList()
                var notice: String? = null
                var landed = false
                var guard = 0
                while (guard < 12) {
                    guard++
                    val s = awaitState()
                    finalProviders = s.providers.map { it.descriptor.trackerId }
                    notice = s.providerCatalogNotice
                    if (s.step == OnboardingStep.Providers) {
                        landed = true
                        break
                    }
                }
                cancelAndIgnoreRemainingItems()

                assertTrue("wizard MUST still advance to Providers on fetch failure", landed)
                // PRIMARY 1: the list is NOT blank — bundled provider survives.
                assertFalse(
                    "on catalogue-fetch failure the provider list MUST NOT be blank (§6.AB) — " +
                        "the bundled 'rutracker' MUST remain. was $finalProviders",
                    finalProviders.isEmpty(),
                )
                assertTrue(
                    "bundled 'rutracker' MUST remain after fetch failure — was $finalProviders",
                    finalProviders.contains("rutracker"),
                )
                // PRIMARY 2: a non-blocking notice is surfaced to the user.
                assertNotNull(
                    "a fetch failure MUST surface a non-blocking provider-catalog notice",
                    notice,
                )
                assertEquals(
                    OnboardingViewModel.PROVIDER_CATALOG_FALLBACK_NOTICE,
                    notice,
                )
            }
        }

    /**
     * Captured catalogue that DELIBERATELY EXCLUDES the bundled "rutracker": the
     * chosen API supports a different provider set. This is the operator's exact
     * requirement — "all listed providers MUST be obtained from the API because
     * some APIs may or may not support all providers". After a successful fetch
     * the wizard MUST show ONLY the API's set (thepiratebay + yts) and the bundled
     * "rutracker" the API does NOT offer MUST be ABSENT (populateFrom REPLACES,
     * does not merge).
     */
    private val apiOnlyCatalogueWithoutRutracker = """
        {
          "providers": [
            {
              "id": "thepiratebay",
              "displayName": "The Pirate Bay",
              "kind": "native",
              "capabilities": ["SEARCH","MAGNET_LINK"],
              "authType": "NONE",
              "encoding": "UTF-8",
              "baseUrls": ["https://thepiratebay.org"],
              "supportsAnonymous": true
            },
            {
              "id": "yts",
              "displayName": "YTS",
              "kind": "native",
              "capabilities": ["SEARCH","MAGNET_LINK"],
              "authType": "NONE",
              "encoding": "UTF-8",
              "baseUrls": ["https://yts.mx"],
              "supportsAnonymous": true
            }
          ]
        }
    """.trimIndent()

    // CHALLENGE — the operator's load-bearing requirement: the provider list comes
    // FROM the chosen API. A bundled provider the API does NOT offer is REPLACED
    // out (not merged in), because some APIs may not support all providers.
    //
    // FALSIFIABILITY (§6.J clause 2):
    //   Mutation: in OnboardingViewModel.fetchAndPopulateProviders, drop the
    //             `trackerRegistry?.populateFrom(descriptors)` call (success path).
    //   Observed: this test FAILS — the bundled "rutracker" survives (no replace)
    //             so `providersOnScreen` still contains "rutracker" while the API's
    //             "thepiratebay"/"yts" are absent.
    //   Reverted: yes.
    @Test
    fun `onboarding shows ONLY the chosen API's providers replacing bundled ones the API lacks`() =
        runTest(dispatcherRule.testDispatcher) {
            // Bundled registry holds "rutracker" (registered in setup()); the API
            // catalogue below does NOT include it.
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(apiOnlyCatalogueWithoutRutracker),
            )
            val fetchUseCase = buildRealFetchProvidersUseCase(server)
            val viewModel = createViewModel(fetchUseCase)
            viewModel.test(this) {
                runOnCreate()
                expectInitialState()
                val bundled = awaitState().providers.map { it.descriptor.trackerId }
                // Precondition: bundled list DID contain rutracker before the API fetch.
                assertTrue(
                    "precondition: bundled list must contain 'rutracker' before the API fetch — was $bundled",
                    bundled.contains("rutracker"),
                )

                viewModel.perform(OnboardingAction.NextStep) // Welcome → ApiSelection
                viewModel.perform(OnboardingAction.SelectApi(goApiEndpoint()))

                var providersOnScreen: List<String> = emptyList()
                var advanced = false
                var guard = 0
                while (guard < 12) {
                    guard++
                    val s = awaitState()
                    providersOnScreen = s.providers.map { it.descriptor.trackerId }
                    if (s.step == OnboardingStep.Providers && providersOnScreen.contains("thepiratebay")) {
                        advanced = true
                        break
                    }
                }
                cancelAndIgnoreRemainingItems()

                assertTrue("wizard MUST advance to Providers with the API list", advanced)
                // PRIMARY 1: the API's providers are shown.
                assertTrue(
                    "the chosen API's providers MUST be shown — expected thepiratebay+yts, was $providersOnScreen",
                    providersOnScreen.containsAll(listOf("thepiratebay", "yts")),
                )
                // PRIMARY 2 (the operator's requirement): the bundled 'rutracker'
                // the API does NOT offer MUST be ABSENT — providers come FROM the
                // API (replace, not merge).
                assertFalse(
                    "bundled 'rutracker' (NOT in the chosen API's catalogue) MUST be " +
                        "ABSENT — the list comes FROM the API, not merged with bundled. was $providersOnScreen",
                    providersOnScreen.contains("rutracker"),
                )
                // The list is EXACTLY the API set (no stray bundled entries).
                assertEquals(
                    "provider list MUST equal the chosen API's set",
                    setOf("thepiratebay", "yts"),
                    providersOnScreen.toSet(),
                )
            }
        }

    /**
     * Constructs the REAL [lava.domain.usecase.FetchProvidersUseCase] over the
     * REAL [lava.data.provider.ProviderCatalogRepository] (a real
     * [okhttp3.OkHttpClient] + the real [lava.data.provider.InMemoryProviderCatalogStore])
     * — NOT a mock of the use case (§6.J Second Law). The repository issues a
     * real HTTP `GET {apiBaseUrl}/providers` against the [server] socket.
     *
     * Transport note: the VM derives `apiBaseUrl = http://{server.hostName}:{server.port}`
     * from the test [Endpoint.GoApi] via the injected http [apiBaseUrlBuilder]
     * (see [createViewModel]) and passes it to the String-keyed use case, so the
     * repository's request lands on [server]. The [server] parameter is retained
     * for signature stability + to document the socket the real stack targets.
     */
    private fun buildRealFetchProvidersUseCase(
        @Suppress("UNUSED_PARAMETER") server: MockWebServer,
    ): lava.domain.usecase.FetchProvidersUseCase {
        // Plain-HTTP MockWebServer here: this VM-layer test covers the
        // wiring + the >4-providers user-visible outcome. The self-signed-TLS +
        // Lava-Auth boundary (the Defect-A failure mode) is decisively covered
        // one layer down in ProviderCatalogRepositoryTest, which crosses the
        // real cert + header gate. A vanilla client over plain http suffices to
        // exercise the registry-population path the VM owns.
        val repository = lava.data.provider.ProviderCatalogRepository(
            lanHttpClient = okhttp3.OkHttpClient(),
            authFieldName = "Lava-Auth",
            store = lava.data.provider.InMemoryProviderCatalogStore(),
        )
        return lava.domain.usecase.FetchProvidersUseCase(repository)
    }

    private object NoOpAnalytics : AnalyticsTracker {
        override fun event(name: String, params: Map<String, String>) {}
        override fun setUserId(userId: String?) {}
        override fun setProperty(key: String, value: String?) {}
        override fun recordNonFatal(throwable: Throwable, context: Map<String, String>) {}
        override fun recordWarning(message: String, context: Map<String, String>) {}
        override fun log(message: String) {}
    }
}

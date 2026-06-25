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
import lava.data.api.service.ConnectionService
import lava.data.api.service.DiscoveredEndpoint
import lava.database.dao.ProviderCredentialsDao
import lava.database.entity.ProviderCredentialsEntity
import lava.models.settings.Endpoint
import lava.onboarding.steps.CLOUD_SUBTITLE
import lava.onboarding.steps.discoveredName
import lava.onboarding.steps.displaySubtitle
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.orbitmvi.orbit.test.test

/**
 * Reproduce-first ViewModel coverage for the 2026-06-25 video-sweep display /
 * onboarding fixes — issues #6, #7, #8, #9. Each test traverses the REAL
 * [OnboardingViewModel] wired to the REAL [LavaTrackerSdk] +
 * [ProviderConfigRepository] on behaviourally-equivalent in-memory DAO fakes
 * (Anti-Bluff Pact Second + Third Law); only the outermost boundaries
 * ([ConnectionService], [TestLocalNetworkDiscoveryService], [SiblingAppLauncher])
 * are faked. The primary assertion of each test is on user-visible
 * [OnboardingState] (or, for the pure render helpers, on the exact string the
 * Composable renders).
 *
 * Each test documents the falsifiability rehearsal (Sixth Law clause 2):
 * deliberately break the production code path it covers, observe the assertion
 * failure, revert.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelVideoFixesTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private lateinit var registry: DefaultTrackerRegistry
    private lateinit var sdk: LavaTrackerSdk
    private lateinit var credentialManager: ProviderCredentialManager
    private lateinit var providerConfigRepository: ProviderConfigRepository
    private lateinit var providerConfigDao: FakeProviderConfigDao
    private lateinit var clonedProviderDao: FakeClonedProviderDao
    private lateinit var endpointsRepository: TestEndpointsRepository
    private lateinit var authService: FakeAuthService

    @Before
    fun setup() {
        registry = DefaultTrackerRegistry()
        clonedProviderDao = FakeClonedProviderDao()
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
        providerConfigDao = FakeProviderConfigDao()
        providerConfigRepository = ProviderConfigRepository(providerConfigDao)
        endpointsRepository = TestEndpointsRepository()
        authService = FakeAuthService()
    }

    // ── Test descriptor builders ─────────────────────────────────────────────

    /** A no-auth (anonymous-by-construction) provider — usable without creds. */
    private fun registerNoAuth(id: String) = register(id, AuthType.NONE, supportsAnon = false)

    /** A FORM_LOGIN provider that PERMITS anonymous browse — usable without creds. */
    private fun registerAnonCapable(id: String) =
        register(id, AuthType.FORM_LOGIN, supportsAnon = true)

    /** A FORM_LOGIN provider that REQUIRES credentials (no anonymous path). */
    private fun registerCredRequired(id: String) =
        register(id, AuthType.FORM_LOGIN, supportsAnon = false)

    private fun register(id: String, auth: AuthType, supportsAnon: Boolean) {
        val descriptor = object : TrackerDescriptor {
            override val trackerId: String = id
            override val displayName: String = id.replaceFirstChar { it.titlecase() }
            override val baseUrls = listOf(MirrorUrl(url = "https://$id.example", isPrimary = true))
            override val capabilities = setOf(TrackerCapability.SEARCH)
            override val authType: AuthType = auth
            override val supportsAnonymous: Boolean = supportsAnon
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

    private fun rebuildSdk() {
        sdk = LavaTrackerSdk(registry, clonedProviderDao = clonedProviderDao)
    }

    private val noOpAnalytics = object : AnalyticsTracker {
        override fun event(name: String, params: Map<String, String>) {}
        override fun setUserId(userId: String?) {}
        override fun setProperty(key: String, value: String?) {}
        override fun recordNonFatal(throwable: Throwable, context: Map<String, String>) {}
        override fun recordWarning(message: String, context: Map<String, String>) {}
        override fun log(message: String) {}
    }

    private val noOpSiblingAppLauncher = object : SiblingAppLauncher {
        override fun isInstalled(): Boolean = false
        override fun intentToOpen(): android.content.Intent? = null
        override fun intentToDownload(): android.content.Intent =
            android.content.Intent(android.content.Intent.ACTION_VIEW)
    }

    private fun connectionService(reachable: Boolean = true) = object : ConnectionService {
        override val networkUpdates = emptyFlow<Boolean>()
        override suspend fun isReachable(endpoint: Endpoint): Boolean = reachable
        override suspend fun isInternetReachable(): Boolean = true
    }

    private fun TestScope.createViewModel(
        apiSelectionEnabled: Boolean,
        discovery: TestLocalNetworkDiscoveryService = TestLocalNetworkDiscoveryService(),
    ): OnboardingViewModel = OnboardingViewModel(
        sdk = sdk,
        credentialManager = credentialManager,
        authService = authService,
        loggerFactory = TestLoggerFactory(),
        analytics = noOpAnalytics,
        providerConfigRepository = providerConfigRepository,
        clonedProviderDao = clonedProviderDao,
        discoveryService = discovery,
        connectionService = connectionService(),
        endpointsRepository = endpointsRepository,
        apiSelectionEnabled = apiSelectionEnabled,
        siblingAppLauncher = noOpSiblingAppLauncher,
    )

    private suspend fun org.orbitmvi.orbit.test.OrbitTestContext<OnboardingState, OnboardingSideEffect, OnboardingViewModel>.awaitStateWhere(
        guard: Int = 12,
        predicate: (OnboardingState) -> Boolean,
    ): OnboardingState {
        var n = 0
        while (n < guard) {
            n++
            val s = awaitState()
            if (predicate(s)) return s
        }
        error("awaitStateWhere: predicate never satisfied within $guard states")
    }

    // ── Issue #6 — Welcome provider count must match the list, never lie ──────
    //
    // FALSIFIABILITY: in loadProviders set `welcomeProviderCount = items.size`
    // unconditionally (drop the `if (apiSelectionEnabled) null` guard). Observed:
    // this test FAILS — "Welcome MUST NOT assert a premature count in the
    // ApiSelection flow" because the count is a non-null bundled size that the
    // post-API-catalogue picker contradicts. Reverted.
    @Test
    fun `welcome count is null in api-selection flow so it cannot contradict the picker`() =
        runTest(dispatcherRule.testDispatcher) {
            registerNoAuth("a")
            registerNoAuth("b")
            rebuildSdk()
            val viewModel = createViewModel(apiSelectionEnabled = true)
            viewModel.test(this) {
                runOnCreate()
                expectInitialState()
                val loaded = awaitStateWhere { it.providers.size == 2 }
                // PRIMARY: the Welcome screen must NOT show a concrete count that
                // the (later, API-populated) picker would contradict.
                assertNull(
                    "ApiSelection flow MUST NOT pin a premature Welcome count; was " +
                        "${loaded.welcomeProviderCount}",
                    loaded.welcomeProviderCount,
                )
                cancelAndIgnoreRemainingItems()
            }
        }

    // FALSIFIABILITY: in loadProviders set `welcomeProviderCount = null`
    // unconditionally. Observed: this test FAILS — "legacy flow count MUST equal
    // the bundled provider list size" because it becomes null instead of 2.
    @Test
    fun `welcome count equals provider list size in legacy flow`() =
        runTest(dispatcherRule.testDispatcher) {
            registerNoAuth("a")
            registerNoAuth("b")
            rebuildSdk()
            val viewModel = createViewModel(apiSelectionEnabled = false)
            viewModel.test(this) {
                runOnCreate()
                expectInitialState()
                val loaded = awaitStateWhere { it.providers.isNotEmpty() }
                // PRIMARY: in the legacy Welcome → Providers flow the count IS the
                // list the user sees next, so it must equal providers.size exactly.
                assertEquals(
                    "legacy-flow Welcome count MUST equal the provider list the user " +
                        "sees next",
                    loaded.providers.size,
                    loaded.welcomeProviderCount,
                )
                cancelAndIgnoreRemainingItems()
            }
        }

    // ── Issue #9 — Select all must not enable credential-required providers ───
    //
    // FALSIFIABILITY: in onToggleAllProviders make the select branch set
    // `target = true` for every item (the pre-fix shape). Observed: this test
    // FAILS — "select-all MUST NOT auto-enable the credential-required provider"
    // because the cred provider becomes selected. Reverted.
    @Test
    fun `select all enables only no-credential providers`() =
        runTest(dispatcherRule.testDispatcher) {
            registerNoAuth("noauth")
            registerAnonCapable("anon")
            registerCredRequired("creds")
            rebuildSdk()
            val viewModel = createViewModel(apiSelectionEnabled = false)
            viewModel.test(this) {
                runOnCreate()
                expectInitialState()
                awaitStateWhere { it.providers.size == 3 }

                viewModel.perform(OnboardingAction.NextStep) // → Providers
                awaitStateWhere { it.step == OnboardingStep.Providers }

                // Deselect everything first so "Select all" is the gesture under
                // test (loadProviders defaults all to selected).
                viewModel.perform(OnboardingAction.ToggleAllProviders) // deselect-all
                awaitStateWhere { it.providers.none { p -> p.selected } }

                viewModel.perform(OnboardingAction.ToggleAllProviders) // select-all
                val afterSelect = awaitStateWhere { it.providers.any { p -> p.selected } }

                fun selected(id: String) =
                    afterSelect.providers.first { it.descriptor.trackerId == id }.selected

                // PRIMARY: no-auth + anonymous-capable providers ARE auto-selected.
                assertTrue("no-auth provider MUST be auto-selected by select-all", selected("noauth"))
                assertTrue("anonymous-capable provider MUST be auto-selected", selected("anon"))
                // PRIMARY: the credential-required provider is NOT auto-selected —
                // the user must opt in explicitly so they pass through Configure.
                assertFalse(
                    "credential-required provider MUST NOT be silently enabled by select-all",
                    selected("creds"),
                )
                cancelAndIgnoreRemainingItems()
            }
        }

    // FALSIFIABILITY: in onToggleAllProviders deselect branch set `target =
    // item.requiresNoCredentials()` (so deselect leaves cred providers as-was).
    // Observed: this test FAILS — "deselect-all MUST clear EVERY provider" because
    // a manually-opted-in cred provider survives. Reverted.
    @Test
    fun `deselect all clears every provider including manually opted-in cred providers`() =
        runTest(dispatcherRule.testDispatcher) {
            registerNoAuth("noauth")
            registerCredRequired("creds")
            rebuildSdk()
            val viewModel = createViewModel(apiSelectionEnabled = false)
            viewModel.test(this) {
                runOnCreate()
                expectInitialState()
                awaitStateWhere { it.providers.size == 2 }

                viewModel.perform(OnboardingAction.NextStep) // → Providers
                awaitStateWhere { it.step == OnboardingStep.Providers }

                // loadProviders defaults all selected → first toggle is deselect-all.
                viewModel.perform(OnboardingAction.ToggleAllProviders)
                val afterDeselect = awaitStateWhere { it.providers.none { p -> p.selected } }
                // PRIMARY: deselect-all means NONE — the cred provider is cleared too.
                assertTrue(
                    "deselect-all MUST clear every provider; was " +
                        "${afterDeselect.providers.map { it.descriptor.trackerId to it.selected }}",
                    afterDeselect.providers.none { it.selected },
                )
                cancelAndIgnoreRemainingItems()
            }
        }

    // ── Issue #8 — discovered API friendly mDNS name is surfaced ──────────────
    //
    // FALSIFIABILITY: in startApiDiscovery drop the `discoveredApiNames +
    // (key to friendlyName)` reduce (keep only discoveredApis). Observed: this
    // test FAILS — "discovered API name MUST be captured for the friendly label"
    // because the name map stays empty. Reverted.
    @Test
    fun `discovery captures the friendly mDNS instance name keyed by host port`() =
        runTest(dispatcherRule.testDispatcher) {
            registerNoAuth("a")
            rebuildSdk()
            val discovery = TestLocalNetworkDiscoveryService()
            val viewModel = createViewModel(apiSelectionEnabled = true, discovery = discovery)
            viewModel.test(this) {
                runOnCreate()
                expectInitialState()
                awaitStateWhere { it.providers.isNotEmpty() }

                viewModel.perform(OnboardingAction.NextStep) // → ApiSelection (starts discovery)
                awaitStateWhere { it.step == OnboardingStep.ApiSelection }

                discovery.emit(
                    DiscoveredEndpoint(
                        host = "192.168.0.107:8443",
                        port = 8443,
                        name = "Home Server",
                        engine = DiscoveredEndpoint.Engine.Go,
                    ),
                )

                val withName = awaitStateWhere { it.discoveredApiNames.isNotEmpty() }
                // PRIMARY: the friendly name the advertiser published is captured,
                // keyed by the host:port the discovered endpoint carries, so the
                // rendered row can show "Home Server" instead of raw ip:port.
                assertEquals(
                    "discovered friendly name MUST be captured under the host:port key",
                    "Home Server",
                    withName.discoveredApiNames[discoveredApiNameKey("192.168.0.107", 8443)],
                )
                // The endpoint itself is in the discovered list (unchanged shape).
                assertTrue(
                    "discovered endpoint MUST appear in the list",
                    withName.discoveredApis.any {
                        it is Endpoint.GoApi && it.host == "192.168.0.107" && it.port == 8443
                    },
                )
                cancelAndIgnoreRemainingItems()
            }
        }

    // ── Issue #8 render helper — friendly name resolves from the side map ─────
    //
    // FALSIFIABILITY: in Endpoint.discoveredName return `null` for the GoApi
    // branch. Observed: this test FAILS — "discoveredName MUST resolve the
    // published name" returns null instead of "Home Server". Reverted.
    @Test
    fun `discoveredName resolves the friendly name from the side map by host port`() {
        val endpoint = Endpoint.GoApi(host = "192.168.0.107", port = 8443)
        val names = mapOf(discoveredApiNameKey("192.168.0.107", 8443) to "Home Server")
        assertEquals("Home Server", endpoint.discoveredName(names))
        // No matching entry → null (row falls back to host:port).
        assertNull(endpoint.discoveredName(emptyMap()))
    }

    // ── Issue #7 — a cloud preset is NOT labelled "On this network" ───────────
    //
    // The cloud-preset subtitle is the constant CLOUD_SUBTITLE, NOT the
    // discoveredApiLabel("On this network") a platform-less GoApi would otherwise
    // render. This pure helper asserts the two are distinct so the cloud preset
    // can never be mislabelled.
    //
    // FALSIFIABILITY: set CLOUD_SUBTITLE = "On this network". Observed: this test
    // FAILS — "cloud subtitle MUST NOT read 'On this network'". Reverted.
    @Test
    fun `cloud preset subtitle is honest and not on-this-network`() {
        val cloudPreset = Endpoint.GoApi(host = "lava.example", port = 7777)
        // The default GoApi subtitle (used for a discovered LAN instance) reads
        // "... On this network"; a cloud preset MUST use CLOUD_SUBTITLE instead.
        assertTrue(
            "the default GoApi subtitle is the 'On this network' label this fix avoids for cloud",
            cloudPreset.displaySubtitle().contains("On this network"),
        )
        assertFalse(
            "cloud preset subtitle MUST NOT read 'On this network'",
            CLOUD_SUBTITLE.contains("On this network"),
        )
        assertEquals("Cloud / remote server", CLOUD_SUBTITLE)
    }
}

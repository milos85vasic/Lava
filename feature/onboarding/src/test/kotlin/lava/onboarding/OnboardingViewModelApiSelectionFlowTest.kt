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
import lava.database.dao.ProviderCredentialsDao
import lava.database.entity.ProviderCredentialsEntity
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
import lava.tracker.api.model.AuthState
import lava.tracker.api.model.LoginResult
import lava.tracker.client.LavaTrackerSdk
import lava.tracker.client.ProviderSessionTokenHolder
import lava.tracker.registry.DefaultTrackerRegistry
import lava.tracker.testing.FakeTrackerClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.orbitmvi.orbit.test.test

/**
 * Full-automation state-machine coverage for the onboarding wizard with the
 * production `apiSelectionEnabled = true` flow active
 * (Welcome → ApiSelection → Providers → Configure → Summary).
 *
 * The pre-existing [OnboardingViewModelTest] drives every case with
 * `apiSelectionEnabled = false` (the legacy Welcome → Providers flow), so the
 * ApiSelection-enabled state machine — the flow real users actually see — was
 * untested at the ViewModel layer. This class closes that gap: every step
 * transition (forward + back), the probe success / failure / retry paths, and
 * the credential success / failure / exception paths each get a falsifiable
 * test asserting on user-visible [OnboardingState] / [OnboardingSideEffect].
 *
 * §6.J Second Law: the SUT is the REAL [OnboardingViewModel] wired to the REAL
 * [LavaTrackerSdk] + REAL [ProviderConfigRepository] + REAL
 * [ProviderCredentialManager] on behaviourally-equivalent in-memory DAO fakes
 * (Third Law). Only the outermost boundaries — [ConnectionService] (the TCP
 * probe) and [SiblingAppLauncher] / [TestLocalNetworkDiscoveryService] (Android
 * system services) — are faked.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * FALSIFIABILITY REHEARSAL (§6.J / Sixth Law clause 2) — recorded per Bluff-Audit
 * stamp in the commit body. Each test names the production mutation that makes it
 * fail and the assertion message produced; all reverted.
 * ─────────────────────────────────────────────────────────────────────────────
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelApiSelectionFlowTest {

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

    private lateinit var registry: DefaultTrackerRegistry
    private lateinit var sdk: LavaTrackerSdk
    private lateinit var credentialManager: ProviderCredentialManager
    private lateinit var authService: FakeAuthService
    private lateinit var providerConfigRepository: ProviderConfigRepository
    private lateinit var providerConfigDao: FakeProviderConfigDao
    private lateinit var clonedProviderDao: FakeClonedProviderDao
    private lateinit var endpointsRepository: TestEndpointsRepository

    /**
     * Pre-configured FORM_LOGIN client whose [FakeTrackerClient.loginProvider]
     * the test sets so `sdk.login()` returns a deterministic [LoginResult]. A
     * single shared instance (closure-captured by the factory) so every
     * `registry.get(...)` resolves the SAME configured client regardless of
     * whether the registry caches.
     */
    private var formLoginClient: FakeTrackerClient? = null

    @Before
    fun setup() {
        // Global singleton holder — clear so a prior test's token cannot leak in.
        ProviderSessionTokenHolder.reset()
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
        authService = FakeAuthService()
        endpointsRepository = TestEndpointsRepository()
    }

    /**
     * Register a no-auth tracker (the bundled-provider stand-in). Its
     * loadProviders filter requires verified && apiSupported.
     */
    private fun registerAnonTracker(id: String = "test-tracker", displayName: String = "Test Tracker") {
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

    /**
     * Register a FORM_LOGIN tracker that DOES expose an AuthenticatableTracker
     * feature (capability AUTH_REQUIRED) — so `sdk.login()` returns the
     * [LoginResult] produced by [loginState] (NOT null). This is the real
     * credentialed-provider shape the onboarding TestAndContinue credential
     * branch exercises.
     */
    private fun registerFormLoginTracker(loginState: AuthState, sessionToken: String? = null) {
        val descriptor = object : TrackerDescriptor {
            override val trackerId: String = "form-login-tracker"
            override val displayName: String = "Form Login Tracker"
            override val baseUrls = listOf(MirrorUrl(url = "https://form.example", isPrimary = true))
            override val capabilities = setOf(TrackerCapability.SEARCH, TrackerCapability.AUTH_REQUIRED)
            override val authType: AuthType = AuthType.FORM_LOGIN
            override val encoding = "UTF-8"
            override val expectedHealthMarker = "ok"
            override val verified = true
            override val apiSupported = true
        }
        val client = FakeTrackerClient(descriptor).apply {
            loginProvider = { LoginResult(loginState, sessionToken = sessionToken) }
        }
        formLoginClient = client
        registry.register(
            object : PluginFactory<TrackerDescriptor, TrackerClient> {
                override val descriptor: TrackerDescriptor = descriptor
                override fun create(config: PluginConfig): TrackerClient = client
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

    private fun connectionService(reachable: Boolean) = object : ConnectionService {
        override val networkUpdates = emptyFlow<Boolean>()
        override suspend fun isReachable(endpoint: Endpoint): Boolean = reachable
        override suspend fun isInternetReachable(): Boolean = true
    }

    /**
     * Build the ViewModel in the PRODUCTION `apiSelectionEnabled = true` shape.
     * [reachable] controls the probe boundary; [discovery] lets a test emit a
     * discovered endpoint into the real discovery flow the VM collects.
     */
    private fun TestScope.createViewModel(
        reachable: Boolean = true,
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
        connectionService = connectionService(reachable),
        endpointsRepository = endpointsRepository,
        apiSelectionEnabled = true,
        siblingAppLauncher = noOpSiblingAppLauncher,
    )

    private fun goApi(host: String = "192.0.2.50", port: Int = 8443) =
        Endpoint.GoApi(host = host, port = port)

    /** Walk states until [predicate] holds or the guard trips; returns the matching state. */
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

    // ── G1: Welcome → ApiSelection (apiSelectionEnabled=true) ─────────────────
    //
    // FALSIFIABILITY: in onNextStep Welcome branch, change `if (apiSelectionEnabled)`
    // body to `step = OnboardingStep.Providers`. Observed: this test FAILS —
    // "Welcome NextStep with apiSelectionEnabled MUST enter ApiSelection" because
    // step lands on Providers. Reverted.
    @Test
    fun `next step from Welcome enters ApiSelection when apiSelectionEnabled`() =
        runTest(dispatcherRule.testDispatcher) {
            registerAnonTracker()
            rebuildSdk()
            val viewModel = createViewModel()
            viewModel.test(this) {
                runOnCreate()
                expectInitialState()
                awaitState() // providers loaded

                viewModel.perform(OnboardingAction.NextStep)
                val s = awaitStateWhere { it.step == OnboardingStep.ApiSelection }
                assertEquals(
                    "Welcome NextStep with apiSelectionEnabled MUST enter ApiSelection",
                    OnboardingStep.ApiSelection,
                    s.step,
                )
                cancelAndIgnoreRemainingItems()
            }
        }

    // ── G2: ApiSelection discovered-list SelectApi success → Providers ────────
    //
    // FALSIFIABILITY: in onSelectApi success branch, drop `step = Providers`.
    // Observed: FAILS — "successful probe MUST advance to Providers" never reached.
    // Reverted.
    @Test
    fun `selecting a reachable discovered api advances to Providers`() =
        runTest(dispatcherRule.testDispatcher) {
            registerAnonTracker()
            rebuildSdk()
            val viewModel = createViewModel(reachable = true)
            viewModel.test(this) {
                runOnCreate()
                expectInitialState()
                awaitState()

                viewModel.perform(OnboardingAction.NextStep) // → ApiSelection
                awaitStateWhere { it.step == OnboardingStep.ApiSelection }
                viewModel.perform(OnboardingAction.SelectApi(goApi()))

                val advanced = awaitStateWhere { it.step == OnboardingStep.Providers }
                assertEquals(
                    "a reachable API selection MUST advance the wizard to Providers",
                    OnboardingStep.Providers,
                    advanced.step,
                )
                // The persisted endpoint is a precondition of advance (onSelectApi
                // calls endpointsRepository.add BEFORE the step reduce).
                val persisted = endpointsRepository.currentEndpoints()
                    .filterIsInstance<Endpoint.GoApi>()
                assertTrue(
                    "the selected endpoint MUST be persisted on probe success; was $persisted",
                    persisted.any { it.host == "192.0.2.50" && it.port == 8443 },
                )
                cancelAndIgnoreRemainingItems()
            }
        }

    // ── G3: ApiSelection SelectApi probe failure → Failure, no advance ────────
    //
    // FALSIFIABILITY: in onSelectApi make the `if (reachable)` branch run
    // unconditionally (treat unreachable as reachable). Observed: this test FAILS
    // — "unreachable API MUST surface Failure and MUST NOT advance" because step
    // becomes Providers. Reverted.
    @Test
    fun `selecting an unreachable api surfaces Failure and does not advance`() =
        runTest(dispatcherRule.testDispatcher) {
            registerAnonTracker()
            rebuildSdk()
            val viewModel = createViewModel(reachable = false)
            viewModel.test(this) {
                runOnCreate()
                expectInitialState()
                awaitState()

                viewModel.perform(OnboardingAction.NextStep) // → ApiSelection
                awaitStateWhere { it.step == OnboardingStep.ApiSelection }
                viewModel.perform(OnboardingAction.SelectApi(goApi()))

                val failed = awaitStateWhere {
                    it.apiConnectivity is ApiConnectivityState.Failure
                }
                // PRIMARY: the user sees a Failure (the "Could not reach this API"
                // banner is driven by this state).
                assertTrue(
                    "an unreachable probe MUST set apiConnectivity = Failure",
                    failed.apiConnectivity is ApiConnectivityState.Failure,
                )
                // PRIMARY: the wizard MUST NOT advance to Providers on failure.
                assertEquals(
                    "an unreachable probe MUST keep the user on ApiSelection",
                    OnboardingStep.ApiSelection,
                    failed.step,
                )
                cancelAndIgnoreRemainingItems()
            }
        }

    // ── G4: RetryApiProbe after a failure → success advances ──────────────────
    //
    // FALSIFIABILITY: in perform(), make RetryApiProbe a no-op (ignore action).
    // Observed: this test FAILS — after the connection becomes reachable the
    // retry never advances, "retry after failure MUST advance" trips. Reverted.
    @Test
    fun `retry api probe after a failure advances when the api becomes reachable`() =
        runTest(dispatcherRule.testDispatcher) {
            registerAnonTracker()
            rebuildSdk()
            // A connection service whose reachability flips after the first call.
            var calls = 0
            val flipping = object : ConnectionService {
                override val networkUpdates = emptyFlow<Boolean>()
                override suspend fun isReachable(endpoint: Endpoint): Boolean {
                    calls++
                    return calls > 1 // first probe fails, retry succeeds
                }
                override suspend fun isInternetReachable(): Boolean = true
            }
            val viewModel = OnboardingViewModel(
                sdk = sdk,
                credentialManager = credentialManager,
                authService = authService,
                loggerFactory = TestLoggerFactory(),
                analytics = noOpAnalytics,
                providerConfigRepository = providerConfigRepository,
                clonedProviderDao = clonedProviderDao,
                discoveryService = TestLocalNetworkDiscoveryService(),
                connectionService = flipping,
                endpointsRepository = endpointsRepository,
                apiSelectionEnabled = true,
                siblingAppLauncher = noOpSiblingAppLauncher,
            )
            viewModel.test(this) {
                runOnCreate()
                expectInitialState()
                awaitState()

                viewModel.perform(OnboardingAction.NextStep) // → ApiSelection
                awaitStateWhere { it.step == OnboardingStep.ApiSelection }

                viewModel.perform(OnboardingAction.SelectApi(goApi()))
                awaitStateWhere { it.apiConnectivity is ApiConnectivityState.Failure }

                // The user taps "Try again" — RetryApiProbe re-runs onSelectApi
                // with the same selectedApi, which now reaches.
                viewModel.perform(OnboardingAction.RetryApiProbe)
                val advanced = awaitStateWhere { it.step == OnboardingStep.Providers }
                assertEquals(
                    "retry after failure MUST advance once the API becomes reachable",
                    OnboardingStep.Providers,
                    advanced.step,
                )
                cancelAndIgnoreRemainingItems()
            }
        }

    // ── G5: Back from Providers → ApiSelection (apiSelectionEnabled=true) ──────
    //
    // FALSIFIABILITY: in onBackStep Providers branch, hardcode
    // `step = OnboardingStep.Welcome` (ignore apiSelectionEnabled). Observed:
    // this test FAILS — "back from Providers with apiSelectionEnabled MUST return
    // to ApiSelection" because step becomes Welcome. Reverted.
    @Test
    fun `back from Providers returns to ApiSelection when apiSelectionEnabled`() =
        runTest(dispatcherRule.testDispatcher) {
            registerAnonTracker()
            rebuildSdk()
            val viewModel = createViewModel(reachable = true)
            viewModel.test(this) {
                runOnCreate()
                expectInitialState()
                awaitState()

                viewModel.perform(OnboardingAction.NextStep) // → ApiSelection
                awaitStateWhere { it.step == OnboardingStep.ApiSelection }
                viewModel.perform(OnboardingAction.SelectApi(goApi()))
                awaitStateWhere { it.step == OnboardingStep.Providers }

                viewModel.perform(OnboardingAction.BackStep)
                val back = awaitStateWhere { it.step == OnboardingStep.ApiSelection }
                assertEquals(
                    "back from Providers with apiSelectionEnabled MUST return to ApiSelection",
                    OnboardingStep.ApiSelection,
                    back.step,
                )
                cancelAndIgnoreRemainingItems()
            }
        }

    // ── G6 / F1: Back from ApiSelection → Welcome clears discovery + cloud +
    // catalogue-notice state ───────────────────────────────────────────────────
    //
    // FALSIFIABILITY: in onBackStep ApiSelection branch, remove the
    // `cloudAddressError = null` (or `providerCatalogNotice = null`) line the F1
    // fix adds. Observed: this test FAILS — "back to Welcome MUST clear the stale
    // cloud parse error" because the error survives. Reverted.
    @Test
    fun `back from ApiSelection to Welcome clears stale cloud error and notice`() =
        runTest(dispatcherRule.testDispatcher) {
            registerAnonTracker()
            rebuildSdk()
            val viewModel = createViewModel(reachable = true)
            viewModel.test(this) {
                runOnCreate()
                expectInitialState()
                awaitState()

                viewModel.perform(OnboardingAction.NextStep) // → ApiSelection
                awaitStateWhere { it.step == OnboardingStep.ApiSelection }

                // Produce a user-visible cloud parse error so we can assert it is
                // cleared on the way back to Welcome.
                viewModel.perform(OnboardingAction.CloudAddressChanged("not a url /x"))
                awaitStateWhere { it.cloudAddressInput == "not a url /x" }
                viewModel.perform(OnboardingAction.AddCloudApi)
                awaitStateWhere { it.cloudAddressError != null }

                viewModel.perform(OnboardingAction.BackStep)
                val welcome = awaitStateWhere { it.step == OnboardingStep.Welcome }
                // PRIMARY: the stale parse error MUST NOT survive the back nav.
                assertNull(
                    "back to Welcome MUST clear the stale cloud parse error; was ${welcome.cloudAddressError}",
                    welcome.cloudAddressError,
                )
                assertEquals(
                    "back to Welcome MUST clear the typed cloud address",
                    "",
                    welcome.cloudAddressInput,
                )
                assertNull(
                    "back to Welcome MUST clear any provider-catalogue notice",
                    welcome.providerCatalogNotice,
                )
                // Discovery state is also reset (existing behaviour).
                assertTrue(
                    "back to Welcome MUST clear the discovered-API list",
                    welcome.discoveredApis.isEmpty(),
                )
                assertFalse(
                    "back to Welcome MUST stop the discovery spinner",
                    welcome.apiDiscoveryRunning,
                )
                cancelAndIgnoreRemainingItems()
            }
        }

    // ── G7: TestAndContinue FORM_LOGIN credential SUCCESS persists + advances ──
    //
    // FALSIFIABILITY: in onTestAndContinue, change
    // `loginResult.state != AuthState.Authenticated` to `== Authenticated` so an
    // authenticated login is treated as a failure. Observed: this test FAILS —
    // "credential login success MUST advance" because the wizard surfaces
    // "Invalid credentials" and never advances. Reverted.
    @Test
    fun `test and continue with valid credentials authenticates and advances`() =
        runTest(dispatcherRule.testDispatcher) {
            registerFormLoginTracker(loginState = AuthState.Authenticated)
            rebuildSdk()
            val viewModel = createViewModel(reachable = true)
            viewModel.test(this) {
                runOnCreate()
                expectInitialState()
                awaitState()

                // Reach Configure for the single FORM_LOGIN provider.
                viewModel.perform(OnboardingAction.NextStep) // → ApiSelection
                awaitStateWhere { it.step == OnboardingStep.ApiSelection }
                viewModel.perform(OnboardingAction.SelectApi(goApi()))
                awaitStateWhere { it.step == OnboardingStep.Providers }
                viewModel.perform(OnboardingAction.NextStep) // → Configure
                awaitStateWhere { it.step == OnboardingStep.Configure }

                viewModel.perform(OnboardingAction.UsernameChanged("alice"))
                awaitStateWhere { it.configs["form-login-tracker"]?.username == "alice" }
                viewModel.perform(OnboardingAction.PasswordChanged("s3cret"))
                awaitStateWhere { it.configs["form-login-tracker"]?.password == "s3cret" }

                viewModel.perform(OnboardingAction.TestAndContinue)
                // Single provider → after success, advance lands on Summary.
                val done = awaitStateWhere { it.step == OnboardingStep.Summary }
                val cfg = done.configs["form-login-tracker"]
                assertNull(
                    "a valid credential login MUST NOT surface an error; was ${cfg?.error}",
                    cfg?.error,
                )
                assertTrue(
                    "a valid credential login MUST mark the provider configured",
                    cfg?.configured == true,
                )
                assertTrue(
                    "a valid credential login MUST mark the provider tested",
                    cfg?.tested == true,
                )
                cancelAndIgnoreRemainingItems()
            }

            // The non-anonymous success path MUST persist a NON-anonymous config row.
            val persisted = providerConfigRepository.load("form-login-tracker")
            assertNotNull("onboarding MUST persist a provider_configs row", persisted)
            assertFalse(
                "a credentialed (non-anonymous) login MUST persist useAnonymous=false; was ${persisted?.useAnonymous}",
                persisted?.useAnonymous == true,
            )
        }

    // ── G7b: TestAndContinue FORM_LOGIN persists the PROVIDER session token ──────
    //
    // Regression for the 2026-07-02 CASE-COOKIE goapi defect: onboarding logged in
    // (produced a bb_session) but never wrote it to ProviderSessionTokenHolder, so
    // the dynamic ApiBackedTrackerClient searched with sessionToken=null →
    // withAuth() omitted `Auth-Token` → the Go API 401'd every
    // /v1/{provider}/search ("problem reaching the trackers"). Physical device
    // evidence (RED before the fix): the containerized-KVM Challenge70 keystone at
    // .lava-ci-evidence/autonomous-qa/2026-07-02/goapi/rutracker-1080p/. That
    // real-device run is the load-bearing §6.AK reproduce-first proof; this is the
    // fast unit gate that keeps the regression from silently returning.
    //
    // FALSIFIABILITY: remove `ProviderSessionTokenHolder.set(currentId,
    // loginResult.sessionToken)` from OnboardingViewModel's Authenticated
    // else-branch. Observed: this test FAILS — "onboarding login MUST persist the
    // provider session token ... expected:<bb_session=onboarding-session-xyz>
    // but was:<null>". Reverted: yes.
    @Test
    fun `test and continue with valid credentials persists the provider session token`() =
        runTest(dispatcherRule.testDispatcher) {
            val expectedToken = "bb_session=onboarding-session-xyz"
            registerFormLoginTracker(
                loginState = AuthState.Authenticated,
                sessionToken = expectedToken,
            )
            rebuildSdk()
            val viewModel = createViewModel(reachable = true)
            viewModel.test(this) {
                runOnCreate()
                expectInitialState()
                awaitState()

                viewModel.perform(OnboardingAction.NextStep) // → ApiSelection
                awaitStateWhere { it.step == OnboardingStep.ApiSelection }
                viewModel.perform(OnboardingAction.SelectApi(goApi()))
                awaitStateWhere { it.step == OnboardingStep.Providers }
                viewModel.perform(OnboardingAction.NextStep) // → Configure
                awaitStateWhere { it.step == OnboardingStep.Configure }

                viewModel.perform(OnboardingAction.UsernameChanged("alice"))
                awaitStateWhere { it.configs["form-login-tracker"]?.username == "alice" }
                viewModel.perform(OnboardingAction.PasswordChanged("s3cret"))
                awaitStateWhere { it.configs["form-login-tracker"]?.password == "s3cret" }

                viewModel.perform(OnboardingAction.TestAndContinue)
                awaitStateWhere { it.step == OnboardingStep.Summary }
                cancelAndIgnoreRemainingItems()
            }

            // PRIMARY anti-bluff assertion: the provider login-session token the
            // onboarding login produced MUST be in the holder, so the dynamic
            // ApiBackedTrackerClient threads it as `Auth-Token` on /v1 search.
            assertEquals(
                "onboarding login MUST persist the provider session token into " +
                    "ProviderSessionTokenHolder (else goapi /v1/{provider}/search 401s — CASE-COOKIE)",
                expectedToken,
                ProviderSessionTokenHolder.tokenFor("form-login-tracker"),
            )
        }

    // ── G8: TestAndContinue FORM_LOGIN credential FAILURE → Invalid credentials,
    // no advance ────────────────────────────────────────────────────────────────
    //
    // FALSIFIABILITY: in onTestAndContinue, remove the `return@launch` after the
    // Invalid-credentials reduce so the wizard advances anyway. Observed: this
    // test FAILS — "a failed login MUST NOT advance" because step leaves Configure.
    // Reverted.
    @Test
    fun `test and continue with invalid credentials surfaces error and does not advance`() =
        runTest(dispatcherRule.testDispatcher) {
            registerFormLoginTracker(loginState = AuthState.Unauthenticated)
            rebuildSdk()
            val viewModel = createViewModel(reachable = true)
            viewModel.test(this) {
                runOnCreate()
                expectInitialState()
                awaitState()

                viewModel.perform(OnboardingAction.NextStep) // → ApiSelection
                awaitStateWhere { it.step == OnboardingStep.ApiSelection }
                viewModel.perform(OnboardingAction.SelectApi(goApi()))
                awaitStateWhere { it.step == OnboardingStep.Providers }
                viewModel.perform(OnboardingAction.NextStep) // → Configure
                awaitStateWhere { it.step == OnboardingStep.Configure }

                viewModel.perform(OnboardingAction.UsernameChanged("alice"))
                awaitStateWhere { it.configs["form-login-tracker"]?.username == "alice" }
                viewModel.perform(OnboardingAction.PasswordChanged("wrong"))
                awaitStateWhere { it.configs["form-login-tracker"]?.password == "wrong" }

                viewModel.perform(OnboardingAction.TestAndContinue)
                val errored = awaitStateWhere {
                    it.configs["form-login-tracker"]?.error != null
                }
                val cfg = errored.configs["form-login-tracker"]
                // PRIMARY: the user sees the credential-error message.
                assertEquals(
                    "an Unauthenticated login MUST surface 'Invalid credentials'",
                    "Invalid credentials",
                    cfg?.error,
                )
                // PRIMARY: the wizard MUST stay on Configure (no advance).
                assertEquals(
                    "a failed login MUST keep the user on Configure",
                    OnboardingStep.Configure,
                    errored.step,
                )
                assertFalse(
                    "a failed login MUST NOT mark the provider configured",
                    cfg?.configured == true,
                )
                // The spinner must be cleared so the button is tappable again.
                assertFalse(
                    "a failed login MUST clear connectionTestRunning",
                    errored.connectionTestRunning,
                )
                cancelAndIgnoreRemainingItems()
            }
        }

    // ── G9: TestAndContinue exception path → error from throwable, no advance ──
    //
    // FALSIFIABILITY: in onTestAndContinue catch, drop the
    // `configs + (currentId to ... error = ...)` reduce (swallow the exception
    // silently). Observed: this test FAILS — "an exception MUST surface a
    // user-visible error" because config.error stays null. Reverted.
    @Test
    fun `test and continue surfaces the throwable message when login throws`() =
        runTest(dispatcherRule.testDispatcher) {
            // FORM_LOGIN tracker whose login throws — the real "connection error"
            // class (network down, parser blow-up) the catch block exists for.
            val descriptor = object : TrackerDescriptor {
                override val trackerId: String = "throwing-tracker"
                override val displayName: String = "Throwing Tracker"
                override val baseUrls = listOf(MirrorUrl(url = "https://throw.example", isPrimary = true))
                override val capabilities = setOf(TrackerCapability.SEARCH, TrackerCapability.AUTH_REQUIRED)
                override val authType: AuthType = AuthType.FORM_LOGIN
                override val encoding = "UTF-8"
                override val expectedHealthMarker = "ok"
                override val verified = true
                override val apiSupported = true
            }
            val client = FakeTrackerClient(descriptor).apply {
                loginProvider = { throw IllegalStateException("boom-connection-down") }
            }
            registry.register(
                object : PluginFactory<TrackerDescriptor, TrackerClient> {
                    override val descriptor: TrackerDescriptor = descriptor
                    override fun create(config: PluginConfig): TrackerClient = client
                },
            )
            rebuildSdk()
            val viewModel = createViewModel(reachable = true)
            viewModel.test(this) {
                runOnCreate()
                expectInitialState()
                awaitState()

                viewModel.perform(OnboardingAction.NextStep) // → ApiSelection
                awaitStateWhere { it.step == OnboardingStep.ApiSelection }
                viewModel.perform(OnboardingAction.SelectApi(goApi()))
                awaitStateWhere { it.step == OnboardingStep.Providers }
                viewModel.perform(OnboardingAction.NextStep) // → Configure
                awaitStateWhere { it.step == OnboardingStep.Configure }

                viewModel.perform(OnboardingAction.UsernameChanged("alice"))
                awaitStateWhere { it.configs["throwing-tracker"]?.username == "alice" }
                viewModel.perform(OnboardingAction.PasswordChanged("pw"))
                awaitStateWhere { it.configs["throwing-tracker"]?.password == "pw" }

                viewModel.perform(OnboardingAction.TestAndContinue)
                val errored = awaitStateWhere {
                    it.configs["throwing-tracker"]?.error != null
                }
                val cfg = errored.configs["throwing-tracker"]
                // PRIMARY: the thrown message is surfaced to the user.
                assertEquals(
                    "an exception during login MUST surface its message on the config",
                    "boom-connection-down",
                    cfg?.error,
                )
                assertEquals(
                    "an exception MUST keep the user on Configure",
                    OnboardingStep.Configure,
                    errored.step,
                )
                assertFalse(
                    "an exception MUST clear connectionTestRunning",
                    errored.connectionTestRunning,
                )
                cancelAndIgnoreRemainingItems()
            }
        }

    // ── G10: onFinish signals the username for a credentialed provider ────────
    //
    // FALSIFIABILITY: in onFinish, replace the credentialed `name = config.username`
    // branch with `name = "Anonymous (...)"` unconditionally. Observed: this test
    // FAILS — "a credentialed provider MUST sign in with the username" because the
    // recorded name is "Anonymous (...)". Reverted.
    @Test
    fun `finish signals authorized with the username for a credentialed provider`() =
        runTest(dispatcherRule.testDispatcher) {
            registerFormLoginTracker(loginState = AuthState.Authenticated)
            rebuildSdk()
            val viewModel = createViewModel(reachable = true)
            viewModel.test(this) {
                runOnCreate()
                expectInitialState()
                awaitState()

                viewModel.perform(OnboardingAction.NextStep) // → ApiSelection
                awaitStateWhere { it.step == OnboardingStep.ApiSelection }
                viewModel.perform(OnboardingAction.SelectApi(goApi()))
                awaitStateWhere { it.step == OnboardingStep.Providers }
                viewModel.perform(OnboardingAction.NextStep) // → Configure
                awaitStateWhere { it.step == OnboardingStep.Configure }
                viewModel.perform(OnboardingAction.UsernameChanged("alice"))
                awaitStateWhere { it.configs["form-login-tracker"]?.username == "alice" }
                viewModel.perform(OnboardingAction.PasswordChanged("s3cret"))
                awaitStateWhere { it.configs["form-login-tracker"]?.password == "s3cret" }
                viewModel.perform(OnboardingAction.TestAndContinue)
                awaitStateWhere { it.step == OnboardingStep.Summary }

                viewModel.perform(OnboardingAction.Finish)
                expectSideEffect(OnboardingSideEffect.Finish)
            }

            // PRIMARY user-visible outcome: the account surface (driven by
            // AuthService.signalAuthorized) carries the real username, not
            // "Anonymous (...)" — the credentialed-provider account label.
            assertTrue("user MUST be authorized after finishing", authService.isAuthorized())
            assertTrue(
                "a credentialed provider MUST sign in with the username 'alice'; was ${authService.lastAuthorizedName()}",
                authService.lastAuthorizedName() == "alice",
            )
        }
}

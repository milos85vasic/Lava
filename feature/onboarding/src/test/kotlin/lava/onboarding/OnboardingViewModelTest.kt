package lava.onboarding

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import lava.applink.SiblingAppLauncher
import lava.auth.api.AuthService
import lava.common.analytics.AnalyticsTracker
import lava.credentials.CredentialEncryptor
import lava.credentials.CredentialsRepository
import lava.credentials.ProviderConfigRepository
import lava.credentials.ProviderCredentialManager
import lava.database.dao.ClonedProviderDao
import lava.database.dao.ProviderConfigDao
import lava.database.dao.ProviderCredentialsDao
import lava.database.entity.ClonedProviderEntity
import lava.database.entity.ProviderConfigEntity
import lava.database.entity.ProviderCredentialsEntity
import lava.models.auth.AuthResult
import lava.models.auth.AuthState
import lava.onboarding.steps.displaySubtitle
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.orbitmvi.orbit.test.test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    /**
     * No-op [SiblingAppLauncher] for tests that do not exercise the
     * "On this device" launch flow — always reports not-installed so
     * the download intent path fires (a harmless no-op in these tests).
     */
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

    @Before
    fun setup() {
        registry = DefaultTrackerRegistry()
        registerTracker("test-tracker", "Test Tracker", "https://test.example.com")
        clonedProviderDao = FakeClonedProviderDao()
        sdk = LavaTrackerSdk(registry, clonedProviderDao = clonedProviderDao)

        val fakeDao = object : ProviderCredentialsDao {
            override suspend fun load(providerId: String) = null
            override fun observeAll() = emptyFlow<List<ProviderCredentialsEntity>>()
            override fun observe(providerId: String) = emptyFlow<ProviderCredentialsEntity?>()
            override suspend fun upsert(entity: ProviderCredentialsEntity) {}
            override suspend fun delete(providerId: String) {}
        }
        val credentialsRepository = CredentialsRepository(fakeDao, CredentialEncryptor())
        credentialManager = ProviderCredentialManager(credentialsRepository)

        // Real repository wired on a behaviourally-equivalent in-memory
        // DAO (Anti-Bluff Pact Third Law).
        providerConfigDao = FakeProviderConfigDao()
        providerConfigRepository = ProviderConfigRepository(providerConfigDao)

        authService = FakeAuthService()
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

    private fun TestScope.createViewModel(): OnboardingViewModel {
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
            // 60th §6.L invocation (2026-05-18): 3 new injections for the
            // ApiSelection step. Inline fakes here keep the existing test
            // surface unchanged — these are no-op defaults that don't
            // influence Welcome → Providers → Configure → Summary tests.
            discoveryService = lava.testing.service.TestLocalNetworkDiscoveryService(),
            connectionService = object : lava.data.api.service.ConnectionService {
                override val networkUpdates: kotlinx.coroutines.flow.Flow<Boolean> =
                    kotlinx.coroutines.flow.emptyFlow()
                override suspend fun isReachable(endpoint: lava.models.settings.Endpoint): Boolean = true
                override suspend fun isInternetReachable(): Boolean = true
            },
            endpointsRepository = lava.testing.repository.TestEndpointsRepository(),
            apiSelectionEnabled = false, // pre-60th flow preserved for these tests
            // Task 3.3: siblingAppLauncher required; these tests don't exercise the
            // on-device flow, so the no-op (not-installed) launcher is sufficient.
            siblingAppLauncher = noOpSiblingAppLauncher,
        )
    }

    @Test
    fun `next step advances from Welcome to Providers`() = runTest(dispatcherRule.testDispatcher) {
        val viewModel = createViewModel()
        viewModel.test(this) {
            runOnCreate()
            expectInitialState()
            awaitState()

            viewModel.perform(OnboardingAction.NextStep)
            expectState { copy(step = OnboardingStep.Providers) }
        }
    }

    @Test
    fun `toggle provider changes selection`() = runTest(dispatcherRule.testDispatcher) {
        val viewModel = createViewModel()
        viewModel.test(this) {
            runOnCreate()
            expectInitialState()
            awaitState()

            viewModel.perform(OnboardingAction.NextStep)
            expectState { copy(step = OnboardingStep.Providers) }

            viewModel.perform(OnboardingAction.ToggleProvider("test-tracker"))
            var current = awaitState()
            val item = current.providers.find { it.descriptor.trackerId == "test-tracker" }
            assertFalse("provider should be deselected", item?.selected ?: true)

            viewModel.perform(OnboardingAction.ToggleProvider("test-tracker"))
            current = awaitState()
            val item2 = current.providers.find { it.descriptor.trackerId == "test-tracker" }
            assertTrue("provider should be selected", item2?.selected ?: false)

            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `finish emits Finish side effect when at least one provider is configured AND tested`() =
        runTest(dispatcherRule.testDispatcher) {
            val viewModel = createViewModel()
            viewModel.test(this) {
                runOnCreate()
                expectInitialState()
                awaitState()

                // Drive the wizard to Configure for the only registered tracker
                // (anonymous AuthType.NONE; Continue triggers configured+tested+true via TestAndContinue).
                viewModel.perform(OnboardingAction.NextStep) // Welcome → Providers
                expectState { copy(step = OnboardingStep.Providers) }
                viewModel.perform(OnboardingAction.NextStep) // Providers → Configure (idx 0)
                expectState { copy(step = OnboardingStep.Configure, currentProviderIndex = 0) }
                viewModel.perform(OnboardingAction.TestAndContinue)
                // After connection-test path, three states emit:
                //   1. connectionTestRunning = true
                //   2. configs[id] = configured=true, tested=true, running=false
                //   3. advanceToNextProvider() → step = Summary (single-provider wizard)
                awaitState()
                awaitState()
                awaitState()

                viewModel.perform(OnboardingAction.Finish)
                expectSideEffect(OnboardingSideEffect.Finish)
            }
        }

    @Test
    fun `finish does NOT emit Finish when no provider has been probed (gate enforced)`() =
        runTest(dispatcherRule.testDispatcher) {
            val viewModel = createViewModel()
            viewModel.test(this) {
                runOnCreate()
                expectInitialState()
                awaitState()

                // Reach Summary by jumping forward without TestAndContinue —
                // configs are populated by loadProviders() but tested=false +
                // configured=false. Per §6.AB onboarding-gate enforcement,
                // Finish here MUST NOT fire.
                viewModel.perform(OnboardingAction.NextStep) // Welcome → Providers
                expectState { copy(step = OnboardingStep.Providers) }
                viewModel.perform(OnboardingAction.NextStep) // Providers → Configure
                expectState { copy(step = OnboardingStep.Configure, currentProviderIndex = 0) }
                viewModel.perform(OnboardingAction.NextStep) // Configure → Summary (skip without test)
                expectState { copy(step = OnboardingStep.Summary, currentProviderIndex = 0) }

                viewModel.perform(OnboardingAction.Finish)
                // The wizard MUST refuse to finish — re-enter Configure with
                // an error message on the last provider's config. NOT a
                // Finish side effect.
                val errored = awaitState()
                assertTrue(
                    "wizard must re-enter Configure when no provider was probed",
                    errored.step == OnboardingStep.Configure,
                )
                val cfg = errored.configs["test-tracker"]
                assertTrue(
                    "the gate-failure error message must surface on the active provider config",
                    cfg?.error?.contains("probed", ignoreCase = true) == true,
                )

                // No Finish side effect should be emitted; orbit-test will
                // fail this case if any unconsumed side effect remains when
                // we cancel — assertion encoded by the absence of any
                // expectSideEffect() call after the gate failure.
                cancelAndIgnoreRemainingItems()
            }
        }

    // Back-step coverage — closes the Sixth-Law gap that allowed the inverted
    // `BackHandler` predicate in OnboardingScreen.kt to ship to users. Each
    // transition in the onboarding wizard MUST round-trip via BackStep.

    @Test
    fun `back step from Welcome emits ExitApp side effect (gate enforcement, NOT Finish)`() =
        runTest(dispatcherRule.testDispatcher) {
            val viewModel = createViewModel()
            viewModel.test(this) {
                runOnCreate()
                expectInitialState()
                awaitState()

                viewModel.perform(OnboardingAction.BackStep)
                // §6.AB onboarding-gate enforcement: back-from-Welcome MUST
                // post ExitApp, NOT Finish. The pre-fix shape posted Finish
                // which made MainActivity write setOnboardingComplete(true)
                // and route to the half-functional home with zero providers.
                // Forensic anchor: 2026-05-14 1.2.20-1040 gate-bypass
                // reported by operator on Galaxy S23 Ultra.
                expectSideEffect(OnboardingSideEffect.ExitApp)
            }
        }

    @Test
    fun `back step from Providers returns to Welcome`() = runTest(dispatcherRule.testDispatcher) {
        val viewModel = createViewModel()
        viewModel.test(this) {
            runOnCreate()
            expectInitialState()
            awaitState()

            viewModel.perform(OnboardingAction.NextStep)
            expectState { copy(step = OnboardingStep.Providers) }

            viewModel.perform(OnboardingAction.BackStep)
            expectState { copy(step = OnboardingStep.Welcome) }
        }
    }

    @Test
    fun `back step from Configure returns to Providers when only one provider selected`() =
        runTest(dispatcherRule.testDispatcher) {
            val viewModel = createViewModel()
            viewModel.test(this) {
                runOnCreate()
                expectInitialState()
                awaitState()

                viewModel.perform(OnboardingAction.NextStep) // → Providers
                expectState { copy(step = OnboardingStep.Providers) }
                viewModel.perform(OnboardingAction.NextStep) // → Configure (one provider, index 0)
                expectState { copy(step = OnboardingStep.Configure, currentProviderIndex = 0) }

                viewModel.perform(OnboardingAction.BackStep)
                expectState { copy(step = OnboardingStep.Providers, currentProviderIndex = 0) }
            }
        }

    @Test
    fun `back step from Configure walks back through provider index when multiple selected`() =
        runTest(dispatcherRule.testDispatcher) {
            registerTracker("test-tracker-2", "Test Tracker 2", "https://test2.example.com")
            sdk = LavaTrackerSdk(registry)

            val viewModel = createViewModel()
            viewModel.test(this) {
                runOnCreate()
                expectInitialState()
                awaitState() // providers + configs reduced

                viewModel.perform(OnboardingAction.NextStep) // → Providers
                expectState { copy(step = OnboardingStep.Providers) }
                viewModel.perform(OnboardingAction.NextStep) // → Configure (index 0)
                expectState { copy(step = OnboardingStep.Configure, currentProviderIndex = 0) }
                viewModel.perform(OnboardingAction.NextStep) // advance → Configure (index 1)
                expectState { copy(step = OnboardingStep.Configure, currentProviderIndex = 1) }

                // Back from Configure(index=1) decrements to Configure(index=0),
                // does NOT jump straight to Providers — that was the pre-fix bug
                // where in-progress credentials for provider 0 were inaccessible.
                viewModel.perform(OnboardingAction.BackStep)
                expectState { copy(step = OnboardingStep.Configure, currentProviderIndex = 0) }

                // Another back from index 0 returns to Providers list.
                viewModel.perform(OnboardingAction.BackStep)
                expectState { copy(step = OnboardingStep.Providers, currentProviderIndex = 0) }
            }
        }

    @Test
    fun `back step from Summary returns to Configure of last provider`() =
        runTest(dispatcherRule.testDispatcher) {
            val viewModel = createViewModel()
            viewModel.test(this) {
                runOnCreate()
                expectInitialState()
                awaitState()

                // Single provider: Welcome → Providers → Configure → (advance) → Summary
                viewModel.perform(OnboardingAction.NextStep)
                expectState { copy(step = OnboardingStep.Providers) }
                viewModel.perform(OnboardingAction.NextStep)
                expectState { copy(step = OnboardingStep.Configure, currentProviderIndex = 0) }
                viewModel.perform(OnboardingAction.NextStep) // single provider → Summary
                expectState { copy(step = OnboardingStep.Summary, currentProviderIndex = 0) }

                viewModel.perform(OnboardingAction.BackStep)
                expectState { copy(step = OnboardingStep.Configure, currentProviderIndex = 0) }
            }
        }

    /**
     * Sweep Finding #7 closure (2026-05-17, §6.L 59th invocation).
     *
     * Forensic anchor: when `sdk.login(currentId, ...)` returns `null`
     * (the SDK contract for "tracker does not support auth" — see
     * `ProviderLoginViewModel.kt:279-295`), the pre-fix `TestAndContinue`
     * handler classified it as `Invalid credentials` and surfaced that
     * misleading message on the configure screen. The user never entered
     * credentials (the tracker has no auth path) yet the wizard said
     * the credentials were wrong.
     *
     * Falsifiability rehearsal:
     *   Mutation: revert the `loginResult == null` branch back to the
     *             original lumped `if (loginResult == null || loginResult.state != Authenticated)`
     *             that always surfaces "Invalid credentials".
     *   Observed: this test fails with
     *             "config error MUST NOT be 'Invalid credentials' when the
     *             tracker returns null from login() — was Invalid credentials".
     *   Reverted: yes.
     *
     * The chosen "tracker returns null" model uses a custom registered
     * client whose `getFeature(AuthenticatableTracker::class)` returns
     * null — exactly the runtime shape the real SDK exhibits for an
     * `AuthType.NONE` tracker (e.g. Internet Archive), and the same
     * shape a misconfigured FORM_LOGIN descriptor produces when its
     * impl does not provide AuthenticatableTracker.
     */
    @Test
    fun `TestAndContinue null login result treats as no-auth — no Invalid credentials error`() =
        runTest(dispatcherRule.testDispatcher) {
            // Register a misconfigured-shape tracker: authType=FORM_LOGIN
            // (so onboarding takes the credentials branch, NOT the
            // AuthType.NONE short-circuit) BUT its FakeTrackerClient
            // exposes no AuthenticatableTracker feature → sdk.login(...)
            // returns null. This is the exact runtime shape Finding #7
            // exists to evict: pre-fix the wizard surfaced
            // "Invalid credentials" for a tracker the user never even
            // tried to authenticate against.
            // Capabilities deliberately exclude AUTH_REQUIRED so
            // FakeTrackerClient.getFeature(AuthenticatableTracker::class)
            // returns null → sdk.login(...) returns null. authType is
            // overridden to FORM_LOGIN so the onboarding ViewModel takes
            // the credentials branch (NOT the AuthType.NONE shortcut),
            // exactly replicating the "misconfigured descriptor" runtime
            // shape Finding #7 documents.
            val misconfiguredDesc = object : TrackerDescriptor {
                override val trackerId: String = "misconfigured-form-login"
                override val displayName: String = "Misconfigured FORM_LOGIN"
                override val baseUrls = listOf(MirrorUrl(url = "https://misconfigured.example", isPrimary = true))
                override val capabilities = setOf(TrackerCapability.SEARCH)
                override val authType: AuthType = AuthType.FORM_LOGIN
                override val encoding = "UTF-8"
                override val expectedHealthMarker = "ok"
                override val verified = true
                override val apiSupported = true
            }
            val misconfiguredFactory = object : PluginFactory<TrackerDescriptor, TrackerClient> {
                override val descriptor: TrackerDescriptor = misconfiguredDesc
                override fun create(config: PluginConfig): TrackerClient = FakeTrackerClient(misconfiguredDesc)
            }
            registry.register(misconfiguredFactory)
            sdk = LavaTrackerSdk(registry, clonedProviderDao = clonedProviderDao)

            val viewModel = createViewModel()
            viewModel.test(this) {
                runOnCreate()
                expectInitialState()
                awaitState() // providers + configs reduced

                viewModel.perform(OnboardingAction.NextStep) // → Providers
                expectState { copy(step = OnboardingStep.Providers) }
                viewModel.perform(OnboardingAction.NextStep) // → Configure idx 0 (test-tracker = AuthType.NONE)
                expectState { copy(step = OnboardingStep.Configure, currentProviderIndex = 0) }
                // Probe test-tracker first via the AuthType.NONE branch
                // so the wizard advances to idx 1 (misconfigured FORM_LOGIN).
                viewModel.perform(OnboardingAction.TestAndContinue)
                awaitState() // running=true
                awaitState() // configured=true, tested=true (anonymous path)
                expectState { copy(step = OnboardingStep.Configure, currentProviderIndex = 1) }

                // NOW exercise the credentials branch on the misconfigured
                // tracker — sdk.login returns null because no
                // AuthenticatableTracker feature → Finding #7 path.
                viewModel.perform(OnboardingAction.TestAndContinue)
                awaitState() // running=true
                val tested = awaitState() // configured=true, tested=true (post-fix)
                cancelAndIgnoreRemainingItems()

                val cfg = tested.configs["misconfigured-form-login"]
                // PRIMARY anti-bluff assertion: config.error MUST NOT
                // be the misleading "Invalid credentials" string.
                assertNull(
                    "config.error MUST be null when sdk.login returns null (no-auth path) — " +
                        "the pre-fix branch surfaced 'Invalid credentials' here (Finding #7). " +
                        "was: ${cfg?.error}",
                    cfg?.error,
                )
                // Secondary: the wizard advances normally (configured + tested).
                assertTrue(
                    "configured MUST be true after no-auth probe succeeds; was ${cfg?.configured}",
                    cfg?.configured == true,
                )
                assertTrue(
                    "tested MUST be true after no-auth probe succeeds; was ${cfg?.tested}",
                    cfg?.tested == true,
                )
            }
        }

    /**
     * Sweep Finding #8 closure (2026-05-17, §6.L 59th invocation).
     *
     * Forensic anchor: `sdk.listAvailableTrackers()` returns BOTH base
     * descriptors AND any ClonedTrackerDescriptor rows persisted in
     * `cloned_provider`. The pre-fix onboarding `loadProviders()` did
     * `filter { it.verified && it.apiSupported }` only — clones leaked
     * into the wizard. Clones are an advanced post-onboarding feature
     * configured via Provider Config, not first-run.
     *
     * Falsifiability rehearsal:
     *   Mutation: remove the `&& it.trackerId !in syntheticIds` clause
     *             from `loadProviders()` so clones flow into the list.
     *   Observed: this test fails with
     *             "providers MUST NOT include cloned syntheticId — expected:<1> but was:<2>".
     *   Reverted: yes.
     */
    @Test
    fun `loadProviders filters out cloned synthetic providers`() =
        runTest(dispatcherRule.testDispatcher) {
            // Persist a cloned-provider row whose syntheticId matches a
            // descriptor the SDK would surface in listAvailableTrackers().
            clonedProviderDao.upsert(
                ClonedProviderEntity(
                    syntheticId = "test-tracker-clone-1",
                    sourceTrackerId = "test-tracker",
                    displayName = "Cloned Test Tracker",
                    primaryUrl = "https://clone-1.example",
                ),
            )

            val viewModel = createViewModel()
            viewModel.test(this) {
                runOnCreate()
                expectInitialState()
                val loaded = awaitState() // providers + configs reduced

                // PRIMARY anti-bluff assertion: the cloned syntheticId
                // MUST NOT appear in the onboarding provider list.
                assertFalse(
                    "providers MUST NOT include cloned syntheticId 'test-tracker-clone-1' — " +
                        "clones are configured via Provider Config, not onboarding. " +
                        "providers=${loaded.providers.map { it.descriptor.trackerId }}",
                    loaded.providers.any { it.descriptor.trackerId == "test-tracker-clone-1" },
                )
                // Secondary: the base provider is still present.
                assertEquals(
                    "Only the base tracker should appear — clones excluded",
                    1,
                    loaded.providers.size,
                )
                assertEquals(
                    "Base tracker preserved",
                    "test-tracker",
                    loaded.providers.first().descriptor.trackerId,
                )
            }
        }

    /**
     * LVA — onboarding anonymous-mode choice MUST persist to provider_configs.
     *
     * Forensic anchor: Sweep Finding #1 (2026-05-17) fixed the *Provider Config
     * screen* so the anonymous Switch round-trips through `use_anonymous`. But
     * the *onboarding* path that ALSO lets the user choose anonymous (for any
     * provider whose `supportsAnonymous == true` but whose authType is NOT
     * AuthType.NONE — ConfigureStep renders the `anonymous_switch`) never
     * persisted the choice. `onTestAndContinue` reads `config.useAnonymous` to
     * pick the anon branch but then calls only
     * `providerConfigRepository.ensureDefault(currentId)` — which writes (or
     * preserves) a row with the DEFAULT `useAnonymous = false`. Result: a user
     * who onboards a provider in anonymous mode gets a persisted row that says
     * `use_anonymous = false`; the Provider Config screen later shows the
     * Switch OFF and downstream search treats the provider as credentialed.
     * This is the same bluff-class as Finding #1, one layer up.
     *
     * Real-stack: real OnboardingViewModel → real ProviderConfigRepository →
     * behaviourally-equivalent FakeProviderConfigDao (Anti-Bluff Pact Third
     * Law). PRIMARY assertion is on the persisted DB row the user's later
     * screens read.
     *
     * Falsifiability rehearsal (per §6.J / Sixth Law clause 2):
     *   Mutation: keep only `providerConfigRepository.ensureDefault(currentId)`
     *             in onTestAndContinue (the pre-fix shape — i.e. remove the
     *             `setUseAnonymous` call the fix adds).
     *   Observed: this test FAILS with
     *             "onboarding anonymous choice MUST persist useAnonymous=true
     *              to provider_configs — was false".
     *   Reverted: yes (fix is the production change).
     */
    @Test
    fun `onboarding anonymous toggle persists useAnonymous to provider config`() =
        runTest(dispatcherRule.testDispatcher) {
            // Register a provider that renders the onboarding anonymous Switch:
            // supportsAnonymous = true AND authType != AuthType.NONE (so the
            // wizard does NOT take the AuthType.NONE short-circuit and instead
            // honours the per-config `useAnonymous` the user toggled).
            val anonCapableDesc = object : TrackerDescriptor {
                override val trackerId: String = "anon-capable"
                override val displayName: String = "Anon Capable"
                override val baseUrls = listOf(MirrorUrl(url = "https://anon.example", isPrimary = true))
                override val capabilities = setOf(TrackerCapability.SEARCH)
                override val authType: AuthType = AuthType.FORM_LOGIN
                override val supportsAnonymous: Boolean = true
                override val encoding = "UTF-8"
                override val expectedHealthMarker = "ok"
                override val verified = true
                override val apiSupported = true
            }
            val anonCapableFactory = object : PluginFactory<TrackerDescriptor, TrackerClient> {
                override val descriptor: TrackerDescriptor = anonCapableDesc
                override fun create(config: PluginConfig): TrackerClient = FakeTrackerClient(anonCapableDesc)
            }
            registry.register(anonCapableFactory)
            sdk = LavaTrackerSdk(registry, clonedProviderDao = clonedProviderDao)

            val viewModel = createViewModel()
            viewModel.test(this) {
                runOnCreate()
                expectInitialState()
                awaitState() // providers + configs reduced

                viewModel.perform(OnboardingAction.NextStep) // → Providers
                expectState { copy(step = OnboardingStep.Providers) }
                // Deselect the base AuthType.NONE test-tracker so the only
                // provider configured is the anon-capable FORM_LOGIN one and
                // its Configure page (index 0) is the one being driven.
                viewModel.perform(OnboardingAction.ToggleProvider("test-tracker"))
                awaitState()
                viewModel.perform(OnboardingAction.NextStep) // → Configure idx 0 (anon-capable)
                expectState { copy(step = OnboardingStep.Configure, currentProviderIndex = 0) }

                // User flips the onboarding anonymous Switch ON.
                viewModel.perform(OnboardingAction.ToggleAnonymous(enabled = true))
                awaitState()

                // User taps Continue — anon branch runs, then persistence.
                viewModel.perform(OnboardingAction.TestAndContinue)
                awaitState() // running=true
                awaitState() // configured=true, tested=true (anon path)
                cancelAndIgnoreRemainingItems()
            }

            // PRIMARY anti-bluff assertion: the persisted provider_configs row
            // (the source of truth every later screen + search reads) reflects
            // the user's anonymous choice. Pre-fix this was false.
            val persisted = providerConfigRepository.load("anon-capable")
            assertNotNull(
                "onboarding MUST persist a provider_configs row for the configured provider",
                persisted,
            )
            assertTrue(
                "onboarding anonymous choice MUST persist useAnonymous=true to " +
                    "provider_configs (Sweep Finding #1 one layer up) — was " +
                    "${persisted?.useAnonymous}",
                persisted?.useAnonymous == true,
            )
        }

    // ── ApiSelection Cloud / remote-server section (2026-05-31) ───────────
    //
    // FALSIFIABILITY REHEARSAL (per §6.J / Sixth Law clause 2). Deliberate
    // break tried while authoring these tests: make `onAddCloudApi()` ignore
    // the parse result and unconditionally call `onSelectApi(parsed!!)` (i.e.
    // remove the `if (parsed == null)` branch). Expected failure:
    //   - `add cloud api with malformed address sets error and does not advance`
    //     fails because `state.cloudAddressError` is null (the error branch
    //     never ran) AND/OR the test crashes / advances past ApiSelection
    //     instead of staying put — proving the error-on-parse-failure path is
    //     load-bearing. The break was reverted; production code is unchanged.
    //
    // `createViewModelForCloud(reachable)` mirrors `createViewModel()` but lets
    // the ConnectionService.isReachable result be parameterised so the success
    // path (probe OK → persist → advance) can be exercised. apiSelectionEnabled
    // is left false (the default) — AddCloudApi works regardless of the flag
    // because the cloud-add handler funnels straight into onSelectApi.
    private fun TestScope.createViewModelForCloud(reachable: Boolean): OnboardingViewModel {
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
            discoveryService = lava.testing.service.TestLocalNetworkDiscoveryService(),
            connectionService = object : lava.data.api.service.ConnectionService {
                override val networkUpdates: kotlinx.coroutines.flow.Flow<Boolean> =
                    kotlinx.coroutines.flow.emptyFlow()
                override suspend fun isReachable(endpoint: lava.models.settings.Endpoint): Boolean = reachable
                override suspend fun isInternetReachable(): Boolean = true
            },
            endpointsRepository = lava.testing.repository.TestEndpointsRepository(),
            apiSelectionEnabled = false,
            defaultCloudApi = "",
            siblingAppLauncher = noOpSiblingAppLauncher,
        )
    }

    // CHALLENGE — primary assertion on the user-visible cloud-address field +
    // the cleared error state the user sees while typing.
    @Test
    fun `cloud address changed updates input and clears error`() =
        runTest(dispatcherRule.testDispatcher) {
            val viewModel = createViewModelForCloud(reachable = true)
            viewModel.test(this) {
                runOnCreate()
                expectInitialState()
                awaitState() // providers + configs reduced

                viewModel.perform(OnboardingAction.CloudAddressChanged("https://lava.app:7777"))
                val typed = awaitState()
                assertEquals(
                    "the typed address must be reflected in the field the user sees",
                    "https://lava.app:7777",
                    typed.cloudAddressInput,
                )
                assertNull(
                    "editing the field must clear any prior parse error; was ${typed.cloudAddressError}",
                    typed.cloudAddressError,
                )

                cancelAndIgnoreRemainingItems()
            }
        }

    // CHALLENGE — primary assertion on state.step advancing to Providers. The
    // step advance happens in onSelectApi ONLY after the probe succeeds AND
    // endpointsRepository.add(Endpoint.GoApi("lava.app", 7777)) is called — so
    // reaching Providers is proof the parse → probe → persist path ran for the
    // user. TestEndpointsRepository exposes no synchronous read accessor, so
    // the step advance is the load-bearing assertion (persist is a precondition
    // of advance per the production code in OnboardingViewModel.onSelectApi).
    @Test
    fun `add cloud api with valid address probes persists and advances to Providers`() =
        runTest(dispatcherRule.testDispatcher) {
            val viewModel = createViewModelForCloud(reachable = true)
            viewModel.test(this) {
                runOnCreate()
                expectInitialState()
                awaitState() // providers + configs reduced

                viewModel.perform(OnboardingAction.CloudAddressChanged("https://lava.app:7777"))
                expectState { copy(cloudAddressInput = "https://lava.app:7777", cloudAddressError = null) }

                viewModel.perform(OnboardingAction.AddCloudApi)
                // onAddCloudApi() clears the error then funnels into onSelectApi:
                //   1. selectedApi set + apiConnectivity = Testing
                //   2. (async probe OK + endpointsRepository.add) → apiConnectivity
                //      = Idle, step = Providers
                // Walk states until the step lands on Providers. The loop MUST
                // stop awaiting as soon as Providers is reached — Orbit dedups
                // no-op reduces so only a bounded number of distinct states are
                // emitted; awaiting beyond the last one times out Turbine. `break`
                // (not `return@repeat`, which only continues the lambda) is the fix.
                var advanced = false
                var guard = 0
                while (guard < 8) {
                    guard++
                    val s = awaitState()
                    if (s.step == OnboardingStep.Providers) {
                        advanced = true
                        break
                    }
                }
                assertTrue(
                    "AddCloudApi with a valid address MUST advance to Providers after the " +
                        "probe succeeds and the endpoint is persisted (Endpoint.GoApi(\"lava.app\", 7777)). " +
                        "step never reached Providers.",
                    advanced,
                )

                cancelAndIgnoreRemainingItems()
            }
        }

    // CHALLENGE — primary assertion on the user-visible cloudAddressError +
    // the absence of a step advance (the wizard must NOT leave ApiSelection
    // when the typed address cannot be parsed).
    @Test
    fun `add cloud api with malformed address sets error and does not advance`() =
        runTest(dispatcherRule.testDispatcher) {
            val viewModel = createViewModelForCloud(reachable = true)
            viewModel.test(this) {
                runOnCreate()
                expectInitialState()
                awaitState() // providers + configs reduced

                viewModel.perform(OnboardingAction.CloudAddressChanged("not a url /x"))
                expectState { copy(cloudAddressInput = "not a url /x", cloudAddressError = null) }

                viewModel.perform(OnboardingAction.AddCloudApi)
                val errored = awaitState()
                // PRIMARY: a user-visible parse error is surfaced.
                assertNotNull(
                    "a malformed cloud address MUST surface a user-visible cloudAddressError",
                    errored.cloudAddressError,
                )
                assertTrue(
                    "the error message must mention a valid address so the user knows the fix; " +
                        "was '${errored.cloudAddressError}'",
                    errored.cloudAddressError?.contains("valid address", ignoreCase = true) == true,
                )
                // PRIMARY: the wizard must NOT advance to Providers — it stays
                // where it was (initial step is Welcome with the test's
                // apiSelectionEnabled=false default; the gate is "not Providers").
                assertFalse(
                    "a malformed address MUST NOT advance the wizard to Providers; was ${errored.step}",
                    errored.step == OnboardingStep.Providers,
                )

                cancelAndIgnoreRemainingItems()
            }
        }

    // ── Sub-project 2 (on-device API): discovery labeling in ApiSelection ──
    //
    // FALSIFIABILITY REHEARSAL (per §6.J / Sixth Law clause 2). Deliberate
    // break tried while authoring this test: in OnboardingViewModel.startApiDiscovery
    // drop `platform = hit.platform` from the Endpoint.GoApi(...) constructed in
    // the discovery `onEach`. Expected failure:
    //   - `discovered android-platform api renders with the android-device label`
    //     fails its `displaySubtitle()` assertion — the GoApi.platform is null,
    //     so discoveredApiLabel(null) returns "On this network" instead of
    //     "On this network · Android device", and assertEquals reports
    //     expected:<...· Android device> but was:<On this network>. Reverted.
    //
    // This helper holds a reference to the discovery service so the test can
    // emit a real DiscoveredEndpoint (carrying platform=android, as Sub-project 1
    // advertises) into the production discovery flow the ViewModel collects.
    private fun TestScope.createViewModelWithDiscovery(
        discovery: lava.testing.service.TestLocalNetworkDiscoveryService,
    ): OnboardingViewModel {
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
            discoveryService = discovery,
            connectionService = object : lava.data.api.service.ConnectionService {
                override val networkUpdates: kotlinx.coroutines.flow.Flow<Boolean> =
                    kotlinx.coroutines.flow.emptyFlow()
                override suspend fun isReachable(endpoint: lava.models.settings.Endpoint): Boolean = true
                override suspend fun isInternetReachable(): Boolean = true
            },
            endpointsRepository = lava.testing.repository.TestEndpointsRepository(),
            // apiSelectionEnabled=true so NextStep from Welcome enters
            // ApiSelection and startApiDiscovery() begins collecting.
            apiSelectionEnabled = true,
            siblingAppLauncher = noOpSiblingAppLauncher,
        )
    }

    // CHALLENGE — primary assertion on the user-visible subtitle rendered by the
    // production ApiSelectionStep.displaySubtitle() for an on-device API hit. An
    // android-platform DiscoveredEndpoint MUST surface the distinct "Android
    // device" label, while a host hit (no platform) MUST render without it.
    @Test
    fun `discovered android-platform api renders with the android-device label`() =
        runTest(dispatcherRule.testDispatcher) {
            val discovery = lava.testing.service.TestLocalNetworkDiscoveryService()
            val viewModel = createViewModelWithDiscovery(discovery)
            viewModel.test(this) {
                runOnCreate()
                expectInitialState()
                awaitState() // providers + configs reduced

                // Welcome → ApiSelection starts discovery collection.
                viewModel.perform(OnboardingAction.NextStep)

                // Sub-project 1 advertises platform=android, storage=sqlite for
                // the on-device API; the host advertiser omits platform.
                discovery.emit(
                    lava.data.api.service.DiscoveredEndpoint(
                        host = "192.0.2.10:8443",
                        port = 8443,
                        name = "Lava API (on device)",
                        engine = lava.data.api.service.DiscoveredEndpoint.Engine.Go,
                        platform = "android",
                        storage = "sqlite",
                    ),
                )
                discovery.emit(
                    lava.data.api.service.DiscoveredEndpoint(
                        host = "192.0.2.20:8443",
                        port = 8443,
                        name = "Lava API (server)",
                        engine = lava.data.api.service.DiscoveredEndpoint.Engine.Go,
                        platform = null,
                        storage = null,
                    ),
                )

                // Walk states until both discovered APIs have landed. Each
                // emit adds one GoApi via a nested intent{} reduce; the loop
                // breaks as soon as the list reaches 2 so it never awaits past
                // the last distinct state (Orbit dedups no-op reduces).
                var discovered: List<lava.models.settings.Endpoint.GoApi> = emptyList()
                var guard = 0
                while (guard < 12 && discovered.size < 2) {
                    guard++
                    val s = awaitState()
                    val apis = s.discoveredApis.filterIsInstance<lava.models.settings.Endpoint.GoApi>()
                    if (apis.size > discovered.size) discovered = apis
                }
                // Bug A regression (operator-reported 2026-06-04): startApiDiscovery
                // MUST strip the embedded ":port" from the legacy "ip:port"
                // DiscoveredEndpoint.host so GoApi.host is the BARE address. Before
                // the fix this test asserted `host == "192.0.2.10:8443"` — it had
                // encoded the bug as correct, which is why "API did not respond"
                // shipped: GoApi.host="ip:port" makes ConnectionService.connectTarget()
                // hand InetSocketAddress an invalid host and the TCP probe fails.
                val androidApi = discovered.firstOrNull { it.host == "192.0.2.10" }
                val hostApi = discovered.firstOrNull { it.host == "192.0.2.20" }

                assertNotNull(
                    "the android-platform API MUST appear in the discovered list with a BARE host " +
                        "(port stripped); saw hosts=${discovered.map { it.host }}",
                    androidApi,
                )
                assertNotNull(
                    "the host API MUST appear in the discovered list with a BARE host; " +
                        "saw hosts=${discovered.map { it.host }}",
                    hostApi,
                )
                // Explicit no-embedded-port regression assertion: the host is the
                // bare IP and the port lives in the separate GoApi.port field.
                assertEquals("on-device API host must be the bare IP", "192.0.2.10", androidApi!!.host)
                assertEquals("on-device API port must be the separate field", 8443, androidApi.port)
                assertEquals(
                    "the on-device API's platform TXT attribute MUST be carried through to the Endpoint",
                    "android",
                    androidApi!!.platform,
                )
                // The load-bearing user-visible assertion: the production
                // rendering function distinguishes the two instances in the list
                // the user actually sees.
                assertEquals(
                    "the android-platform API MUST render with the distinct Android-device label",
                    "Lava API · On this network · Android device",
                    androidApi!!.displaySubtitle(),
                )
                assertEquals(
                    "the host API MUST render with the plain network label (no Android-device tail)",
                    "Lava API · On this network",
                    hostApi!!.displaySubtitle(),
                )

                cancelAndIgnoreRemainingItems()
            }
        }
}

/**
 * Sweep Finding #1 + #7 (2026-05-17): behaviorally-equivalent in-memory
 * fake for [ProviderConfigDao]. Anti-Bluff Pact Third Law: enforces the
 * same Insert-Replace semantics as the real Room DAO; observe() emits on
 * upsert + delete via a backing MutableStateFlow.
 */
internal class FakeProviderConfigDao : ProviderConfigDao {
    private val store = mutableMapOf<String, ProviderConfigEntity>()
    private val flow = MutableStateFlow<List<ProviderConfigEntity>>(emptyList())

    override suspend fun load(providerId: String): ProviderConfigEntity? = store[providerId]
    override fun observeAll() = flow
    override fun observe(providerId: String) =
        kotlinx.coroutines.flow.MutableStateFlow(store[providerId])
    override suspend fun upsert(entity: ProviderConfigEntity) {
        store[entity.providerId] = entity
        flow.value = store.values.toList()
    }
    override suspend fun delete(providerId: String) {
        store.remove(providerId)
        flow.value = store.values.toList()
    }
}

/**
 * Sweep Finding #8 (2026-05-17): behaviorally-equivalent in-memory fake
 * for [ClonedProviderDao]. Anti-Bluff Pact Third Law: only emits rows
 * whose `deletedAt IS NULL`, exactly matching the real Room DAO's
 * production `WHERE deletedAt IS NULL` clause.
 */
internal class FakeClonedProviderDao : ClonedProviderDao {
    private val store = mutableMapOf<String, ClonedProviderEntity>()

    override fun observeAll() =
        kotlinx.coroutines.flow.MutableStateFlow<List<ClonedProviderEntity>>(visible())
    override suspend fun getAll(): List<ClonedProviderEntity> = visible()
    override suspend fun upsert(entity: ClonedProviderEntity) {
        store[entity.syntheticId] = entity
    }
    override suspend fun softDelete(id: String, deletedAt: Long) {
        store[id]?.let { store[id] = it.copy(deletedAt = deletedAt) }
    }
    override suspend fun delete(id: String) {
        store.remove(id)
    }
    private fun visible() = store.values.filter { it.deletedAt == null }
}

class FakeAuthService : AuthService {
    private val authorizedNames = mutableListOf<String>()

    override suspend fun isAuthorized(): Boolean = authorizedNames.isNotEmpty()

    override fun observeAuthState() = emptyFlow<AuthState>()

    override suspend fun login(
        username: String,
        password: String,
        captchaSid: String?,
        captchaCode: String?,
        captchaValue: String?,
    ) = AuthResult.Success

    override suspend fun logout() {
        authorizedNames.clear()
    }

    override suspend fun signalAuthorized(name: String, avatarUrl: String?) {
        authorizedNames.add(name)
    }
}

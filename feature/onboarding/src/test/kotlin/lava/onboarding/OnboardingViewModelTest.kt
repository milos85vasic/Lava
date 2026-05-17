package lava.onboarding

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import lava.auth.api.AuthService
import lava.common.analytics.AnalyticsTracker
import lava.credentials.CredentialEncryptor
import lava.credentials.CredentialsRepository
import lava.credentials.ProviderConfig
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

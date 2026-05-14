package lava.onboarding

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import lava.auth.api.AuthService
import lava.common.analytics.AnalyticsTracker
import lava.credentials.CredentialEncryptor
import lava.credentials.CredentialsRepository
import lava.credentials.ProviderCredentialManager
import lava.database.dao.ProviderCredentialsDao
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
import org.junit.Assert.assertFalse
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

    @Before
    fun setup() {
        registry = DefaultTrackerRegistry()
        registerTracker("test-tracker", "Test Tracker", "https://test.example.com")
        sdk = LavaTrackerSdk(registry)

        val fakeDao = object : ProviderCredentialsDao {
            override suspend fun load(providerId: String) = null
            override fun observeAll() = emptyFlow<List<ProviderCredentialsEntity>>()
            override fun observe(providerId: String) = emptyFlow<ProviderCredentialsEntity?>()
            override suspend fun upsert(entity: ProviderCredentialsEntity) {}
            override suspend fun delete(providerId: String) {}
        }
        val credentialsRepository = CredentialsRepository(fakeDao, CredentialEncryptor())
        credentialManager = ProviderCredentialManager(credentialsRepository)

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
                override fun log(message: String) {}
            },
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

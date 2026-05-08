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

        val trackerDescriptor = object : TrackerDescriptor {
            override val trackerId: String = "test-tracker"
            override val displayName: String = "Test Tracker"
            override val baseUrls: List<MirrorUrl> = listOf(
                MirrorUrl(url = "https://test.example.com", isPrimary = true),
            )
            override val capabilities: Set<TrackerCapability> = setOf(TrackerCapability.SEARCH)
            override val authType: AuthType = AuthType.NONE
            override val encoding: String = "UTF-8"
            override val expectedHealthMarker: String = "test"
            override val verified: Boolean = true
            override val apiSupported: Boolean = true
        }

        val factory = object : PluginFactory<TrackerDescriptor, TrackerClient> {
            override val descriptor: TrackerDescriptor = trackerDescriptor
            override fun create(config: PluginConfig): TrackerClient = FakeTrackerClient(trackerDescriptor)
        }

        registry.register(factory)
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
    fun `finish emits Finish side effect`() = runTest(dispatcherRule.testDispatcher) {
        val viewModel = createViewModel()
        viewModel.test(this) {
            runOnCreate()
            expectInitialState()
            awaitState()

            viewModel.perform(OnboardingAction.Finish)
            expectSideEffect(OnboardingSideEffect.Finish)
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

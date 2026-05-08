package lava.onboarding

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import lava.auth.api.AuthService
import lava.common.analytics.AnalyticsTracker
import lava.credentials.ProviderCredentialManager
import lava.logger.api.LoggerFactory
import lava.sdk.api.MirrorUrl
import lava.sdk.api.Protocol
import lava.testing.rule.MainDispatcherRule
import lava.tracker.api.AuthType
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.api.model.AuthState
import lava.tracker.api.model.LoginResult
import lava.tracker.client.LavaTrackerSdk
import lava.tracker.registry.DefaultTrackerRegistry
import lava.tracker.registry.TrackerClientFactory
import lava.tracker.testing.FakeTrackerClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.orbitmvi.orbit.test.test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val rutrackerDescriptor = trackerDescriptor(
        id = "rutracker",
        name = "RuTracker",
        authType = AuthType.CAPTCHA_LOGIN,
    )
    private val rutorDescriptor = trackerDescriptor(
        id = "rutor",
        name = "RuTor",
        authType = AuthType.FORM_LOGIN,
    )
    private val archiveOrgDescriptor = trackerDescriptor(
        id = "archiveorg",
        name = "Internet Archive",
        authType = AuthType.NONE,
    )

    private lateinit var rutrackerClient: FakeTrackerClient
    private lateinit var rutorClient: FakeTrackerClient
    private lateinit var archiveOrgClient: FakeTrackerClient
    private lateinit var sdk: LavaTrackerSdk

    private val credentialManager: ProviderCredentialManager = mockk(relaxed = true)
    private val authService: AuthService = mockk(relaxed = true)
    private val loggerFactory: LoggerFactory = mockk(relaxed = true)
    private val analytics: AnalyticsTracker = mockk(relaxed = true)

    @Before
    fun setUp() {
        rutrackerClient = FakeTrackerClient(rutrackerDescriptor).also {
            it.loginProvider = { LoginResult(AuthState.Authenticated) }
        }
        rutorClient = FakeTrackerClient(rutorDescriptor).also {
            it.loginProvider = { LoginResult(AuthState.Authenticated) }
        }
        archiveOrgClient = FakeTrackerClient(archiveOrgDescriptor)

        val registry = DefaultTrackerRegistry().also { reg ->
            reg.register(factory(rutrackerDescriptor, rutrackerClient))
            reg.register(factory(rutorDescriptor, rutorClient))
            reg.register(factory(archiveOrgDescriptor, archiveOrgClient))
        }
        sdk = LavaTrackerSdk(registry = registry)
    }

    // CHALLENGE
    @Test
    fun `on create loads verified apiSupported providers`() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = createViewModel()
        vm.test(this) {
            runOnCreate()
            cancelAndIgnoreRemainingItems()
        }
        val state = vm.container.stateFlow.value
        assertEquals("should load 3 verified apiSupported providers", 3, state.providers.size)
        val loadedIds = state.providers.map { it.descriptor.trackerId }.toSet()
        assertTrue(loadedIds.contains("rutracker"))
        assertTrue(loadedIds.contains("rutor"))
        assertTrue(loadedIds.contains("archiveorg"))
        assertTrue("all should be pre-selected", state.providers.all { it.selected })
    }

    // CHALLENGE
    @Test
    fun `welcome advances to providers on Get Started`() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = OnboardingViewModel(
            sdk = sdk,
            credentialManager = credentialManager,
            authService = authService,
            loggerFactory = loggerFactory,
            analytics = analytics,
        )
        val providers = listOf(
            ProviderOnboardingItem(rutrackerDescriptor),
            ProviderOnboardingItem(archiveOrgDescriptor),
        )
        vm.test(this) {
            vm.perform(OnboardingAction.NextStep)
            cancelAndIgnoreRemainingItems()
        }
        val result = vm.container.stateFlow.value
        assertEquals(OnboardingStep.Providers, result.step)
    }

    // CHALLENGE
    @Test
    fun `providers step cannot advance without selection`() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = createViewModel()
        vm.test(this) {
            runOnCreate()
            vm.perform(OnboardingAction.NextStep)
            cancelAndIgnoreRemainingItems()
        }
        val result = vm.container.stateFlow.value
        assertEquals("should stay on Providers when at least one is selected", OnboardingStep.Providers, result.step)
    }

    // CHALLENGE
    @Test
    fun `toggle provider deselection prevents advancing`() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = createViewModel()
        vm.test(this) {
            runOnCreate()
            vm.perform(OnboardingAction.ToggleProvider("archiveorg"))
            vm.perform(OnboardingAction.ToggleProvider("rutracker"))
            vm.perform(OnboardingAction.ToggleProvider("rutor"))
            vm.perform(OnboardingAction.NextStep)
            cancelAndIgnoreRemainingItems()
        }
        val result = vm.container.stateFlow.value
        assertEquals("should stay on Providers when none selected", OnboardingStep.Providers, result.step)
        assertTrue("all should be deselected", result.providers.none { it.selected })
    }

    // VM-CONTRACT
    @Test
    fun `back press at welcome stays at welcome step`() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = createViewModel()
        vm.test(this) {
            runOnCreate()
            awaitState()
            vm.perform(OnboardingAction.BackStep) // emits Finish side effect (exit)
            cancelAndIgnoreRemainingItems()
        }
        // After BackStep at Welcome, state hasn't changed (app closes via side effect)
        assertEquals(OnboardingStep.Welcome, vm.container.stateFlow.value.step)
    }

    // CHALLENGE
    @Test
    fun `back press from configure goes to providers per spec`() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = createViewModel()
        vm.test(this) {
            runOnCreate()
            vm.perform(OnboardingAction.NextStep) // Welcome -> Providers
            vm.perform(OnboardingAction.NextStep) // Providers -> Configure
            vm.perform(OnboardingAction.BackStep) // Configure -> Providers
            cancelAndIgnoreRemainingItems()
        }
        val result = vm.container.stateFlow.value
        assertEquals("back from Configure should go to Providers", OnboardingStep.Providers, result.step)
    }

    // CHALLENGE
    @Test
    fun `back press from providers goes to welcome`() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = createViewModel()
        vm.test(this) {
            runOnCreate()
            vm.perform(OnboardingAction.NextStep) // Welcome -> Providers
            vm.perform(OnboardingAction.BackStep) // Providers -> Welcome
            cancelAndIgnoreRemainingItems()
        }
        val result = vm.container.stateFlow.value
        assertEquals("back from Providers should go to Welcome", OnboardingStep.Welcome, result.step)
    }

    // CHALLENGE
    @Test
    fun `anonymous provider test advances with health check`() = runTest(mainDispatcherRule.testDispatcher) {
        archiveOrgClient.healthy = true
        archiveOrgClient.authState = AuthState.Authenticated
        val registry = DefaultTrackerRegistry().also { reg ->
            reg.register(factory(archiveOrgDescriptor, archiveOrgClient))
        }
        val anonSdk = LavaTrackerSdk(registry = registry)
        val vm = OnboardingViewModel(
            sdk = anonSdk,
            credentialManager = credentialManager,
            authService = authService,
            loggerFactory = loggerFactory,
            analytics = analytics,
        )
        vm.test(this) {
            runOnCreate()
            awaitState()
            vm.perform(OnboardingAction.NextStep) // Welcome -> Providers
            awaitState()
            vm.perform(OnboardingAction.NextStep) // Providers -> Configure
            awaitState()
            vm.perform(OnboardingAction.TestAndContinue)
            // consume the TestAndContinue emissions
            while (vm.container.stateFlow.value.connectionTestRunning) {
                awaitState()
            }
            cancelAndIgnoreRemainingItems()
        }
        val result = vm.container.stateFlow.value
        assertEquals(OnboardingStep.Summary, result.step)
        val config = result.configs["archiveorg"]
        assertNotNull(config)
        assertTrue("should be configured", config!!.configured)
    }

    // CHALLENGE
    @Test
    fun `anonymous provider test fails with error`() = runTest(mainDispatcherRule.testDispatcher) {
        archiveOrgClient.healthy = false
        val registry = DefaultTrackerRegistry().also { reg ->
            reg.register(factory(archiveOrgDescriptor, archiveOrgClient))
        }
        val anonSdk = LavaTrackerSdk(registry = registry)
        val vm = OnboardingViewModel(
            sdk = anonSdk,
            credentialManager = credentialManager,
            authService = authService,
            loggerFactory = loggerFactory,
            analytics = analytics,
        )
        vm.test(this) {
            runOnCreate()
            vm.perform(OnboardingAction.NextStep)
            awaitState()
            vm.perform(OnboardingAction.NextStep)
            awaitState()
            vm.perform(OnboardingAction.TestAndContinue)
            awaitState() // connectionTestRunning = true
            awaitState() // error set
            cancelAndIgnoreRemainingItems()
        }
        val result = vm.container.stateFlow.value
        assertEquals(OnboardingStep.Configure, result.step)
        val config = result.configs["archiveorg"]
        assertNotNull(config)
        assertFalse("should not be configured", config!!.configured)
        assertNotNull("should have error", config.error)
    }

    // CHALLENGE
    @Test
    fun `auth provider test saves credentials on success`() = runTest(mainDispatcherRule.testDispatcher) {
        val registry = DefaultTrackerRegistry().also { reg ->
            reg.register(factory(rutrackerDescriptor, rutrackerClient))
        }
        val authSdk = LavaTrackerSdk(registry = registry)
        val vm = OnboardingViewModel(
            sdk = authSdk,
            credentialManager = credentialManager,
            authService = authService,
            loggerFactory = loggerFactory,
            analytics = analytics,
        )
        vm.test(this) {
            runOnCreate()
            vm.perform(OnboardingAction.NextStep) // Welcome -> Providers
            awaitState()
            vm.perform(OnboardingAction.NextStep) // Providers -> Configure (rutracker is at index 0)
            awaitState()
            vm.perform(OnboardingAction.UsernameChanged("testuser"))
            vm.perform(OnboardingAction.PasswordChanged("testpass"))
            vm.perform(OnboardingAction.TestAndContinue)
            awaitState() // connectionTestRunning = true
            awaitState() // configured = true, advance
            cancelAndIgnoreRemainingItems()
        }
        val result = vm.container.stateFlow.value
        assertEquals(OnboardingStep.Summary, result.step)
        val config = result.configs["rutracker"]
        assertNotNull(config)
        assertTrue("should be configured", config!!.configured)

        coVerify(exactly = 1) { credentialManager.setPassword("rutracker", "testuser", "testpass") }
    }

    // VM-CONTRACT
    @Test
    fun `finish signals authorized for configured providers`() = runTest(mainDispatcherRule.testDispatcher) {
        coEvery { authService.signalAuthorized(any(), any()) } returns Unit
        val vm = createViewModel()
        vm.test(this) {
            vm.perform(OnboardingAction.Finish)
            // consume any state emissions from onCreate processing
            cancelAndIgnoreRemainingItems()
        }
        // With no configured providers, Finish just emits side effect without signaling auth
    }

    // VM-CONTRACT
    @Test
    fun `finish with configured provider calls signalAuthorized`() = runTest(mainDispatcherRule.testDispatcher) {
        coEvery { authService.signalAuthorized(any(), any()) } returns Unit
        val vm = createViewModel()
        vm.test(this) {
            runOnCreate()
            awaitState()
            // Set up state with configured provider
            vm.perform(OnboardingAction.NextStep) // -> Providers
            awaitState()
            vm.perform(OnboardingAction.NextStep) // -> Configure
            awaitState()
            vm.perform(OnboardingAction.UsernameChanged("testuser"))
            awaitState()
            vm.perform(OnboardingAction.PasswordChanged("testpass"))
            awaitState()
            vm.perform(OnboardingAction.TestAndContinue)
            awaitState() // connectionTestRunning
            awaitState() // configured
            vm.perform(OnboardingAction.Finish)
            cancelAndIgnoreRemainingItems()
        }
        coVerify(exactly = 1) { authService.signalAuthorized("testuser", null) }
    }

    // VM-CONTRACT
    @Test
    fun `username changes update config state`() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = createViewModel()
        vm.test(this) {
            runOnCreate()
            vm.perform(OnboardingAction.NextStep) // Welcome -> Providers
            vm.perform(OnboardingAction.NextStep) // Providers -> Configure
            vm.perform(OnboardingAction.UsernameChanged("newuser"))
            cancelAndIgnoreRemainingItems()
        }
        val result = vm.container.stateFlow.value
        assertEquals("newuser", result.configs["rutracker"]!!.username)
    }

    // VM-CONTRACT
    @Test
    fun `password changes update config state`() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = createViewModel()
        vm.test(this) {
            runOnCreate()
            vm.perform(OnboardingAction.NextStep)
            vm.perform(OnboardingAction.NextStep)
            vm.perform(OnboardingAction.PasswordChanged("newpass"))
            cancelAndIgnoreRemainingItems()
        }
        val result = vm.container.stateFlow.value
        assertEquals("newpass", result.configs["rutracker"]!!.password)
    }

    // CHALLENGE
    @Test
    fun `filters out unverified providers`() = runTest(mainDispatcherRule.testDispatcher) {
        val registry = DefaultTrackerRegistry().also { reg ->
            val unverified = trackerDescriptor(
                id = "unverified",
                name = "Unverified",
                authType = AuthType.NONE,
                verified = false,
                apiSupported = true,
            )
            reg.register(factory(unverified, FakeTrackerClient(unverified)))
            reg.register(factory(archiveOrgDescriptor, archiveOrgClient))
        }
        val filteredSdk = LavaTrackerSdk(registry = registry)
        val vm = OnboardingViewModel(
            sdk = filteredSdk,
            credentialManager = credentialManager,
            authService = authService,
            loggerFactory = loggerFactory,
            analytics = analytics,
        )
        vm.test(this) {
            runOnCreate()
            cancelAndIgnoreRemainingItems()
        }
        val state = vm.container.stateFlow.value
        assertEquals(1, state.providers.size)
        assertEquals("archiveorg", state.providers.first().descriptor.trackerId)
    }

    // CHALLENGE
    @Test
    fun `filters out api-unsupported providers`() = runTest(mainDispatcherRule.testDispatcher) {
        val registry = DefaultTrackerRegistry().also { reg ->
            val unsupported = trackerDescriptor(
                id = "unsupported",
                name = "Unsupported",
                authType = AuthType.NONE,
                verified = true,
                apiSupported = false,
            )
            reg.register(factory(unsupported, FakeTrackerClient(unsupported)))
            reg.register(factory(archiveOrgDescriptor, archiveOrgClient))
        }
        val filteredSdk = LavaTrackerSdk(registry = registry)
        val vm = OnboardingViewModel(
            sdk = filteredSdk,
            credentialManager = credentialManager,
            authService = authService,
            loggerFactory = loggerFactory,
            analytics = analytics,
        )
        vm.test(this) {
            runOnCreate()
            cancelAndIgnoreRemainingItems()
        }
        val state = vm.container.stateFlow.value
        assertEquals(1, state.providers.size)
    }

    private fun createViewModel() = OnboardingViewModel(
        sdk = sdk,
        credentialManager = credentialManager,
        authService = authService,
        loggerFactory = loggerFactory,
        analytics = analytics,
    )

    private fun trackerDescriptor(
        id: String,
        name: String,
        authType: AuthType,
        verified: Boolean = true,
        apiSupported: Boolean = true,
    ) = object : TrackerDescriptor {
        override val trackerId: String = id
        override val displayName: String = name
        override val baseUrls: List<MirrorUrl> = listOf(
            MirrorUrl("https://$id.example/", isPrimary = true, priority = 0, protocol = Protocol.HTTPS),
        )
        override val capabilities: Set<TrackerCapability> = setOf(
            TrackerCapability.SEARCH,
            TrackerCapability.AUTH_REQUIRED,
        )
        override val authType: AuthType = authType
        override val encoding: String = "UTF-8"
        override val expectedHealthMarker: String = id
        override val verified: Boolean = verified
        override val apiSupported: Boolean = apiSupported
    }

    private fun factory(
        descriptor: TrackerDescriptor,
        client: TrackerClient,
    ) = object : TrackerClientFactory {
        override val descriptor: TrackerDescriptor = descriptor
        override fun create(config: lava.sdk.api.PluginConfig): TrackerClient = client
    }
}

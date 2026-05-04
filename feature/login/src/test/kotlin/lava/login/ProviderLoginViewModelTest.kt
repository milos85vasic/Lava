package lava.login

import androidx.compose.ui.text.input.TextFieldValue
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import lava.credentials.CredentialEncryptor
import lava.credentials.CredentialsRepository
import lava.credentials.ProviderCredentialManager
import lava.database.AppDatabase
import lava.domain.usecase.ValidateInputUseCase
import lava.sdk.api.MirrorUrl
import lava.sdk.api.Protocol
import lava.testing.logger.TestLoggerFactory
import lava.testing.rule.MainDispatcherRule
import lava.tracker.api.AuthType
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerDescriptor
import lava.tracker.api.model.AuthState
import lava.tracker.api.model.CaptchaChallenge
import lava.tracker.api.model.LoginResult
import lava.tracker.client.LavaTrackerSdk
import lava.tracker.registry.DefaultTrackerRegistry
import lava.tracker.registry.TrackerClientFactory
import lava.tracker.testing.FakeTrackerClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.orbitmvi.orbit.test.test
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Anti-bluff test for [ProviderLoginViewModel].
 *
 * Uses real ProviderCredentialManager + real CredentialsRepository + real Room DAO
 * + real CredentialEncryptor + real LavaTrackerSdk wired with FakeTrackerClients.
 * No mocks of internal business logic (Second Law compliance).
 *
 * Constitutional compliance:
 * - Sixth Law: assertions on user-visible state (loading, provider list, auth status,
 *   credential pre-fill, side effects)
 * - Bluff-Audit rehearsal: mutate onSubmitClick to skip credentialManager.setPassword()
 *   → test `successful login saves credentials` fails with wrong authType.
 *   Reverted.
 *
 * Bluff-Audit: ProviderLoginViewModelTest
 *   Deliberate break: commented out `credentialManager.setPassword()` in onSubmitClick
 *   Failure: `assertEquals("password", savedCreds?.authType)` → expected "password" but was "none"
 *   Reverted: yes
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ProviderLoginViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var db: AppDatabase
    private lateinit var viewModel: ProviderLoginViewModel
    private lateinit var manager: ProviderCredentialManager
    private lateinit var sdk: LavaTrackerSdk
    private lateinit var rutrackerClient: FakeTrackerClient
    private lateinit var rutorClient: FakeTrackerClient
    private lateinit var registry: DefaultTrackerRegistry

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val encryptor = CredentialEncryptor()
        val repository = CredentialsRepository(
            dao = db.providerCredentialsDao(),
            encryptor = encryptor,
        )
        manager = ProviderCredentialManager(repository)

        registry = DefaultTrackerRegistry()
        val rutrackerDesc = descriptor(
            "rutracker",
            "RuTracker",
            setOf(TrackerCapability.SEARCH, TrackerCapability.AUTH_REQUIRED),
        )
        val rutorDesc = descriptor(
            "rutor",
            "RuTor",
            setOf(TrackerCapability.SEARCH),
        )
        rutrackerClient = FakeTrackerClient(rutrackerDesc).apply {
            loginProvider = { req ->
                when (req.password) {
                    "correct" -> LoginResult(AuthState.Authenticated)
                    "captcha" -> LoginResult(
                        AuthState.CaptchaRequired(
                            CaptchaChallenge(
                                sid = "sid1",
                                code = "code1",
                                imageUrl = "https://cap/1.png",
                            ),
                        ),
                    )
                    else -> LoginResult(AuthState.Unauthenticated)
                }
            }
        }
        rutorClient = FakeTrackerClient(rutorDesc)
        registry.register(object : TrackerClientFactory {
            override val descriptor = rutrackerDesc
            override fun create(config: lava.sdk.api.PluginConfig) = rutrackerClient
        })
        registry.register(object : TrackerClientFactory {
            override val descriptor = rutorDesc
            override fun create(config: lava.sdk.api.PluginConfig) = rutorClient
        })

        sdk = LavaTrackerSdk(registry)
        viewModel = ProviderLoginViewModel(
            validateInputUseCase = ValidateInputUseCase(),
            credentialManager = manager,
            sdk = sdk,
            loggerFactory = TestLoggerFactory(),
        )
    }

    @Test
    fun `initial state shows loading then lists all providers`() =
        runTest(mainDispatcherRule.testDispatcher) {
            viewModel.test(this) {
                runOnCreate()
                var state = awaitState()
                assertTrue(state.isLoading)
                state = awaitState()
                assertFalse(state.isLoading)
                assertEquals(2, state.providers.size)
                assertTrue(state.providers.any { it.providerId == "rutracker" })
                assertTrue(state.providers.any { it.providerId == "rutor" })
            }
        }

    @Test
    fun `select provider pre-fills saved credentials`() =
        runTest(mainDispatcherRule.testDispatcher) {
            manager.setPassword("rutracker", "vasya", "secret")

            viewModel.test(this) {
                runOnCreate()
                awaitState() // loading
                awaitState() // loaded

                viewModel.perform(ProviderLoginAction.SelectProvider("rutracker"))
                val state = awaitState()
                assertEquals("rutracker", state.selectedProviderId)
                assertEquals("vasya", state.usernameInput.value.text)
                assertEquals("secret", state.passwordInput.value.text)
            }
        }

    @Test
    fun `anonymous mode login succeeds immediately`() =
        runTest(mainDispatcherRule.testDispatcher) {
            viewModel.test(this) {
                runOnCreate()
                awaitState() // loading
                awaitState() // loaded

                viewModel.perform(ProviderLoginAction.SelectProvider("rutracker"))
                awaitState() // selected

                viewModel.perform(ProviderLoginAction.SetAnonymousMode(true))
                awaitState() // anonymous mode toggled

                viewModel.perform(ProviderLoginAction.SubmitClick)
                // Two side effects: HideKeyboard then Success. Intermediate
                // loading=true/false reduces are coalesced by StateFlow, so we
                // assert on side effects + final state value.
                val effect1 = awaitSideEffect()
                assertTrue(effect1 is LoginSideEffect.HideKeyboard)
                val effect2 = awaitSideEffect()
                assertTrue(effect2 is LoginSideEffect.Success)
                assertFalse(viewModel.container.stateFlow.value.isLoading)
            }
        }

    /**
     * Regression test for the Internet Archive stuck-on-loading bug (forensic anchor of clause 6.G).
     *
     * Bug: a provider with `AuthType.NONE` (no anonymous-mode toggle in the UI, just a
     * "Continue" button) would tap Submit, the spinner would appear, `sdk.login()` would
     * return `null` (the no-auth branch), and the spinner would never be dismissed.
     *
     * This test exercises the EXACT user flow that broke: select an `AuthType.NONE`
     * provider, tap Submit WITHOUT toggling anonymous mode, and verify Success is emitted
     * AND `isLoading` ends false. The pre-existing `anonymous mode login succeeds immediately`
     * test does NOT cover this path because it sets `SetAnonymousMode(true)` first, which
     * triggers the OR-branch (`state.anonymousMode`), not the AuthType.NONE branch.
     *
     * Bluff-Audit: ProviderLoginViewModelTest.no-auth provider Continue tap succeeds without anonymous toggle
     *   Mutation: change `provider?.authType == "NONE"` to `provider?.authType == "NEVER"`
     *             at ProviderLoginViewModel.onSubmitClick (line 195)
     *   Observed-Failure: this test fails — final side effect is not Success; the OLD
     *             "anonymous mode login succeeds immediately" test STILL PASSES, confirming
     *             it was a bluff for the IA bug class.
     *   Reverted: yes
     */
    @Test
    fun `no-auth provider Continue tap succeeds without anonymous toggle`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Add an AuthType.NONE provider distinct from the AUTH_REQUIRED ones in setUp().
            // Caps deliberately exclude AUTH_REQUIRED, so:
            //   - the descriptor reports AuthType.NONE (helper at line 303),
            //   - FakeTrackerClient.getFeature(AuthenticatableTracker::class) returns null,
            //   - sdk.login("archiveorg", _) therefore returns null,
            //   - which is the EXACT runtime shape of the real Internet Archive flow.
            val noAuthDesc = descriptor(
                id = "archiveorg",
                name = "Internet Archive",
                caps = setOf(TrackerCapability.SEARCH),
            )
            assertEquals(AuthType.NONE, noAuthDesc.authType)
            val noAuthClient = FakeTrackerClient(noAuthDesc) // loginProvider unused
            registry.register(
                object : TrackerClientFactory {
                    override val descriptor = noAuthDesc
                    override fun create(config: lava.sdk.api.PluginConfig) = noAuthClient
                },
            )

            viewModel.test(this) {
                runOnCreate()
                awaitState() // initial loading
                awaitState() // loaded — now includes archiveorg

                viewModel.perform(ProviderLoginAction.SelectProvider("archiveorg"))
                awaitState() // selected; no anonymous toggle, no username/password set

                viewModel.perform(ProviderLoginAction.SubmitClick)

                // Two side effects MUST follow: HideKeyboard, then Success.
                // (StateFlow conflates the loading=true/loading=false reduces, so
                // we don't assert on the intermediate state shape — what matters
                // user-visibly is that Success fires AND the final state isn't
                // stuck on loading.)
                val effect1 = awaitSideEffect()
                assertTrue(
                    "first effect must be HideKeyboard, was $effect1",
                    effect1 is LoginSideEffect.HideKeyboard,
                )
                val effect2 = awaitSideEffect()
                assertTrue(
                    "must emit Success for AuthType.NONE Continue tap, was $effect2 — IA stuck-on-loading regression",
                    effect2 is LoginSideEffect.Success,
                )
                // User-visible final state: spinner cleared.
                assertFalse(
                    "isLoading must be false after Success — IA stuck-on-loading regression",
                    viewModel.container.stateFlow.value.isLoading,
                )
            }
        }

    @Test
    fun `successful login saves credentials`() =
        runTest(mainDispatcherRule.testDispatcher) {
            viewModel.test(this) {
                runOnCreate()
                awaitState() // loading
                awaitState() // loaded

                viewModel.perform(ProviderLoginAction.SelectProvider("rutracker"))
                awaitState() // selected

                viewModel.perform(ProviderLoginAction.UsernameChanged(TextFieldValue("vasya")))
                awaitState()

                viewModel.perform(ProviderLoginAction.PasswordChanged(TextFieldValue("correct")))
                awaitState()

                viewModel.perform(ProviderLoginAction.SubmitClick)
                awaitSideEffect() // HideKeyboard (or Success if coalesced)
                val state = awaitState()
                assertTrue(state.isLoading)
                val effect = awaitSideEffect()
                assertTrue(effect is LoginSideEffect.Success)

                val savedCreds = manager.getCredentials("rutracker")
                assertNotNull(savedCreds)
                assertEquals("password", savedCreds?.authType)
                assertEquals("vasya", savedCreds?.username)
            }
        }

    @Test
    fun `wrong credits shows error and clears captcha`() =
        runTest(mainDispatcherRule.testDispatcher) {
            viewModel.test(this) {
                runOnCreate()
                awaitState() // loading
                awaitState() // loaded

                viewModel.perform(ProviderLoginAction.SelectProvider("rutracker"))
                awaitState()

                viewModel.perform(ProviderLoginAction.UsernameChanged(TextFieldValue("vasya")))
                awaitState()

                viewModel.perform(ProviderLoginAction.PasswordChanged(TextFieldValue("wrong")))
                awaitState()

                viewModel.perform(ProviderLoginAction.SubmitClick)
                awaitSideEffect() // HideKeyboard
                val state = awaitState()
                assertFalse(state.isLoading)
                assertTrue(state.usernameInput is InputState.Invalid)
                assertTrue(state.passwordInput is InputState.Invalid)
            }
        }

    @Test
    fun `captcha required shows captcha`() =
        runTest(mainDispatcherRule.testDispatcher) {
            viewModel.test(this) {
                runOnCreate()
                awaitState() // loading
                awaitState() // loaded

                viewModel.perform(ProviderLoginAction.SelectProvider("rutracker"))
                awaitState()

                viewModel.perform(ProviderLoginAction.UsernameChanged(TextFieldValue("vasya")))
                awaitState()

                viewModel.perform(ProviderLoginAction.PasswordChanged(TextFieldValue("captcha")))
                awaitState()

                viewModel.perform(ProviderLoginAction.SubmitClick)
                awaitSideEffect() // HideKeyboard
                val state = awaitState()
                assertFalse(state.isLoading)
                assertNotNull(state.captcha)
                assertEquals("sid1", state.captcha?.id)
            }
        }

    @Test
    fun `back to providers clears form`() =
        runTest(mainDispatcherRule.testDispatcher) {
            viewModel.test(this) {
                runOnCreate()
                awaitState() // loading
                awaitState() // loaded

                viewModel.perform(ProviderLoginAction.SelectProvider("rutracker"))
                awaitState()

                viewModel.perform(ProviderLoginAction.BackToProviders)
                val state = awaitState()
                assertNull(state.selectedProviderId)
                assertTrue(state.usernameInput is InputState.Initial)
                assertTrue(state.passwordInput is InputState.Initial)
            }
        }

    private fun descriptor(
        id: String,
        name: String,
        caps: Set<TrackerCapability>,
    ) = object : TrackerDescriptor {
        override val trackerId = id
        override val displayName = name
        override val baseUrls =
            listOf(MirrorUrl("https://$id.example", isPrimary = true, protocol = Protocol.HTTPS))
        override val capabilities = caps
        override val authType =
            if (TrackerCapability.AUTH_REQUIRED in caps) AuthType.FORM_LOGIN else AuthType.NONE
        override val encoding = "UTF-8"
        override val expectedHealthMarker = id

        // Test descriptors are verified-by-construction so the UI filter (clause 6.G)
        // does not hide them. Production descriptors gate this on a real Challenge Test.
        override val verified = true
    }
}

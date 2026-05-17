package lava.login

import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import lava.common.analytics.AnalyticsTracker
import lava.domain.usecase.LoginUseCase
import lava.domain.usecase.ValidateInputUseCase
import lava.models.auth.AuthResult
import lava.models.auth.Captcha
import lava.network.api.NetworkApi
import lava.network.data.NetworkApiRepository
import lava.testing.TestDispatchers
import lava.testing.logger.TestLoggerFactory
import lava.testing.rule.MainDispatcherRule
import lava.testing.service.TestAuthService
import lava.testing.service.TestBackgroundService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.orbitmvi.orbit.test.test

/**
 * Anti-bluff coverage for [LoginViewModel] focused on Sweep Findings #4
 * + #5 (2026-05-17, §6.L 59th invocation):
 *  - Finding #4 — `serviceUnavailable` banner MUST clear on UsernameChanged,
 *    PasswordChanged, CaptchaChanged, ReloadCaptchaClick, SubmitClick.
 *  - Finding #5 — the ServiceUnavailable branch MUST also clear any stale
 *    `captcha` + `captchaInput` from a prior CaptchaRequired turn.
 *
 * Constitution: real LoginUseCase wired to a real ValidateInputUseCase +
 * the [TestAuthService] from `:core:testing` (Anti-Bluff Pact Third Law:
 * test fakes only at the outermost boundaries). Mocks are NOT used for
 * LoginUseCase itself — the test programmatically sets
 * [TestAuthService.response] to drive each AuthResult branch.
 *
 * The orbit-test `viewModel.test(this) { ... }` wraps each step so the
 * stateFlow + sideEffect channels both have an active subscriber (the
 * Orbit container starts lazily; without a subscriber, intent { } is
 * queued but never dispatched). Inside the block, the final assertion
 * reads `viewModel.container.stateFlow.value` directly — the awaitState
 * calls are necessary to drain the orbit stream's buffered events
 * (otherwise orbit-test errors on test scope close).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private lateinit var authService: TestAuthService
    private lateinit var loginUseCase: LoginUseCase
    private lateinit var validateInputUseCase: ValidateInputUseCase
    private lateinit var viewModel: LoginViewModel

    private val recordingAnalytics = object : AnalyticsTracker {
        override fun event(name: String, params: Map<String, String>) {}
        override fun setUserId(userId: String?) {}
        override fun setProperty(key: String, value: String?) {}
        override fun recordNonFatal(throwable: Throwable, context: Map<String, String>) {}
        override fun recordWarning(message: String, context: Map<String, String>) {}
        override fun log(message: String) {}
    }

    @Before
    fun setUp() {
        authService = TestAuthService()
        validateInputUseCase = ValidateInputUseCase()
        loginUseCase = LoginUseCase(
            authService = authService,
            backgroundService = TestBackgroundService(),
            networkApiRepository = NoopNetworkApiRepository,
            dispatchers = TestDispatchers(dispatcherRule.testDispatcher),
        )
        viewModel = LoginViewModel(
            loginUseCase = loginUseCase,
            validateInputUseCase = validateInputUseCase,
            analytics = recordingAnalytics,
            loggerFactory = TestLoggerFactory(),
        )
    }

    /**
     * Sweep Finding #4 — UsernameChanged clears stale serviceUnavailable.
     *
     * Falsifiability rehearsal:
     *   Mutation: remove `serviceUnavailable = null` from
     *             LoginViewModel.validateUsername's reduce.
     *   Observed: this test fails with
     *             "serviceUnavailable MUST be null after UsernameChanged —
     *             was Cloudflare 503".
     *   Reverted: yes.
     */
    @Test
    fun `Finding 4 - UsernameChanged clears stale serviceUnavailable`() =
        runTest(dispatcherRule.testDispatcher) {
            authService.response = AuthResult.ServiceUnavailable(reason = "Cloudflare 503")
            viewModel.test(this) {
                viewModel.perform(LoginAction.SubmitClick)
                // Drain orbit-test buffer in arrival order.
                awaitItem(); awaitItem(); awaitItem()
                val afterSubmit = viewModel.container.stateFlow.value
                assertEquals(
                    "precondition: banner populated after SubmitClick → ServiceUnavailable",
                    "Cloudflare 503",
                    afterSubmit.serviceUnavailable,
                )

                viewModel.perform(LoginAction.UsernameChanged(TextFieldValue("new-user")))
                awaitItem()
                val final = viewModel.container.stateFlow.value
                assertNull(
                    "serviceUnavailable MUST be null after UsernameChanged — " +
                        "was ${final.serviceUnavailable}",
                    final.serviceUnavailable,
                )
                cancelAndIgnoreRemainingItems()
            }
        }

    /**
     * Sweep Finding #4 — PasswordChanged also clears the banner.
     */
    @Test
    fun `Finding 4 - PasswordChanged clears stale serviceUnavailable`() =
        runTest(dispatcherRule.testDispatcher) {
            authService.response = AuthResult.ServiceUnavailable(reason = "Parser Unknown")
            viewModel.test(this) {
                viewModel.perform(LoginAction.SubmitClick)
                awaitItem(); awaitItem(); awaitItem()
                assertEquals(
                    "Parser Unknown",
                    viewModel.container.stateFlow.value.serviceUnavailable,
                )

                viewModel.perform(LoginAction.PasswordChanged(TextFieldValue("new-pwd")))
                awaitItem()
                val final = viewModel.container.stateFlow.value
                assertNull(
                    "serviceUnavailable MUST be null after PasswordChanged — " +
                        "was ${final.serviceUnavailable}",
                    final.serviceUnavailable,
                )
                cancelAndIgnoreRemainingItems()
            }
        }

    /**
     * Sweep Finding #5 — the ServiceUnavailable branch clears stale captcha.
     *
     * Forensic anchor: a prior CaptchaRequired populated `state.captcha`.
     * The next attempt lands ServiceUnavailable. The captcha image is now
     * stale (sid invalid upstream); the UI MUST NOT keep rendering it.
     *
     * Falsifiability rehearsal:
     *   Mutation: remove `captcha = null, captchaInput = InputState.Initial`
     *             from the ServiceUnavailable branch in onSubmitClick.
     *   Observed: this test fails with
     *             "captcha MUST be null after ServiceUnavailable — was Captcha(...)".
     *   Reverted: yes.
     */
    @Test
    fun `Finding 5 - ServiceUnavailable clears stale captcha and captchaInput`() =
        runTest(dispatcherRule.testDispatcher) {
            val captcha = Captcha(id = "sid1", code = "code1", url = "https://example/cap.png")
            authService.response = AuthResult.CaptchaRequired(captcha = captcha)
            viewModel.test(this) {
                viewModel.perform(LoginAction.SubmitClick)
                awaitItem(); awaitItem(); awaitItem()
                assertNotNull(
                    "precondition: captcha populated after CaptchaRequired",
                    viewModel.container.stateFlow.value.captcha,
                )

                authService.response = AuthResult.ServiceUnavailable(reason = "503 after captcha")
                viewModel.perform(LoginAction.SubmitClick)
                awaitItem(); awaitItem()

                val afterServiceUnavailable = viewModel.container.stateFlow.value
                assertNull(
                    "captcha MUST be null after ServiceUnavailable — was " +
                        "${afterServiceUnavailable.captcha}",
                    afterServiceUnavailable.captcha,
                )
                assertTrue(
                    "captchaInput MUST be Initial after ServiceUnavailable — was " +
                        "${afterServiceUnavailable.captchaInput}",
                    afterServiceUnavailable.captchaInput is InputState.Initial,
                )
                assertEquals(
                    "503 after captcha",
                    afterServiceUnavailable.serviceUnavailable,
                )
                cancelAndIgnoreRemainingItems()
            }
        }
}

/**
 * Stub [NetworkApiRepository] for tests that don't exercise captcha-URL
 * enrichment — the LoginViewModel + LoginUseCase happy/error paths
 * exercised here never hit getCaptchaUrl or any other network seam.
 */
private object NoopNetworkApiRepository : NetworkApiRepository {
    override suspend fun getApi(): NetworkApi =
        error("NoopNetworkApiRepository: getApi not exercised")
    override suspend fun getCaptchaUrl(url: String): String = url
    override suspend fun getDownloadUri(id: String): String =
        error("NoopNetworkApiRepository: getDownloadUri not exercised")
    override suspend fun getAuthHeader(token: String): Pair<String, String> =
        error("NoopNetworkApiRepository: getAuthHeader not exercised")
}

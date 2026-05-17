package lava.login

import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import lava.common.analytics.AnalyticsTracker
import lava.domain.usecase.LoginUseCase
import lava.domain.usecase.ValidateInputUseCase
import lava.logger.api.LoggerFactory
import lava.models.auth.AuthResult
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.syntax.simple.intent
import org.orbitmvi.orbit.syntax.simple.postSideEffect
import org.orbitmvi.orbit.syntax.simple.reduce
import org.orbitmvi.orbit.viewmodel.container
import javax.inject.Inject

@HiltViewModel
internal class LoginViewModel @Inject constructor(
    private val loginUseCase: LoginUseCase,
    private val validateInputUseCase: ValidateInputUseCase,
    private val analytics: AnalyticsTracker,
    loggerFactory: LoggerFactory,
) : ViewModel(), ContainerHost<LoginState, LoginSideEffect> {
    private val logger = loggerFactory.get("LoginViewModel")

    override val container: Container<LoginState, LoginSideEffect> = container(LoginState())

    fun perform(action: LoginAction) {
        logger.d { "Perform $action" }
        when (action) {
            is LoginAction.UsernameChanged -> validateUsername(action.value)
            is LoginAction.PasswordChanged -> validatePassword(action.value)
            is LoginAction.CaptchaChanged -> validateCaptcha(action.value)
            is LoginAction.ReloadCaptchaClick -> onReloadCaptchaClick()
            is LoginAction.SubmitClick -> onSubmitClick()
        }
    }

    private fun validateUsername(value: TextFieldValue) = intent {
        reduce {
            state.copy(
                usernameInput = if (validateInputUseCase(value.text)) {
                    InputState.Valid(value)
                } else {
                    InputState.Empty
                },
            )
        }
    }

    private fun validatePassword(value: TextFieldValue) = intent {
        reduce {
            state.copy(
                passwordInput = if (validateInputUseCase(value.text)) {
                    InputState.Valid(value)
                } else {
                    InputState.Empty
                },
            )
        }
    }

    private fun validateCaptcha(value: TextFieldValue) = intent {
        reduce {
            state.copy(
                captchaInput = if (validateInputUseCase(value.text)) {
                    InputState.Valid(value)
                } else {
                    InputState.Empty
                },
            )
        }
    }

    private fun onReloadCaptchaClick() = intent {
        val response = loginUseCase(
            username = state.usernameInput.value.text,
            password = state.passwordInput.value.text,
            captchaSid = null,
            captchaCode = null,
            captchaValue = null,
        )
        when (response) {
            is AuthResult.WrongCredits -> reduce {
                state.copy(
                    isLoading = false,
                    captcha = response.captcha,
                    captchaInput = InputState.Empty,
                )
            }
            is AuthResult.CaptchaRequired -> reduce {
                state.copy(
                    isLoading = false,
                    captcha = response.captcha,
                    captchaInput = InputState.Empty,
                )
            }
            is AuthResult.Error -> Unit
            is AuthResult.Success -> Unit
            // Bug 1 (2026-05-17, §6.L 57th): ServiceUnavailable means the
            // upstream produced an infrastructure error; the reload-captcha
            // path has no captcha-display side effect to perform — just
            // clear loading so the user can retry. The user-visible message
            // surfaces on the main submit path below.
            is AuthResult.ServiceUnavailable -> reduce { state.copy(isLoading = false) }
        }
    }

    private fun onSubmitClick() = intent {
        postSideEffect(LoginSideEffect.HideKeyboard)
        reduce { state.copy(isLoading = true) }
        analytics.event(AnalyticsTracker.Events.LOGIN_SUBMIT)
        val response = loginUseCase(
            state.usernameInput.value.text,
            state.passwordInput.value.text,
            state.captcha?.id,
            state.captcha?.code,
            state.captchaInput.value.text,
        )
        when (response) {
            is AuthResult.Success -> {
                logger.d { "Login success" }
                analytics.event(AnalyticsTracker.Events.LOGIN_SUCCESS)
                postSideEffect(LoginSideEffect.Success)
            }

            is AuthResult.CaptchaRequired -> {
                logger.d { "Login failed: captcha required" }
                analytics.event(
                    AnalyticsTracker.Events.LOGIN_FAILURE,
                    mapOf(AnalyticsTracker.Params.ERROR to "captcha_required"),
                )
                reduce {
                    state.copy(
                        isLoading = false,
                        captcha = response.captcha,
                        captchaInput = InputState.Empty,
                    )
                }
            }

            is AuthResult.WrongCredits -> {
                logger.d { "Login failed: wrong credits" }
                analytics.event(
                    AnalyticsTracker.Events.LOGIN_FAILURE,
                    mapOf(AnalyticsTracker.Params.ERROR to "wrong_credits"),
                )
                reduce {
                    state.copy(
                        isLoading = false,
                        usernameInput = InputState.Invalid(state.usernameInput.value),
                        passwordInput = InputState.Invalid(state.passwordInput.value),
                        captcha = response.captcha,
                        captchaInput = InputState.Empty,
                    )
                }
            }

            is AuthResult.Error -> {
                logger.e(response.error) { "Login error" }
                analytics.recordNonFatal(
                    response.error,
                    mapOf(AnalyticsTracker.Params.ERROR to "login_exception"),
                )
                postSideEffect(LoginSideEffect.Error(response.error))
                reduce { state.copy(isLoading = false) }
            }

            // Bug 1 (2026-05-17, §6.L 57th invocation): the upstream
            // produced an infrastructure error. Render the reason
            // verbatim and DO NOT mark credentials as Invalid — that
            // would be the §6.J bluff this branch exists to evict.
            is AuthResult.ServiceUnavailable -> {
                logger.e { "Login service unavailable: ${response.reason}" }
                analytics.recordWarning(
                    "login_service_unavailable",
                    mapOf(
                        AnalyticsTracker.Params.ERROR to response.reason,
                    ),
                )
                reduce {
                    state.copy(
                        isLoading = false,
                        serviceUnavailable = response.reason,
                    )
                }
            }
        }
    }
}

package lava.login

import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import lava.credentials.ProviderCredentialManager
import lava.domain.usecase.ValidateInputUseCase
import lava.logger.api.LoggerFactory
import lava.models.auth.AuthResult
import lava.tracker.api.model.CaptchaSolution
import lava.tracker.api.model.LoginRequest
import lava.tracker.client.LavaTrackerSdk
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.syntax.simple.intent
import org.orbitmvi.orbit.syntax.simple.postSideEffect
import org.orbitmvi.orbit.syntax.simple.reduce
import org.orbitmvi.orbit.viewmodel.container
import javax.inject.Inject

/**
 * ViewModel for the multi-provider login screen.
 *
 * Added in Multi-Provider Extension (US2).
 */
@HiltViewModel
internal class ProviderLoginViewModel @Inject constructor(
    private val validateInputUseCase: ValidateInputUseCase,
    private val credentialManager: ProviderCredentialManager,
    private val sdk: LavaTrackerSdk,
    loggerFactory: LoggerFactory,
) : ViewModel(), ContainerHost<ProviderLoginState, LoginSideEffect> {

    private val logger = loggerFactory.get("ProviderLoginViewModel")

    override val container: Container<ProviderLoginState, LoginSideEffect> = container(
        initialState = ProviderLoginState(isLoading = true),
        onCreate = { loadProviders() },
    )

    fun perform(action: ProviderLoginAction) {
        logger.d { "Perform $action" }
        when (action) {
            is ProviderLoginAction.SelectProvider -> selectProvider(action.providerId)
            is ProviderLoginAction.SetAnonymousMode -> setAnonymousMode(action.enabled)
            is ProviderLoginAction.UsernameChanged -> validateUsername(action.value)
            is ProviderLoginAction.PasswordChanged -> validatePassword(action.value)
            is ProviderLoginAction.CaptchaChanged -> validateCaptcha(action.value)
            is ProviderLoginAction.ReloadCaptchaClick -> onReloadCaptchaClick()
            is ProviderLoginAction.SubmitClick -> onSubmitClick()
            is ProviderLoginAction.BackToProviders -> backToProviders()
        }
    }

    private fun loadProviders() = intent {
        try {
            val descriptors = sdk.listAvailableTrackers()
            val creds = credentialManager.observeAll().first()
            val items = descriptors.map { desc ->
                val cred = creds.firstOrNull { it.providerId == desc.trackerId }
                ProviderLoginItem(
                    providerId = desc.trackerId,
                    displayName = desc.displayName,
                    providerType = if (desc.capabilities.any { it.name.contains("TORRENT") }) "Tracker" else "HTTP Library",
                    authType = desc.authType.name,
                    isAuthenticated = cred != null && cred.authType != "none",
                    hasCredentials = cred != null && cred.authType != "none",
                )
            }
            reduce { state.copy(isLoading = false, providers = items) }
        } catch (t: Throwable) {
            logger.e(t) { "Failed to load providers" }
            reduce { state.copy(isLoading = false, error = t.message) }
        }
    }

    private fun selectProvider(providerId: String) = intent {
        val creds = credentialManager.getCredentials(providerId)
        val savedUsername = creds?.username
        val savedPassword = creds?.password
        reduce {
            state.copy(
                selectedProviderId = providerId,
                usernameInput = if (savedUsername != null) {
                    InputState.Valid(TextFieldValue(savedUsername))
                } else {
                    InputState.Initial
                },
                passwordInput = if (savedPassword != null) {
                    InputState.Valid(TextFieldValue(savedPassword))
                } else {
                    InputState.Initial
                },
                captchaInput = InputState.Initial,
                captcha = null,
            )
        }
    }

    private fun setAnonymousMode(enabled: Boolean) = intent {
        reduce { state.copy(anonymousMode = enabled) }
    }

    private fun backToProviders() = intent {
        reduce {
            state.copy(
                selectedProviderId = null,
                usernameInput = InputState.Initial,
                passwordInput = InputState.Initial,
                captchaInput = InputState.Initial,
                captcha = null,
            )
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
        val providerId = state.selectedProviderId ?: return@intent
        val result = sdk.login(
            providerId,
            LoginRequest(
                username = state.usernameInput.value.text,
                password = state.passwordInput.value.text,
            ),
        )
        when (result) {
            null -> Unit // tracker does not support auth
            else -> {
                val response = result.toAuthResult()
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
                    else -> Unit
                }
            }
        }
    }

    private fun onSubmitClick() = intent {
        postSideEffect(LoginSideEffect.HideKeyboard)
        reduce { state.copy(isLoading = true) }

        val providerId = state.selectedProviderId ?: return@intent

        // If anonymous mode is enabled and provider supports it, skip auth
        if (state.anonymousMode) {
            logger.d { "Anonymous mode selected for $providerId" }
            kotlinx.coroutines.yield()
            postSideEffect(LoginSideEffect.Success)
            return@intent
        }

        val captchaSolution = state.captcha?.let {
            CaptchaSolution(
                sid = it.id,
                code = it.code,
                value = state.captchaInput.value.text,
            )
        }

        val result = sdk.login(
            providerId,
            LoginRequest(
                username = state.usernameInput.value.text,
                password = state.passwordInput.value.text,
                captcha = captchaSolution,
            ),
        )

        when (result) {
            null -> {
                // Tracker does not support auth — treat as success for UI
                logger.d { "Tracker $providerId does not support auth" }
                postSideEffect(LoginSideEffect.Success)
            }
            else -> {
                val response = result.toAuthResult()
                when (response) {
                    is AuthResult.Success -> {
                        logger.d { "Login success for $providerId" }
                        credentialManager.setPassword(
                            providerId = providerId,
                            username = state.usernameInput.value.text,
                            password = state.passwordInput.value.text,
                        )
                        postSideEffect(LoginSideEffect.Success)
                    }
                    is AuthResult.CaptchaRequired -> {
                        logger.d { "Login failed for $providerId: captcha required" }
                        reduce {
                            state.copy(
                                isLoading = false,
                                captcha = response.captcha,
                                captchaInput = InputState.Empty,
                            )
                        }
                    }
                    is AuthResult.WrongCredits -> {
                        logger.d { "Login failed for $providerId: wrong credits" }
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
                        logger.e(response.error) { "Login error for $providerId" }
                        postSideEffect(LoginSideEffect.Error(response.error))
                        reduce { state.copy(isLoading = false) }
                    }
                }
            }
        }
    }
}

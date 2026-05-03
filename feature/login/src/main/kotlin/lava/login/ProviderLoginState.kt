package lava.login

/**
 * State for the multi-provider login screen.
 *
 * Added in Multi-Provider Extension (US2).
 */
data class ProviderLoginState(
    val isLoading: Boolean = false,
    val providers: List<ProviderLoginItem> = emptyList(),
    val selectedProviderId: String? = null,
    val anonymousMode: Boolean = false,
    val usernameInput: InputState = InputState.Initial,
    val passwordInput: InputState = InputState.Initial,
    val captchaInput: InputState = InputState.Initial,
    val captcha: lava.models.auth.Captcha? = null,
    val error: String? = null,
)

/**
 * UI model for a provider in the login list.
 */
data class ProviderLoginItem(
    val providerId: String,
    val displayName: String,
    val providerType: String, // "Tracker" or "HTTP Library"
    val authType: String,
    val isAuthenticated: Boolean,
    val hasCredentials: Boolean,
)

val ProviderLoginState.hasCaptcha: Boolean
    get() = captcha != null

val ProviderLoginState.isValid: Boolean
    get() = usernameInput.isValid() &&
        passwordInput.isValid() &&
        (!hasCaptcha || captchaInput.isValid())

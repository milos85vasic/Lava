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
    /**
     * Bug 1 (2026-05-17, §6.L 57th invocation): when the SDK's
     * `RuTrackerNetworkApi.login` (or any other upstream) reports
     * [lava.models.auth.AuthResult.ServiceUnavailable], this field
     * carries the reason string for the UI to render. The §6.J anti-
     * bluff requirement: the UI MUST NOT mark username/password as
     * Invalid when this fires — that would tell the user the
     * credentials are wrong when in fact the system has no idea.
     */
    val serviceUnavailable: String? = null,
)

/**
 * UI model for a provider in the login list.
 *
 * `supportsAnonymous` (Phase 1.5, 2026-05-04) is per-provider, mapped
 * from `TrackerDescriptor.supportsAnonymous`. The provider-list UI
 * SHOULD use it to gate the "Anonymous Access" toggle's visibility (or
 * disabled state) when this provider is selected. The login submit
 * path MUST gate `state.anonymousMode` on this flag — see
 * `ProviderLoginViewModel.onSubmitClick`.
 */
data class ProviderLoginItem(
    val providerId: String,
    val displayName: String,
    val providerType: String, // "Tracker" or "HTTP Library"
    val authType: String,
    val isAuthenticated: Boolean,
    val hasCredentials: Boolean,
    val supportsAnonymous: Boolean = false,
)

val ProviderLoginState.hasCaptcha: Boolean
    get() = captcha != null

val ProviderLoginState.isValid: Boolean
    get() = usernameInput.isValid() &&
        passwordInput.isValid() &&
        (!hasCaptcha || captchaInput.isValid())

package lava.login

import androidx.compose.ui.text.input.TextFieldValue

internal sealed interface ProviderLoginAction {
    data class SelectProvider(val providerId: String) : ProviderLoginAction
    data class SetAnonymousMode(val enabled: Boolean) : ProviderLoginAction
    data class UsernameChanged(val value: TextFieldValue) : ProviderLoginAction
    data class PasswordChanged(val value: TextFieldValue) : ProviderLoginAction
    data class CaptchaChanged(val value: TextFieldValue) : ProviderLoginAction

    /**
     * Phase 5 (2026-06-11): user edited the API-key field for an
     * [lava.tracker.api.AuthType.API_KEY] provider.
     */
    data class ApiKeyChanged(val value: TextFieldValue) : ProviderLoginAction
    data object ReloadCaptchaClick : ProviderLoginAction
    data object SubmitClick : ProviderLoginAction
    data object BackToProviders : ProviderLoginAction
}

package lava.menu

import androidx.annotation.StringRes

internal sealed interface MenuSideEffect {
    data object OpenLogin : MenuSideEffect
    data class OpenLink(val link: String) : MenuSideEffect
    data object ShowAbout : MenuSideEffect
    data object OpenConnectionSettings : MenuSideEffect

    /** Multi-Provider Extension. Open the provider credentials screen. */
    data object OpenCredentials : MenuSideEffect

    /** SP-4 Phase B (Task 18). Open the per-provider config screen. */
    data class OpenProviderConfig(val providerId: String) : MenuSideEffect
    data class ShowConfirmation(
        @StringRes val title: Int,
        @StringRes val confirmationMessage: Int,
        val action: () -> Unit,
    ) : MenuSideEffect

    /** Show a confirmation dialog before signing out of a provider. */
    data class ShowSignOutConfirmation(val providerId: String) : MenuSideEffect

    /** Sign-out completed successfully. */
    data object ShowSignOutSuccess : MenuSideEffect
}

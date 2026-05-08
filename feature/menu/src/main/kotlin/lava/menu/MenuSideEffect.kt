package lava.menu

import androidx.annotation.StringRes

internal sealed interface MenuSideEffect {
    data object OpenLogin : MenuSideEffect
    data class OpenLink(val link: String) : MenuSideEffect
    data object ShowAbout : MenuSideEffect
    data object OpenConnectionSettings : MenuSideEffect

    /** SP-3a Phase 4 (Task 4.19). Open the multi-tracker settings screen. */
    data object OpenTrackerSettings : MenuSideEffect

    /** Multi-Provider Extension. Open the provider credentials screen. */
    data object OpenCredentials : MenuSideEffect
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

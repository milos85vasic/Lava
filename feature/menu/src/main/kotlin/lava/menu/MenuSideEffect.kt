package lava.menu

import androidx.annotation.StringRes

internal sealed interface MenuSideEffect {
    data object OpenLogin : MenuSideEffect
    data class OpenLink(val link: String) : MenuSideEffect
    data object ShowAbout : MenuSideEffect
    data object OpenConnectionSettings : MenuSideEffect

    /** SP-3a Phase 4 (Task 4.19). Open the multi-tracker settings screen. */
    data object OpenTrackerSettings : MenuSideEffect
    data class ShowConfirmation(
        @StringRes val title: Int,
        @StringRes val confirmationMessage: Int,
        val action: () -> Unit,
    ) : MenuSideEffect
}

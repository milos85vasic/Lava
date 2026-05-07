package lava.menu

import androidx.annotation.StringRes
import lava.models.settings.Endpoint
import lava.models.settings.SyncPeriod
import lava.models.settings.Theme

internal sealed interface MenuAction {
    data object AboutClick : MenuAction
    data object ClearBookmarksConfirmation : MenuAction
    data object ClearFavoritesConfirmation : MenuAction
    data object ClearHistoryConfirmation : MenuAction
    data class ConfirmableAction(
        @StringRes val title: Int,
        @StringRes val confirmationMessage: Int,
        val onConfirmAction: () -> Unit,
    ) : MenuAction
    data object LoginClick : MenuAction
    data object PrivacyPolicyClick : MenuAction
    data object RightsClick : MenuAction
    data object SendFeedbackClick : MenuAction

    /** SP-3a Phase 4 (Task 4.19). Open the multi-tracker settings screen. */
    data object TrackerSettingsClick : MenuAction

    /** Multi-Provider Extension. Open the provider credentials screen. */
    data object CredentialsClick : MenuAction

    /** Sign out of a specific provider. */
    data class SignOut(val providerId: String) : MenuAction
    data class SetBookmarksSyncPeriod(val syncPeriod: SyncPeriod) : MenuAction
    data class SetCredentialsSyncPeriod(val syncPeriod: SyncPeriod) : MenuAction
    data class SetEndpoint(val endpoint: Endpoint) : MenuAction
    data class SetFavoritesSyncPeriod(val syncPeriod: SyncPeriod) : MenuAction
    data class SetHistorySyncPeriod(val syncPeriod: SyncPeriod) : MenuAction
    data class SetTheme(val theme: Theme) : MenuAction
}

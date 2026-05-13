package lava.provider.config.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import lava.designsystem.component.Dialog
import lava.designsystem.component.Text
import lava.designsystem.component.TextButton
import lava.designsystem.theme.AppTheme
import lava.provider.config.ProviderConfigAction
import lava.provider.config.ProviderConfigState

/**
 * SP-4 Phase G.2 (2026-05-13). Destructive Remove affordance for
 * user-cloned providers. The button surfaces ONLY when
 * [ProviderConfigState.isClone] is true — original (registered)
 * trackers cannot be removed by the user.
 *
 * The Remove flow is gated by a confirmation Dialog (destructive
 * actions never one-tap). On confirm: the ViewModel invokes
 * `RemoveClonedProviderUseCase` (soft-delete + outbox enqueue) and
 * emits `NavigateBack` so the user returns to Menu where the clone
 * row is now gone.
 */
@Composable
internal fun RemoveCloneSection(
    state: ProviderConfigState,
    onAction: (ProviderConfigAction) -> Unit,
) {
    if (!state.isClone) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppTheme.spaces.large, vertical = AppTheme.spaces.medium),
    ) {
        TextButton(
            text = "Remove this clone…",
            color = AppTheme.colors.error,
            onClick = { onAction(ProviderConfigAction.OpenRemoveCloneDialog) },
        )
    }

    if (state.showRemoveCloneDialog) {
        Dialog(
            title = { Text("Remove clone?") },
            text = {
                Text(
                    "Removes \"${state.displayName.ifEmpty { state.providerId }}\". " +
                        "Your credentials are preserved — only this clone's mirror + sync state is removed.",
                )
            },
            confirmButton = {
                TextButton(
                    text = "Remove",
                    color = AppTheme.colors.error,
                    onClick = { onAction(ProviderConfigAction.ConfirmRemoveClone) },
                )
            },
            dismissButton = {
                TextButton(
                    text = "Cancel",
                    onClick = { onAction(ProviderConfigAction.DismissRemoveCloneDialog) },
                )
            },
            onDismissRequest = { onAction(ProviderConfigAction.DismissRemoveCloneDialog) },
        )
    }
}

package lava.provider.config.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import lava.credentials.model.CredentialsEntry
import lava.designsystem.component.Body
import lava.designsystem.component.BodyLarge
import lava.designsystem.component.BodySmall
import lava.designsystem.component.ModalBottomSheet
import lava.designsystem.component.Text
import lava.designsystem.component.TextButton
import lava.designsystem.theme.AppTheme
import lava.provider.config.ProviderConfigAction
import lava.provider.config.ProviderConfigState

@Composable
internal fun CredentialsSection(
    state: ProviderConfigState,
    onAction: (ProviderConfigAction) -> Unit,
    onOpenCredentialsManager: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppTheme.spaces.large, vertical = AppTheme.spaces.medium),
    ) {
        Text(
            text = "Credentials",
            style = AppTheme.typography.titleSmall,
            color = AppTheme.colors.primary,
        )
        Spacer(Modifier.padding(vertical = AppTheme.spaces.small))
        val bound = state.boundCredential
        if (bound == null) {
            BodySmall(text = "None — anonymous", color = AppTheme.colors.outline)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Body(modifier = Modifier.weight(1f), text = bound.displayName)
                TextButton(
                    text = "Unbind",
                    onClick = { onAction(ProviderConfigAction.UnbindCredential) },
                )
            }
        }
        Spacer(Modifier.padding(vertical = AppTheme.spaces.small))
        Row {
            TextButton(
                text = "Assign existing…",
                onClick = { onAction(ProviderConfigAction.OpenAssignSheet) },
            )
            Spacer(Modifier.width(AppTheme.spaces.medium))
            TextButton(
                text = "Create new…",
                onClick = onOpenCredentialsManager,
            )
        }
    }

    AssignSheet(
        visible = state.showAssignSheet,
        entries = state.availableCredentials,
        onSelect = { onAction(ProviderConfigAction.BindCredential(it.id)) },
        onDismiss = { onAction(ProviderConfigAction.DismissAssignSheet) },
    )
}

@Composable
private fun AssignSheet(
    visible: Boolean,
    entries: List<CredentialsEntry>,
    onSelect: (CredentialsEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(visible = visible, onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(AppTheme.spaces.large)) {
            BodyLarge(text = "Assign credential")
            Spacer(Modifier.padding(vertical = AppTheme.spaces.small))
            if (entries.isEmpty()) {
                BodySmall(
                    text = "No credentials. Create one in the Credentials Manager first.",
                    color = AppTheme.colors.outline,
                )
            }
            entries.forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(entry) }
                        .padding(PaddingValues(vertical = AppTheme.spaces.medium)),
                ) {
                    Body(text = entry.displayName.ifEmpty { "(unnamed)" })
                }
            }
        }
    }
}

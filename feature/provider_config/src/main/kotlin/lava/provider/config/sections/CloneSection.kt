package lava.provider.config.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import lava.designsystem.component.Dialog
import lava.designsystem.component.OutlinedTextField
import lava.designsystem.component.Text
import lava.designsystem.component.TextButton
import lava.designsystem.theme.AppTheme
import lava.provider.config.ProviderConfigAction
import lava.provider.config.ProviderConfigState

@Composable
internal fun CloneSection(
    state: ProviderConfigState,
    onAction: (ProviderConfigAction) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppTheme.spaces.large, vertical = AppTheme.spaces.medium),
    ) {
        TextButton(
            text = "Clone provider…",
            onClick = { onAction(ProviderConfigAction.OpenCloneDialog) },
        )
    }
    if (state.showCloneDialog) {
        CloneDialog(
            onConfirm = { name, url -> onAction(ProviderConfigAction.ConfirmClone(name, url)) },
            onDismiss = { onAction(ProviderConfigAction.DismissCloneDialog) },
        )
    }
}

@Composable
private fun CloneDialog(
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var displayName by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    Dialog(
        title = { Text("Clone provider") },
        text = {
            Column {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display name") },
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = AppTheme.spaces.small),
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Primary URL") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                text = "Clone",
                enabled = displayName.isNotBlank() && url.isNotBlank(),
                onClick = { onConfirm(displayName.trim(), url.trim()) },
            )
        },
        dismissButton = { TextButton(text = "Cancel", onClick = onDismiss) },
        onDismissRequest = onDismiss,
    )
}

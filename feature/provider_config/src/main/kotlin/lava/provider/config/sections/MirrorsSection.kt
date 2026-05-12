package lava.provider.config.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import lava.designsystem.component.Body
import lava.designsystem.component.BodySmall
import lava.designsystem.component.Divider
import lava.designsystem.component.IconButton
import lava.designsystem.component.OutlinedTextField
import lava.designsystem.component.Text
import lava.designsystem.component.TextButton
import lava.designsystem.drawables.LavaIcons
import lava.designsystem.theme.AppTheme
import lava.domain.usecase.ProbeResult
import lava.provider.config.ProviderConfigAction
import lava.provider.config.ProviderConfigState

@Composable
internal fun MirrorsSection(
    state: ProviderConfigState,
    onAction: (ProviderConfigAction) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppTheme.spaces.large, vertical = AppTheme.spaces.medium),
    ) {
        Text(
            text = "Mirrors",
            style = AppTheme.typography.titleSmall,
            color = AppTheme.colors.primary,
        )
        Spacer(Modifier.padding(vertical = AppTheme.spaces.small))
        state.descriptorMirrors.forEach { url ->
            MirrorRow(
                url = url,
                isUserAdded = false,
                probe = state.probeResults[url],
                onProbe = { onAction(ProviderConfigAction.ProbeMirror(url)) },
                onRemove = null,
            )
            Divider()
        }
        state.userMirrors.forEach { url ->
            MirrorRow(
                url = url,
                isUserAdded = true,
                probe = state.probeResults[url],
                onProbe = { onAction(ProviderConfigAction.ProbeMirror(url)) },
                onRemove = { onAction(ProviderConfigAction.RemoveMirror(url)) },
            )
            Divider()
        }
        Spacer(Modifier.padding(vertical = AppTheme.spaces.small))
        AddMirrorRow(onAdd = { onAction(ProviderConfigAction.AddMirror(it)) })
    }
}

@Composable
private fun MirrorRow(
    url: String,
    isUserAdded: Boolean,
    probe: ProbeResult?,
    onProbe: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppTheme.spaces.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Body(text = url)
            BodySmall(text = probeLabel(probe, isUserAdded), color = AppTheme.colors.outline)
        }
        TextButton(text = "Probe", onClick = onProbe)
        if (onRemove != null) {
            IconButton(
                icon = LavaIcons.Delete,
                contentDescription = "Remove mirror",
                tint = AppTheme.colors.error,
                onClick = onRemove,
            )
        }
    }
}

@Composable
private fun AddMirrorRow(onAdd: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            modifier = Modifier.weight(1f),
            value = input,
            onValueChange = { input = it },
            label = { Text("Add mirror URL") },
            singleLine = true,
        )
        Spacer(Modifier.width(AppTheme.spaces.small))
        TextButton(
            text = "Add",
            enabled = input.isNotBlank(),
            onClick = {
                onAdd(input.trim())
                input = ""
            },
        )
    }
}

private fun probeLabel(result: ProbeResult?, isUser: Boolean): String {
    val prefix = if (isUser) "user" else "bundled"
    return when (result) {
        null -> prefix
        ProbeResult.Reachable -> "$prefix • reachable"
        is ProbeResult.Unhealthy -> "$prefix • HTTP ${result.status}"
        is ProbeResult.Unreachable -> "$prefix • unreachable (${result.reason})"
    }
}

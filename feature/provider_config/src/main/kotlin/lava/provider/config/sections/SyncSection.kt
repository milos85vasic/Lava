package lava.provider.config.sections

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import lava.designsystem.component.Body
import lava.designsystem.theme.AppTheme
import lava.provider.config.ProviderConfigAction

@Composable
internal fun SyncSection(
    enabled: Boolean,
    onAction: (ProviderConfigAction) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppTheme.spaces.large, vertical = AppTheme.spaces.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Body(
            text = "Sync this provider",
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = enabled,
            onCheckedChange = { onAction(ProviderConfigAction.ToggleSync) },
        )
    }
}

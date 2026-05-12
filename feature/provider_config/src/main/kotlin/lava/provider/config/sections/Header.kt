package lava.provider.config.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import lava.designsystem.component.BodySmall
import lava.designsystem.component.Text
import lava.designsystem.theme.AppTheme
import lava.provider.config.ProviderConfigState

@Composable
internal fun Header(state: ProviderConfigState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(AppTheme.spaces.large),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box16Dot(state)
        Spacer(Modifier.width(AppTheme.spaces.medium))
        Column {
            Text(
                text = state.displayName.ifEmpty { state.providerId },
                style = AppTheme.typography.titleMedium,
            )
            BodySmall(
                text = state.descriptor?.trackerId ?: state.providerId,
                color = AppTheme.colors.outline,
            )
        }
    }
}

@Composable
private fun Box16Dot(state: ProviderConfigState) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(16.dp)
            .background(state.color, CircleShape),
    )
}

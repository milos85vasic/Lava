package lava.feature.tracker.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import lava.designsystem.component.Text
import lava.designsystem.theme.AppTheme
import lava.sdk.api.HealthState

/**
 * Pure stateless renderer: a colored dot + label for one [HealthState].
 *
 * Color mapping:
 *  - HEALTHY   → green
 *  - DEGRADED  → amber
 *  - UNHEALTHY → red
 *  - UNKNOWN   → gray
 *
 * Added in SP-3a Phase 4 (Task 4.14).
 */
@Composable
fun HealthIndicator(
    health: HealthState,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(10.dp)
                .background(
                    color = when (health) {
                        HealthState.HEALTHY -> AppTheme.colors.accentGreen
                        HealthState.DEGRADED -> AppTheme.colors.accentOrange
                        HealthState.UNHEALTHY -> AppTheme.colors.accentRed
                        HealthState.UNKNOWN -> AppTheme.colors.outline
                    },
                    shape = CircleShape,
                ),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = labelFor(health),
            style = AppTheme.typography.labelMedium,
            color = AppTheme.colors.outline,
        )
    }
}

private fun labelFor(health: HealthState): String = when (health) {
    HealthState.HEALTHY -> "Healthy"
    HealthState.DEGRADED -> "Degraded"
    HealthState.UNHEALTHY -> "Unhealthy"
    HealthState.UNKNOWN -> "Unknown"
}

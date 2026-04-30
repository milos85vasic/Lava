package lava.feature.tracker_settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
                .background(color = colorFor(health), shape = CircleShape),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = labelFor(health),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun colorFor(health: HealthState): Color = when (health) {
    HealthState.HEALTHY -> Color(0xFF1B5E20) // green 900
    HealthState.DEGRADED -> Color(0xFFFFA000) // amber 700
    HealthState.UNHEALTHY -> Color(0xFFB00020) // red 800
    HealthState.UNKNOWN -> Color(0xFF9E9E9E) // grey 500
}

private fun labelFor(health: HealthState): String = when (health) {
    HealthState.HEALTHY -> "Healthy"
    HealthState.DEGRADED -> "Degraded"
    HealthState.UNHEALTHY -> "Unhealthy"
    HealthState.UNKNOWN -> "Unknown"
}

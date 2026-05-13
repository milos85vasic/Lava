package lava.provider.config.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import lava.designsystem.component.Body
import lava.designsystem.component.BodySmall
import lava.designsystem.component.TextButton
import lava.designsystem.theme.AppTheme
import lava.provider.config.ProviderConfigAction
import lava.provider.config.ProviderConfigState

/**
 * SP-4 Phase C — transitional active-tracker affordance.
 *
 * The deleted `:feature:tracker_settings` Trackers screen carried the
 * active-tracker radio. Phase B + the per-provider config screen
 * absorb every other Trackers-screen capability; this small section
 * preserves the one remaining unique action ("make this provider the
 * SDK's active target") inside the same screen the user is already on.
 *
 * Removed when Phase D's multi-provider parallel search ships
 * (parallel fan-out makes `activeTrackerId` semantically meaningless).
 */
@Composable
internal fun ActiveTrackerSection(
    state: ProviderConfigState,
    onAction: (ProviderConfigAction) -> Unit,
) {
    val active = state.activeTrackerId != null && state.activeTrackerId == state.providerId
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppTheme.spaces.large, vertical = AppTheme.spaces.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Body(text = if (active) "Active provider" else "Inactive")
            BodySmall(
                text = if (active) {
                    "Searches and browse calls go through this provider."
                } else {
                    "Tap Make active to route searches and browse calls here."
                },
                color = AppTheme.colors.outline,
            )
        }
        if (!active) {
            TextButton(text = "Make active", onClick = { onAction(ProviderConfigAction.MakeActive) })
        }
    }
}

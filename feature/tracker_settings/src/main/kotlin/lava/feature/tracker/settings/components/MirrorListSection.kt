package lava.feature.tracker.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import lava.designsystem.component.Divider
import lava.designsystem.component.IconButton
import lava.designsystem.component.Text
import lava.designsystem.component.TextButton
import lava.designsystem.drawables.LavaIcons
import lava.designsystem.theme.AppTheme
import lava.feature.tracker.settings.R
import lava.sdk.api.MirrorState
import lava.sdk.api.MirrorUrl

/**
 * Compose component: renders one tracker's mirror health table plus an
 * "Add custom mirror" button. Each row shows a [HealthIndicator], the
 * mirror URL, the priority + protocol badge, and an optional remove
 * button when the mirror was supplied by the user (i.e. its URL is in
 * [customMirrorUrls]).
 *
 * Added in SP-3a Phase 4 (Task 4.13).
 */
@Composable
fun MirrorListSection(
    trackerId: String,
    states: List<MirrorState>,
    customMirrorUrls: Set<String>,
    onAddCustomMirror: () -> Unit,
    onRemoveMirror: (String) -> Unit,
    onProbeNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppTheme.spaces.large, vertical = AppTheme.spaces.medium),
    ) {
        Text(
            text = stringResource(R.string.tracker_settings_mirrors_for, trackerId),
            style = AppTheme.typography.titleSmall,
            color = AppTheme.colors.primary,
        )
        Spacer(modifier = Modifier.padding(vertical = AppTheme.spaces.small))
        if (states.isEmpty()) {
            Text(
                text = stringResource(R.string.tracker_settings_no_mirrors),
                style = AppTheme.typography.bodySmall,
                color = AppTheme.colors.outline,
            )
        } else {
            states.sortedBy { it.mirror.priority }.forEach { state ->
                MirrorRow(
                    state = state,
                    isUser = state.mirror.url in customMirrorUrls,
                    onRemove = { onRemoveMirror(state.mirror.url) },
                )
                Divider()
            }
        }
        Spacer(modifier = Modifier.padding(vertical = AppTheme.spaces.small))
        Row {
            TextButton(
                text = stringResource(R.string.tracker_settings_add_custom),
                onClick = onAddCustomMirror,
            )
            Spacer(modifier = Modifier.width(AppTheme.spaces.medium))
            TextButton(
                text = stringResource(R.string.tracker_settings_probe_now),
                onClick = onProbeNow,
            )
        }
    }
}

@Composable
private fun MirrorRow(
    state: MirrorState,
    isUser: Boolean,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppTheme.spaces.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HealthIndicator(health = state.health)
        Spacer(modifier = Modifier.width(AppTheme.spaces.mediumLarge))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = state.mirror.url, style = AppTheme.typography.bodyMedium)
            Text(
                text = badgeText(state.mirror),
                style = AppTheme.typography.bodySmall,
                color = AppTheme.colors.outline,
            )
        }
        if (isUser) {
            IconButton(
                icon = LavaIcons.Delete,
                contentDescription = stringResource(R.string.tracker_settings_remove_mirror),
                onClick = onRemove,
            )
        }
    }
}

private fun badgeText(mirror: MirrorUrl): String =
    "priority ${mirror.priority} \u2022 ${mirror.protocol.name}"

package lava.feature.tracker.settings.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import lava.designsystem.component.Divider
import lava.designsystem.component.Icon
import lava.designsystem.component.Text
import lava.designsystem.drawables.LavaIcons
import lava.designsystem.theme.AppTheme
import lava.feature.tracker.settings.R
import lava.tracker.api.TrackerDescriptor

/**
 * Compose component that lists every registered TrackerDescriptor as a
 * tappable row. The row for [activeTrackerId] shows a CheckCircle marker.
 * Added in SP-3a Phase 4 (Task 4.12).
 */
@Composable
fun TrackerSelectorList(
    trackers: List<TrackerDescriptor>,
    activeTrackerId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(trackers, key = { it.trackerId }) { tracker ->
            TrackerSelectorRow(
                tracker = tracker,
                isActive = tracker.trackerId == activeTrackerId,
                onClick = { onSelect(tracker.trackerId) },
            )
            Divider()
        }
    }
}

@Composable
private fun TrackerSelectorRow(
    tracker: TrackerDescriptor,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val primary = tracker.baseUrls.firstOrNull { it.isPrimary }?.url
        ?: tracker.baseUrls.firstOrNull()?.url
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = AppTheme.spaces.large, vertical = AppTheme.spaces.mediumLarge),
    ) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tracker.displayName,
                    style = AppTheme.typography.titleMedium,
                )
                if (primary != null) {
                    Text(
                        text = primary,
                        style = AppTheme.typography.bodyMedium,
                        color = AppTheme.colors.outline,
                    )
                }
            }
            if (isActive) {
                Icon(
                    icon = LavaIcons.Selected,
                    contentDescription = stringResource(R.string.tracker_settings_active),
                    tint = AppTheme.colors.primary,
                )
            }
        }
    }
}

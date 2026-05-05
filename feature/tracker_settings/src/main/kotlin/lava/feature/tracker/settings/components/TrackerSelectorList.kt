package lava.feature.tracker.settings.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
 *
 * 2026-05-05 fix (Crashlytics §6.O closure log
 * .lava-ci-evidence/crashlytics-resolved/2026-05-05-tracker-settings-nested-scroll.md):
 * was a `LazyColumn` — that's the canonical "Vertically scrollable
 * component was measured with an infinite maximum height constraint"
 * crash when nested inside the parent screen's `Column(verticalScroll)`.
 * The tracker list is bounded (typically ≤ 6 entries), so a plain
 * Column is the correct choice — no virtualization needed and no
 * nested-scroll measurement conflict.
 */
@Composable
fun TrackerSelectorList(
    trackers: List<TrackerDescriptor>,
    activeTrackerId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        for (tracker in trackers) {
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

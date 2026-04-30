package lava.feature.tracker.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import lava.sdk.api.HealthState
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
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = "Mirrors for $trackerId",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.padding(vertical = 4.dp))
        if (states.isEmpty()) {
            Text(
                text = "No mirrors known. Tap \"Add custom\" to register one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            states.sortedBy { it.mirror.priority }.forEach { state ->
                MirrorRow(
                    state = state,
                    isUser = state.mirror.url in customMirrorUrls,
                    onRemove = { onRemoveMirror(state.mirror.url) },
                )
                HorizontalDivider()
            }
        }
        Spacer(modifier = Modifier.padding(vertical = 4.dp))
        Row {
            TextButton(onClick = onAddCustomMirror) { Text("Add custom mirror") }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = onProbeNow) { Text("Probe now") }
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
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HealthIndicator(health = state.health)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = state.mirror.url, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = badgeText(state.mirror),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isUser) {
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Remove custom mirror",
                )
            }
        }
    }
}

private fun badgeText(mirror: MirrorUrl): String =
    "priority ${mirror.priority} • ${mirror.protocol.name}"

/**
 * Convenience overload for callers that don't have a state map and just
 * want to render an empty section with the buttons enabled. Kept for
 * symmetry with [TrackerSelectorList]'s simpler shape.
 */
@Composable
@Suppress("unused") // public API surface
fun MirrorListSectionEmpty(
    trackerId: String,
    onAddCustomMirror: () -> Unit,
    onProbeNow: () -> Unit,
) {
    MirrorListSection(
        trackerId = trackerId,
        states = emptyList(),
        customMirrorUrls = emptySet(),
        onAddCustomMirror = onAddCustomMirror,
        onRemoveMirror = {},
        onProbeNow = onProbeNow,
    )
}

@Suppress("unused")
private fun describeHealth(h: HealthState): String = h.name

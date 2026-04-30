package lava.feature.tracker_settings

import lava.sdk.api.MirrorState
import lava.sdk.api.MirrorUrl
import lava.tracker.api.TrackerDescriptor

/**
 * MVI state for the Tracker Settings screen. Added in SP-3a Phase 4
 * (Task 4.10).
 *
 * - [availableTrackers]: every TrackerDescriptor the SDK has registered.
 * - [activeTrackerId]: the id LavaTrackerSdk currently routes calls to.
 * - [mirrorHealthByTracker]: the snapshot the SDK exposes for each
 *   tracker's mirrors (HEALTHY / DEGRADED / UNHEALTHY / UNKNOWN dots).
 * - [customMirrors]: the user-supplied entries from UserMirrorRepository.
 * - [showAddMirrorDialog] + [addMirrorTargetTracker]: dialog state for
 *   the AddCustomMirrorDialog component (Task 4.15).
 * - [error]: surfaced as a Snackbar message when set.
 */
data class TrackerSettingsState(
    val loading: Boolean = false,
    val activeTrackerId: String = "rutracker",
    val availableTrackers: List<TrackerDescriptor> = emptyList(),
    val mirrorHealthByTracker: Map<String, List<MirrorState>> = emptyMap(),
    val customMirrors: Map<String, List<MirrorUrl>> = emptyMap(),
    val showAddMirrorDialog: Boolean = false,
    val addMirrorTargetTracker: String? = null,
    val error: String? = null,
)

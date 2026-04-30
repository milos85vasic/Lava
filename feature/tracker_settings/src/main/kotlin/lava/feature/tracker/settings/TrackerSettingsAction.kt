package lava.feature.tracker.settings

import lava.sdk.api.Protocol

/**
 * MVI actions emitted by the Tracker Settings screen. Added in SP-3a Phase 4
 * (Task 4.10).
 */
sealed class TrackerSettingsAction {
    data object Load : TrackerSettingsAction()
    data class SwitchActive(val trackerId: String) : TrackerSettingsAction()
    data class OpenAddMirrorDialog(val trackerId: String) : TrackerSettingsAction()
    data object DismissAddMirrorDialog : TrackerSettingsAction()
    data class AddCustomMirror(
        val trackerId: String,
        val url: String,
        val priority: Int,
        val protocol: Protocol,
    ) : TrackerSettingsAction()
    data class RemoveCustomMirror(val trackerId: String, val url: String) : TrackerSettingsAction()
    data class ProbeNow(val trackerId: String) : TrackerSettingsAction()
}

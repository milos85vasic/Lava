package lava.feature.tracker.settings

/**
 * MVI side effects for the Tracker Settings screen. Added in SP-3a Phase 4
 * (Task 4.10).
 */
sealed class TrackerSettingsSideEffect {
    data class ShowToast(val message: String) : TrackerSettingsSideEffect()
}

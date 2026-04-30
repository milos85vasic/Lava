package lava.feature.tracker_settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import lava.tracker.client.LavaTrackerSdk
import lava.tracker.client.persistence.UserMirrorRepository
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.syntax.simple.intent
import org.orbitmvi.orbit.syntax.simple.postSideEffect
import org.orbitmvi.orbit.syntax.simple.reduce
import org.orbitmvi.orbit.viewmodel.container
import javax.inject.Inject

/**
 * Orbit MVI ViewModel for the Tracker Settings screen. Added in SP-3a
 * Phase 4 (Task 4.11).
 *
 * Dependencies are real production classes (no UseCase indirection — the
 * SDK is the use-case-equivalent surface for tracker concerns):
 * - LavaTrackerSdk: source of registered trackers, active id,
 *   probeMirrorsFor / observeMirrorHealth, switchTracker.
 * - UserMirrorRepository: source of user-supplied mirrors.
 *
 * Per :feature:CLAUDE.md and Seventh Law clause 4(a), ViewModel tests
 * MUST wire these as real instances (not mockk) so the test traverses
 * the same surfaces the user touches.
 */
@HiltViewModel
class TrackerSettingsViewModel @Inject constructor(
    private val sdk: LavaTrackerSdk,
    private val userMirrorRepo: UserMirrorRepository,
) : ContainerHost<TrackerSettingsState, TrackerSettingsSideEffect>, ViewModel() {

    override val container = container<TrackerSettingsState, TrackerSettingsSideEffect>(
        initialState = TrackerSettingsState(loading = true),
        onCreate = { load() },
    )

    fun onAction(action: TrackerSettingsAction) = intent {
        when (action) {
            TrackerSettingsAction.Load -> load()
            is TrackerSettingsAction.SwitchActive -> {
                sdk.switchTracker(action.trackerId)
                reduce { state.copy(activeTrackerId = action.trackerId) }
                postSideEffect(TrackerSettingsSideEffect.ShowToast("Switched to ${action.trackerId}"))
            }
            is TrackerSettingsAction.OpenAddMirrorDialog ->
                reduce { state.copy(showAddMirrorDialog = true, addMirrorTargetTracker = action.trackerId) }
            TrackerSettingsAction.DismissAddMirrorDialog ->
                reduce { state.copy(showAddMirrorDialog = false, addMirrorTargetTracker = null) }
            is TrackerSettingsAction.AddCustomMirror -> {
                userMirrorRepo.add(
                    trackerId = action.trackerId,
                    url = action.url,
                    priority = action.priority,
                    protocol = action.protocol,
                )
                reduce { state.copy(showAddMirrorDialog = false, addMirrorTargetTracker = null) }
                load()
            }
            is TrackerSettingsAction.RemoveCustomMirror -> {
                userMirrorRepo.remove(action.trackerId, action.url)
                load()
            }
            is TrackerSettingsAction.ProbeNow -> {
                sdk.probeMirrorsFor(action.trackerId)
                load()
                postSideEffect(TrackerSettingsSideEffect.ShowToast("Probed ${action.trackerId}"))
            }
        }
    }

    private suspend fun org.orbitmvi.orbit.syntax.simple.SimpleSyntax<TrackerSettingsState, TrackerSettingsSideEffect>.load() {
        reduce { state.copy(loading = true, error = null) }
        try {
            val trackers = sdk.listAvailableTrackers()
            val active = sdk.activeTrackerId()
            val health = trackers.associate { d ->
                val states = try {
                    sdk.observeMirrorHealth(d.trackerId).first()
                } catch (_: Throwable) {
                    emptyList()
                }
                d.trackerId to states
            }
            val custom = trackers.associate { d ->
                d.trackerId to userMirrorRepo.loadAsMirrorUrls(d.trackerId)
            }
            reduce {
                state.copy(
                    loading = false,
                    availableTrackers = trackers,
                    activeTrackerId = active,
                    mirrorHealthByTracker = health,
                    customMirrors = custom,
                    error = null,
                )
            }
        } catch (t: Throwable) {
            reduce { state.copy(loading = false, error = t.message ?: "load failed") }
        }
    }
}

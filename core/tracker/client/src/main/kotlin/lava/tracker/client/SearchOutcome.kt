package lava.tracker.client

import lava.tracker.api.TrackerCapability
import lava.tracker.api.model.SearchResult

/**
 * Result of a [LavaTrackerSdk.search] call.
 *
 * Capability Honesty (constitutional clause 6.E): if the active tracker doesn't
 * declare [lava.tracker.api.TrackerCapability.SEARCH], the SDK returns
 * [Failure] with `reason = "tracker '<id>' does not support SEARCH"` rather
 * than throwing. Callers (feature ViewModels) MUST exhaustively `when` on this
 * sealed type.
 *
 * Cross-tracker fallback (SP-3a Phase 4 Task 4.7): when the active tracker
 * has exhausted all its mirrors, the SDK emits [CrossTrackerFallbackProposed]
 * carrying a `resumeWith` lambda the UI invokes if the user accepts the
 * one-tap fallback modal (decision 7a-ii). The lambda re-runs the search
 * on the proposed alternative tracker and returns its own outcome.
 */
sealed interface SearchOutcome {
    data class Success(
        val result: SearchResult,
        val viaTracker: String,
    ) : SearchOutcome

    data class Failure(
        val reason: String,
        val triedTrackers: List<String> = emptyList(),
        val cause: Throwable? = null,
    ) : SearchOutcome

    data class CrossTrackerFallbackProposed(
        val failedTrackerId: String,
        val proposedTrackerId: String,
        val capability: TrackerCapability,
        val resumeWith: suspend () -> SearchOutcome,
    ) : SearchOutcome
}

package lava.tracker.client

import lava.tracker.api.TrackerCapability
import lava.tracker.api.model.BrowseResult

/**
 * Result of a [LavaTrackerSdk.browse] call. Sibling of [SearchOutcome] — same
 * Capability Honesty contract; same exhaustive-when expectation on consumers.
 *
 * Cross-tracker fallback (SP-3a Phase 4 Task 4.7): when all mirrors fail,
 * the SDK emits [CrossTrackerFallbackProposed] with a `resumeWith` lambda
 * the UI invokes if the user accepts the modal.
 */
sealed interface BrowseOutcome {
    data class Success(
        val result: BrowseResult,
        val viaTracker: String,
    ) : BrowseOutcome

    data class Failure(
        val reason: String,
        val triedTrackers: List<String> = emptyList(),
        val cause: Throwable? = null,
    ) : BrowseOutcome

    data class CrossTrackerFallbackProposed(
        val failedTrackerId: String,
        val proposedTrackerId: String,
        val capability: TrackerCapability,
        val resumeWith: suspend () -> BrowseOutcome,
    ) : BrowseOutcome
}

package lava.tracker.client

import lava.tracker.api.model.SearchResult

/**
 * Result of a [LavaTrackerSdk.search] call.
 *
 * Capability Honesty (constitutional clause 6.E): if the active tracker doesn't
 * declare [lava.tracker.api.TrackerCapability.SEARCH], the SDK returns
 * [Failure] with `reason = "tracker '<id>' does not support SEARCH"` rather
 * than throwing. Callers (feature ViewModels) MUST exhaustively `when` on this
 * sealed type so a future [Partial] (added when we layer mirror-fallback in
 * Section H) is treated explicitly, not silently dropped.
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
}

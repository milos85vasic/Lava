package lava.tracker.client

import lava.tracker.api.model.BrowseResult

/**
 * Result of a [LavaTrackerSdk.browse] call. Sibling of [SearchOutcome] — same
 * Capability Honesty contract; same exhaustive-when expectation on consumers.
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
}

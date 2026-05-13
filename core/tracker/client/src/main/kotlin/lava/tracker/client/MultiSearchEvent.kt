package lava.tracker.client

import lava.tracker.api.model.TorrentItem

/**
 * Streaming events emitted by [LavaTrackerSdk.streamMultiSearch].
 *
 * SP-4 Phase D (2026-05-13). Mirrors the shape of the Lava Go API's
 * SSE wire events but operates entirely client-side, fanning out to
 * the registered tracker plugins in parallel via `channelFlow`. The
 * collector reduces the event sequence into the same
 * `SearchResultContent.Streaming` state shape the SSE path already
 * uses, so the Search Results UI is source-agnostic.
 *
 * Event sequence per call:
 *   1. One [ProviderStart] per provider id (parallel; order not
 *      guaranteed across providers).
 *   2. One terminal event per provider — exactly one of:
 *      [ProviderResults], [ProviderFailure], [ProviderUnsupported].
 *   3. Exactly one [AllProvidersDone] after every provider has
 *      reached its terminal event AND the channelFlow completes
 *      normally. Cancellation of the collector suppresses
 *      [AllProvidersDone].
 */
sealed interface MultiSearchEvent {
    val providerId: String

    /**
     * Provider has been resolved + the SDK is about to call its
     * `Searchable.search(...)`. UI should mark the provider as
     * `SEARCHING`.
     */
    data class ProviderStart(
        override val providerId: String,
        val displayName: String,
    ) : MultiSearchEvent

    /**
     * Provider's search completed successfully. [items] is the raw
     * (un-deduplicated) per-provider result page.
     */
    data class ProviderResults(
        override val providerId: String,
        val items: List<TorrentItem>,
    ) : MultiSearchEvent

    /**
     * Provider threw / timed out / returned a network error. The
     * [reason] is a short user-facing message; [cause] is the
     * raw throwable when available (logged but not user-rendered).
     */
    data class ProviderFailure(
        override val providerId: String,
        val reason: String,
        val cause: Throwable? = null,
    ) : MultiSearchEvent

    /**
     * Provider exists but doesn't declare `TrackerCapability.SEARCH`.
     * Phase D treats this as a terminal non-error state — the user
     * is informed that this provider was skipped, not that something
     * failed.
     */
    data class ProviderUnsupported(
        override val providerId: String,
    ) : MultiSearchEvent

    /**
     * All providers have reached their terminal state. The
     * deduplicated [unified] result is the same shape
     * [LavaTrackerSdk.multiSearch] returns, so callers that want a
     * single-shot snapshot can collect the flow with `.last()` and
     * cast to this event. Not emitted if the collector cancels.
     */
    data class AllProvidersDone(
        override val providerId: String = "",
        val unified: UnifiedSearchResult,
    ) : MultiSearchEvent
}

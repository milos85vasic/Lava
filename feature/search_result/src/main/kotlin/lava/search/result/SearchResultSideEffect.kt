package lava.search.result

import lava.models.search.Filter

internal sealed interface SearchResultSideEffect {
    data class OpenSearchInput(val filter: Filter) : SearchResultSideEffect
    data class OpenSearchResult(val filter: Filter) : SearchResultSideEffect

    // LVA-052 — carry the source-provider id so the topic screen can branch the
    // download action (HTTP-file for archiveorg/gutenberg vs `.torrent`). Null
    // for the legacy single-tracker paging path (rutracker-direct), which the
    // topic screen resolves to the active tracker.
    data class OpenTopic(val id: String, val providerId: String?) : SearchResultSideEffect
    data object Back : SearchResultSideEffect
    data object ShowFavoriteToggleError : SearchResultSideEffect

    // SP-3.2 (2026-04-29): user tapped login from the Unauthorized state.
    data object OpenLogin : SearchResultSideEffect

    // SP-3a Phase 4 (Task 4.18): user dismissed the cross-tracker fallback
    // modal — surface the original mirror-exhaustion error as a toast.
    data class ShowFallbackDismissedError(val failedTracker: String) : SearchResultSideEffect
}

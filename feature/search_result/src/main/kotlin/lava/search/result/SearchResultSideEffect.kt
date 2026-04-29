package lava.search.result

import lava.models.search.Filter

internal sealed interface SearchResultSideEffect {
    data class OpenSearchInput(val filter: Filter) : SearchResultSideEffect
    data class OpenSearchResult(val filter: Filter) : SearchResultSideEffect
    data class OpenTopic(val id: String) : SearchResultSideEffect
    data object Back : SearchResultSideEffect
    data object ShowFavoriteToggleError : SearchResultSideEffect
    // SP-3.2 (2026-04-29): user tapped login from the Unauthorized state.
    data object OpenLogin : SearchResultSideEffect
}

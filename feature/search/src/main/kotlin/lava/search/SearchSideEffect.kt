package lava.search

import lava.models.search.Filter

internal sealed interface SearchSideEffect {
    data object OpenLogin : SearchSideEffect
    data object OpenSearchInput : SearchSideEffect
    data class OpenSearch(val filter: Filter) : SearchSideEffect
}

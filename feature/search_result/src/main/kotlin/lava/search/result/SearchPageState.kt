package lava.search.result

import lava.domain.model.LoadStates
import lava.models.forum.Category
import lava.models.search.Filter
import lava.models.topic.TopicModel
import lava.models.topic.Torrent

internal data class SearchPageState(
    val filter: Filter,
    val appBarExpanded: Boolean = false,
    val searchContent: SearchResultContent = SearchResultContent.Initial,
    val loadStates: LoadStates = LoadStates.Idle,
)

internal sealed interface SearchResultContent {
    data object Initial : SearchResultContent
    data object Empty : SearchResultContent

    /**
     * SP-3.2 (2026-04-29). The user is not signed in to the upstream
     * tracker; rutracker (and therefore lava-api-go's `/search`) returns
     * 401 for an unauthenticated client. Rendered with a login button
     * instead of the misleading "Nothing found" empty-state — the user
     * REPORTED this exact confusion ("search does not return any results
     * - we get Nothing found screen!"). Sixth-Law clause 3 primary
     * user-visible state: a clear, actionable login prompt.
     */
    data object Unauthorized : SearchResultContent

    data class Content(
        val torrents: List<TopicModel<Torrent>>,
        val categories: List<Category>,
    ) : SearchResultContent
}

internal val SearchPageState.categories
    get() = when (searchContent) {
        is SearchResultContent.Content -> searchContent.categories
        is SearchResultContent.Empty -> emptyList()
        is SearchResultContent.Initial -> emptyList()
        is SearchResultContent.Unauthorized -> emptyList()
    }

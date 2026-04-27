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
    }

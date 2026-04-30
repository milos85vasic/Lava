package lava.search.result

import lava.models.forum.Category
import lava.models.search.Order
import lava.models.search.Period
import lava.models.search.Sort
import lava.models.topic.Author
import lava.models.topic.Topic
import lava.models.topic.TopicModel

internal sealed interface SearchResultAction {
    data class FavoriteClick(val topicModel: TopicModel<out Topic>) : SearchResultAction
    data class SetAuthor(val author: Author?) : SearchResultAction
    data class SetCategories(val categories: List<Category>?) : SearchResultAction
    data class SetOrder(val order: Order) : SearchResultAction
    data class SetPeriod(val period: Period) : SearchResultAction
    data class SetSort(val sort: Sort) : SearchResultAction
    data class TopicClick(val topicModel: TopicModel<out Topic>) : SearchResultAction
    data object BackClick : SearchResultAction
    data object ExpandAppBarClick : SearchResultAction
    data object ListBottomReached : SearchResultAction
    data object RetryClick : SearchResultAction
    data object SearchClick : SearchResultAction

    // SP-3.2 (2026-04-29): triggered from the Unauthorized empty-state.
    data object LoginClick : SearchResultAction

    // SP-3a Phase 4 (Task 4.18): cross-tracker fallback modal events.
    data object FallbackAccept : SearchResultAction
    data object FallbackDismiss : SearchResultAction
}

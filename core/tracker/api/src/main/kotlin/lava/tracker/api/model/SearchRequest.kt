package lava.tracker.api.model

import kotlinx.serialization.Serializable

@Serializable
data class SearchRequest(
    val query: String,
    val categories: List<String> = emptyList(),
    val sort: SortField = SortField.DATE,
    val sortOrder: SortOrder = SortOrder.DESCENDING,
    val author: String? = null,
    val period: TimePeriod? = null,
)

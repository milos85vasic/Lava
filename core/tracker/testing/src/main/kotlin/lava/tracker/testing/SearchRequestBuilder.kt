package lava.tracker.testing

import lava.tracker.api.model.SearchRequest
import lava.tracker.api.model.SortField
import lava.tracker.api.model.SortOrder
import lava.tracker.api.model.TimePeriod

class SearchRequestBuilder {
    var query: String = ""
    var categories: List<String> = emptyList()
    var sort: SortField = SortField.DATE
    var sortOrder: SortOrder = SortOrder.DESCENDING
    var author: String? = null
    var period: TimePeriod? = null

    fun build() = SearchRequest(query, categories, sort, sortOrder, author, period)
}

fun searchRequest(block: SearchRequestBuilder.() -> Unit) = SearchRequestBuilder().apply(block).build()

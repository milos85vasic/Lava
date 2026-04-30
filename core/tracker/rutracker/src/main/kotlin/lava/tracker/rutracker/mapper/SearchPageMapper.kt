package lava.tracker.rutracker.mapper

import lava.network.dto.search.SearchPageDto
import lava.network.dto.search.SearchPeriodDto
import lava.network.dto.search.SearchSortOrderDto
import lava.network.dto.search.SearchSortTypeDto
import lava.tracker.api.model.SearchRequest
import lava.tracker.api.model.SearchResult
import javax.inject.Inject

/**
 * Maps the legacy [SearchPageDto] (rutracker scraper output) to the new
 * tracker-api [SearchResult]. Stub here; populated in Task 2.14.
 */
class SearchPageMapper @Inject constructor() {
    fun toSearchResult(dto: SearchPageDto): SearchResult {
        TODO("populated in Task 2.14")
    }
}

/**
 * Carrier for the legacy positional parameters expected by
 * [lava.tracker.rutracker.domain.GetSearchPageUseCase.invoke]. Lets the new
 * [SearchRequest] (which collapses sort/order/period into typed enums) be
 * unpacked back into the strings the inner API expects.
 */
data class LegacySearchParams(
    val sortType: SearchSortTypeDto?,
    val sortOrder: SearchSortOrderDto?,
    val period: SearchPeriodDto?,
    val categories: String?,
)

/** Reverse mapping (model → legacy DTO enums); populated in Task 2.14. */
fun SearchRequest.toLegacySearchParams(): LegacySearchParams = TODO("populated in Task 2.14")

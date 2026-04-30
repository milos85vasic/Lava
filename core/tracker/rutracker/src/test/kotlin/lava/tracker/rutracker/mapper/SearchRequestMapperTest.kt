package lava.tracker.rutracker.mapper

import lava.network.dto.search.SearchPeriodDto
import lava.network.dto.search.SearchSortOrderDto
import lava.network.dto.search.SearchSortTypeDto
import lava.tracker.api.model.SearchRequest
import lava.tracker.api.model.SortField
import lava.tracker.api.model.SortOrder
import lava.tracker.api.model.TimePeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [SearchRequest.toLegacySearchParams].
 *
 * These tests are pure-function: they construct a [SearchRequest] and assert
 * on the resulting [LegacySearchParams] DTO-enum values. The primary
 * assertions are on the actual rutracker-protocol enums (e.g. that
 * `SortField.SEEDERS` becomes `SearchSortTypeDto.Seeds`, NOT something else).
 * Breaking the mapping (e.g. mapping SEEDERS to `Title`) would cause a clear
 * `expected: <Seeds> but was: <Title>` failure — see Sixth Law clause 2.
 */
class SearchRequestMapperTest {

    @Test
    fun `defaults map DATE descending no period no categories`() {
        val request = SearchRequest(query = "ubuntu")

        val legacy = request.toLegacySearchParams()

        assertEquals(SearchSortTypeDto.Date, legacy.sortType)
        assertEquals(SearchSortOrderDto.Descending, legacy.sortOrder)
        assertNull("no period filter when SearchRequest.period is null", legacy.period)
        assertNull("empty categories list collapses to null filter", legacy.categories)
    }

    @Test
    fun `categories list joins with comma`() {
        val request = SearchRequest(
            query = "linux",
            categories = listOf("33", "44", "55"),
        )

        val legacy = request.toLegacySearchParams()

        assertEquals("33,44,55", legacy.categories)
    }

    @Test
    fun `period maps from TimePeriod to SearchPeriodDto`() {
        val day = SearchRequest("q", period = TimePeriod.LAST_DAY).toLegacySearchParams()
        val week = SearchRequest("q", period = TimePeriod.LAST_WEEK).toLegacySearchParams()
        val month = SearchRequest("q", period = TimePeriod.LAST_MONTH).toLegacySearchParams()
        val all = SearchRequest("q", period = TimePeriod.ALL_TIME).toLegacySearchParams()

        assertEquals(SearchPeriodDto.Today, day.period)
        assertEquals(SearchPeriodDto.LastWeek, week.period)
        assertEquals(SearchPeriodDto.LastMonth, month.period)
        assertEquals(SearchPeriodDto.AllTime, all.period)
    }

    @Test
    fun `sort field maps to SearchSortTypeDto`() {
        assertEquals(
            SearchSortTypeDto.Seeds,
            SearchRequest("q", sort = SortField.SEEDERS).toLegacySearchParams().sortType,
        )
        assertEquals(
            SearchSortTypeDto.Leeches,
            SearchRequest("q", sort = SortField.LEECHERS).toLegacySearchParams().sortType,
        )
        assertEquals(
            SearchSortTypeDto.Size,
            SearchRequest("q", sort = SortField.SIZE).toLegacySearchParams().sortType,
        )
        assertEquals(
            SearchSortTypeDto.Title,
            SearchRequest("q", sort = SortField.TITLE).toLegacySearchParams().sortType,
        )
        // RELEVANCE has no rutracker equivalent; defaults to Date per plan.
        assertEquals(
            SearchSortTypeDto.Date,
            SearchRequest("q", sort = SortField.RELEVANCE).toLegacySearchParams().sortType,
        )
    }

    @Test
    fun `ascending order maps through`() {
        val legacy = SearchRequest("q", sortOrder = SortOrder.ASCENDING).toLegacySearchParams()
        assertEquals(SearchSortOrderDto.Ascending, legacy.sortOrder)
    }
}

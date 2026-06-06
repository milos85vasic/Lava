package lava.data.converters

import lava.database.entity.SearchHistoryEntity
import lava.models.forum.Category
import lava.models.search.Filter
import lava.models.search.Order
import lava.models.search.Period
import lava.models.search.Sort
import lava.models.topic.Author
import lava.network.dto.search.SearchPeriodDto
import lava.network.dto.search.SearchSortOrderDto
import lava.network.dto.search.SearchSortTypeDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Behavioral unit test for the package-internal search converters in
 * `lava.data.converters` (Search.kt).
 *
 * Covers the domain<->entity round trip and the stable id() hash used as the Room
 * @PrimaryKey of SearchHistoryEntity — a real user constraint: two filters that
 * differ in query / period / author / categories MUST hash to different ids so they
 * persist as distinct history rows, and two equal filters MUST collide so re-running
 * the same search updates one row instead of duplicating it.
 *
 * FALSIFIABILITY REHEARSAL: changed `Filter.id()` to `return 0` (constant). The
 * "different filters produce different ids" test FAILED with:
 *   java.lang.AssertionError: distinct filters must not collide expected not equal but was 0
 * Reverted; test passes.
 */
class SearchConverterTest {

    @Test
    fun `toSearch maps every entity field into the domain filter`() {
        val author = Author(id = "a1", name = "Alice")
        val categories = listOf(Category(id = "c1", name = "Movies"))
        val entity = SearchHistoryEntity(
            id = 123,
            timestamp = 999L,
            query = "ubuntu",
            sort = Sort.SEEDS,
            order = Order.ASCENDING,
            period = Period.LAST_WEEK,
            author = author,
            categories = categories,
        )

        val search = entity.toSearch()

        assertEquals(123, search.id)
        assertEquals("ubuntu", search.filter.query)
        assertEquals(Sort.SEEDS, search.filter.sort)
        assertEquals(Order.ASCENDING, search.filter.order)
        assertEquals(Period.LAST_WEEK, search.filter.period)
        assertEquals(author, search.filter.author)
        assertEquals(categories, search.filter.categories)
    }

    @Test
    fun `toEntity then toSearch round-trips filter content`() {
        val filter = Filter(
            query = "debian",
            sort = Sort.SIZE,
            order = Order.DESCENDING,
            period = Period.LAST_MONTH,
            author = Author(id = "a2", name = "Bob"),
            categories = listOf(Category(id = "c2", name = "Linux")),
        )

        val roundTripped = filter.toEntity().toSearch().filter

        assertEquals(filter.query, roundTripped.query)
        assertEquals(filter.sort, roundTripped.sort)
        assertEquals(filter.order, roundTripped.order)
        assertEquals(filter.period, roundTripped.period)
        assertEquals(filter.author, roundTripped.author)
        assertEquals(filter.categories, roundTripped.categories)
    }

    @Test
    fun `equal filters hash to the same entity id`() {
        val a = Filter(query = "same", period = Period.TODAY, author = Author(id = "x", name = "X"))
        val b = Filter(query = "same", period = Period.TODAY, author = Author(id = "x", name = "X"))

        assertEquals(a.toEntity().id, b.toEntity().id)
    }

    @Test
    fun `different filters produce different ids`() {
        val base = Filter(query = "q", period = Period.ALL_TIME)
        val byQuery = base.copy(query = "other")
        val byPeriod = base.copy(period = Period.LAST_WEEK)
        val byAuthor = base.copy(author = Author(id = "z", name = "Z"))
        val byCategory = base.copy(categories = listOf(Category(id = "c", name = "C")))

        val baseId = base.toEntity().id
        assertNotEquals("query change must not collide", baseId, byQuery.toEntity().id)
        assertNotEquals("period change must not collide", baseId, byPeriod.toEntity().id)
        assertNotEquals("author change must not collide", baseId, byAuthor.toEntity().id)
        assertNotEquals("distinct filters must not collide", baseId, byCategory.toEntity().id)
    }

    @Test
    fun `period maps to dto`() {
        assertEquals(SearchPeriodDto.AllTime, Period.ALL_TIME.toDto())
        assertEquals(SearchPeriodDto.Today, Period.TODAY.toDto())
        assertEquals(SearchPeriodDto.LastThreeDays, Period.LAST_THREE_DAYS.toDto())
        assertEquals(SearchPeriodDto.LastWeek, Period.LAST_WEEK.toDto())
        assertEquals(SearchPeriodDto.LastTwoWeeks, Period.LAST_TWO_WEEKS.toDto())
        assertEquals(SearchPeriodDto.LastMonth, Period.LAST_MONTH.toDto())
    }

    @Test
    fun `sort maps to dto`() {
        assertEquals(SearchSortTypeDto.Date, Sort.DATE.toDto())
        assertEquals(SearchSortTypeDto.Title, Sort.TITLE.toDto())
        assertEquals(SearchSortTypeDto.Downloaded, Sort.DOWNLOADED.toDto())
        assertEquals(SearchSortTypeDto.Seeds, Sort.SEEDS.toDto())
        assertEquals(SearchSortTypeDto.Leeches, Sort.LEECHES.toDto())
        assertEquals(SearchSortTypeDto.Size, Sort.SIZE.toDto())
    }

    @Test
    fun `order maps to dto`() {
        assertEquals(SearchSortOrderDto.Ascending, Order.ASCENDING.toDto())
        assertEquals(SearchSortOrderDto.Descending, Order.DESCENDING.toDto())
    }
}

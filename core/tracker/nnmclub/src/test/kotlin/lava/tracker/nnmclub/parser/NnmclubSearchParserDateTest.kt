package lava.tracker.nnmclub.parser

import kotlinx.datetime.Instant
import lava.tracker.testing.LavaFixtureLoader
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * LVA-028 regression: the nnmclub search parser dropped the publish date even
 * though the search HTML carries a parseable `Date` column (the 5th `<td>`,
 * `yyyy-MM-dd`). Before the fix, [lava.tracker.api.model.TorrentItem.publishDate]
 * was always null. After the fix it MUST carry the date a real user sees in the
 * rendered search row.
 *
 * Fixture `search-normal-2026-05-02.html` row 0 has `<td>2024-01-15</td>` and
 * row 1 has `<td>2024-02-10</td>` — the dates are parsed to the start of the UTC
 * day (`2024-01-15T00:00:00Z` / `2024-02-10T00:00:00Z`).
 */
class NnmclubSearchParserDateTest {

    private val loader = LavaFixtureLoader(tracker = "nnmclub")
    private val parser = NnmclubSearchParser()

    @Test
    fun `parse populates publishDate from the search Date column`() {
        val html = loader.load("search", "search-normal-2026-05-02.html")
        val result = parser.parse(html, pageHint = 0)

        assertEquals("expected 2 items", 2, result.items.size)

        assertEquals(
            "row 0 publishDate must be the 2024-01-15 date column, not null",
            Instant.parse("2024-01-15T00:00:00Z"),
            result.items[0].publishDate,
        )
        assertEquals(
            "row 1 publishDate must be the 2024-02-10 date column, not null",
            Instant.parse("2024-02-10T00:00:00Z"),
            result.items[1].publishDate,
        )
    }
}

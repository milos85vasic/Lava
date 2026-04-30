package lava.tracker.rutor.parser

import lava.tracker.testing.LavaFixtureLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [RuTorBrowseParser] against the three `browse-*-2026-04-30.html` fixtures.
 *
 * Sixth Law clause 2 (falsifiability): assertions are on user-visible mapped state
 * (item titles, sizeBytes, magnet hash, totalPages). A parser that produces empty
 * items lists for browse-normal would fail the items >= 10 assertion loudly.
 */
class RuTorBrowseParserTest {

    private val loader = LavaFixtureLoader(tracker = "rutor")
    private val parser = RuTorBrowseParser()

    @Test
    fun `normal browse page returns 10+ items with sizes and pagination`() {
        val html = loader.load("browse", "browse-normal-2026-04-30.html")
        val result = parser.parse(html, pageHint = 0)

        assertTrue(
            "browse-normal should expose at least 10 rows; got ${result.items.size}",
            result.items.size >= 10,
        )
        // Both column variants exist on this page; every row must surface a size.
        val withoutSize = result.items.filter { it.sizeBytes == null }
        assertTrue(
            "all rows must yield sizeBytes — variable-column hazard offenders=${withoutSize.size}",
            withoutSize.isEmpty(),
        )
        // Magnet infoHash present on every row (40 hex chars).
        val badHash = result.items.filter { it.infoHash?.length != 40 }
        assertTrue(
            "all rows must produce a 40-char infoHash; offenders=${badHash.size}",
            badHash.isEmpty(),
        )
        // Pagination references /browse/6815/0/0/0 → 6816 total pages (0-indexed).
        assertEquals(6816, result.totalPages)
        // currentPage hint round-trips.
        assertEquals(0, result.currentPage)
    }

    @Test
    fun `empty browse page has no items but pagination is still a real page count`() {
        val html = loader.load("browse", "browse-empty-2026-04-30.html")
        val result = parser.parse(html, pageHint = 99999)

        assertTrue("empty page must surface no items, got ${result.items.size}", result.items.isEmpty())
        // The fixture references /browse/99998/, so totalPages = 99998 + 1 = 99999.
        assertEquals(99999, result.totalPages)
        assertEquals(99999, result.currentPage)
    }

    @Test
    fun `deep-pagination page 50 reports correct currentPage and totalPages`() {
        val html = loader.load("browse", "browse-deep-pagination-2026-04-30.html")
        val result = parser.parse(html, pageHint = 50)

        // totalPages = 6815 + 1 = 6816.
        assertEquals(6816, result.totalPages)
        assertEquals(50, result.currentPage)
        assertTrue(
            "deep-pagination page should still surface result rows; got ${result.items.size}",
            result.items.size >= 10,
        )
        // Falsifiability anchor: a parser drift that confuses pagination with rows would
        // produce a TorrentItem named with bracketed page-number text. Reject that.
        val anyPaginationLeak = result.items.any { it.title.matches(Regex("\\d+\\s*-\\s*\\d+")) }
        assertTrue("pagination text must not leak into items", !anyPaginationLeak)
    }
}

package lava.tracker.nnmclub.parser

import lava.tracker.testing.LavaFixtureLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NnmclubSearchParserTest {

    private val loader = LavaFixtureLoader(tracker = "nnmclub")
    private val parser = NnmclubSearchParser()

    @Test
    fun `parse normal search page returns expected items`() {
        val html = loader.load("search", "search-normal-2026-05-02.html")
        val result = parser.parse(html, pageHint = 0)

        assertEquals("expected 2 items", 2, result.items.size)

        val first = result.items[0]
        assertEquals("1001", first.torrentId)
        assertEquals("Ubuntu 24.04 LTS", first.title)
        assertEquals(12, first.seeders)
        assertEquals(3, first.leechers)
        val sizeBytes = first.sizeBytes
        assertTrue("size should be parsed", sizeBytes != null && sizeBytes > 0)
    }

    @Test
    fun `parse empty search page returns empty list`() {
        val html = loader.load("search", "search-empty-2026-05-02.html")
        val result = parser.parse(html, pageHint = 0)

        assertEquals(0, result.items.size)
        assertEquals(1, result.totalPages)
    }

    @Test
    fun `parse computes total pages from pagination links`() {
        val html = loader.load("search", "search-normal-2026-05-02.html")
        val result = parser.parse(html, pageHint = 0)

        // The fixture has a link with start=50, which means page 2 (50/50 + 1)
        assertEquals(2, result.totalPages)
    }
}

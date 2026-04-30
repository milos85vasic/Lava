package lava.tracker.rutor.parser

import lava.tracker.testing.LavaFixtureLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [RuTorSearchParser] against the five `search-*-2026-04-30.html` fixtures.
 *
 * Sixth Law clause 2 (falsifiability): each test asserts on user-visible mapped
 * state — title text, sizeBytes, totalPages, infoHash, magnetUri. Breaking the
 * parser's content-based size selector (e.g. by indexing positionally) makes the
 * edge-columns test fail loudly with sizeBytes = null on every item; the rehearsal
 * is recorded in the parser KDoc.
 */
class RuTorSearchParserTest {

    private val loader = LavaFixtureLoader(tracker = "rutor")
    private val parser = RuTorSearchParser()

    @Test
    fun `normal search results contain at least 10 torrents with sizes`() {
        val html = loader.load("search", "search-normal-2026-04-30.html")
        val result = parser.parse(html, pageHint = 0)

        assertTrue(
            "expected at least 10 result rows for the ubuntu query, got ${result.items.size}",
            result.items.size >= 10,
        )
        // Sixth Law clause 2 falsifiability anchor: assert on a real user-visible title.
        val firstTitle = result.items.first().title
        assertTrue(
            "first title should mention Ubuntu — got '$firstTitle'",
            firstTitle.contains("Ubuntu", ignoreCase = true),
        )
        // Every item must surface a parseable size (the column-content selector contract).
        val withoutSize = result.items.filter { it.sizeBytes == null }
        assertTrue(
            "all rows should yield a sizeBytes; offenders=${withoutSize.map { it.title }}",
            withoutSize.isEmpty(),
        )
        // Magnet + 40-char hex infoHash present on every item.
        val withoutHash = result.items.filter { it.infoHash == null || it.infoHash!!.length != 40 }
        assertTrue(
            "all rows should expose a 40-char infoHash; offenders=${withoutHash.map { it.title }}",
            withoutHash.isEmpty(),
        )
        // Pagination block "Страницы: 1 <a>2</a> <a>3</a>" → 3 total pages.
        assertEquals(3, result.totalPages)
    }

    @Test
    fun `empty search results have no items and totalPages defaults to 1`() {
        val html = loader.load("search", "search-empty-2026-04-30.html")
        val result = parser.parse(html, pageHint = 0)

        assertTrue("empty page must surface no items, got ${result.items.size}", result.items.isEmpty())
        assertEquals(1, result.totalPages)
    }

    @Test
    fun `edge-columns variant still produces sizeBytes for every row`() {
        val html = loader.load("search", "search-edge-columns-2026-04-30.html")
        val result = parser.parse(html, pageHint = 0)

        // The hand-crafted fixture is the 5-column variant for every row. A positional
        // selector would yield sizeBytes = null on every item; a content-based selector
        // recovers the size from whichever td actually carries it.
        assertTrue(
            "expected at least 10 rows in the edge-columns variant, got ${result.items.size}",
            result.items.size >= 10,
        )
        val withoutSize = result.items.filter { it.sizeBytes == null }
        assertTrue(
            "every row must still yield a sizeBytes despite the variable-column layout; " +
                "offenders=${withoutSize.size} of ${result.items.size}",
            withoutSize.isEmpty(),
        )
    }

    @Test
    fun `cyrillic search results carry Cyrillic characters in titles`() {
        val html = loader.load("search", "search-cyrillic-2026-04-30.html")
        val result = parser.parse(html, pageHint = 0)

        assertTrue("cyrillic search should return non-empty items", result.items.isNotEmpty())
        val anyCyrillic = result.items.any { item ->
            item.title.any { it.code in 0x0400..0x04FF }
        }
        assertTrue(
            "at least one item title should contain a Cyrillic letter — got titles=" +
                result.items.take(3).map { it.title },
            anyCyrillic,
        )
        // 20-page pagination block; the parser picks max(int) = 20.
        assertEquals(20, result.totalPages)
        // Falsifiability anchor: a parser drift that returns rutor news_table rows would
        // surface non-Cyrillic English-only news titles. The dedicated div#index scope
        // protects against that.
        val anyNewsTitle = result.items.any { it.title.contains("Путеводитель", ignoreCase = true) }
        assertFalse(
            "must not leak news_table rows; news 'Путеводитель' title must be excluded",
            anyNewsTitle,
        )
    }

    @Test
    fun `malformed search HTML does not throw and returns a SearchResult`() {
        val html = loader.load("search", "search-malformed-2026-04-30.html")
        // The contract is "no throw"; degraded items are acceptable.
        val result = parser.parse(html, pageHint = 0)
        assertNotNull("malformed HTML must still produce a SearchResult", result)
        // currentPage must echo the hint we passed.
        assertEquals(0, result.currentPage)
    }
}

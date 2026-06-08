package lava.tracker.rutor.parser

import lava.tracker.testing.LavaFixtureLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun `empty string and garbage HTML produce an empty-but-valid SearchResult`() {
        // A truncated/garbage response (proxy returned an error body, a redirect stub, etc.)
        // must degrade to zero items + totalPages defaulting to 1 — never an exception, never a
        // negative or zero totalPages that a paginating UI would choke on.
        listOf(
            "",
            "   ",
            "<html><body>503 Service Unavailable</body></html>",
            "<!DOCTYPE html><html><head><title>rutor.info</title></head><body><div id=\"menu\"></div></body></html>",
        ).forEach { html ->
            val result = parser.parse(html, pageHint = 3)
            assertTrue("garbage HTML must surface no items for input '${html.take(20)}'", result.items.isEmpty())
            assertEquals("totalPages must default to 1 for input '${html.take(20)}'", 1, result.totalPages)
            // currentPage echoes the caller hint even on a degraded page.
            assertEquals(3, result.currentPage)
        }
    }

    @Test
    fun `missing optional fields per row degrade to null without dropping the row`() {
        val html = loader.load("search", "search-missing-fields-2026-04-30.html")
        val result = parser.parse(html, pageHint = 0)

        // All four rows are still surfaced — a missing optional field never drops the torrent.
        assertEquals(
            "all four edge rows must be parsed; got titles=${result.items.map { it.title }}",
            4,
            result.items.size,
        )
        val byId = result.items.associateBy { it.torrentId }

        // Row 1: control — every field present.
        val control = byId.getValue("1052665")
        assertEquals(4_563_402_752L, control.sizeBytes) // 4.25 * 2^30
        assertNotNull("control row must carry a magnet", control.magnetUri)
        assertEquals("fb3e518132e636b798c4ae4b346b60578665e09e", control.infoHash)
        assertEquals(7, control.seeders)
        assertEquals(2, control.leechers)

        // Row 2: no magnet anchor → magnetUri and infoHash null, but the row survives with a
        // parseable title and a download URL.
        val noMagnet = byId.getValue("1050403")
        assertNull("row without a magnet anchor must expose null magnetUri", noMagnet.magnetUri)
        assertNull("row without a magnet anchor must expose null infoHash", noMagnet.infoHash)
        assertTrue("title must still be parsed", noMagnet.title.contains("без магнет-ссылки"))
        assertEquals("https://d.rutor.info/download/1050403", noMagnet.downloadUrl)
        assertEquals(4_413_078_896L, noMagnet.sizeBytes) // 4.11 * 2^30 truncated

        // Row 3: no peers spans → seeders/leechers null, size still recovered by content.
        val noPeers = byId.getValue("1049194")
        assertNull("row without span.green must expose null seeders", noPeers.seeders)
        assertNull("row without span.red must expose null leechers", noPeers.leechers)
        assertEquals(4_252_017_623L, noPeers.sizeBytes) // 3.96 * 2^30 truncated

        // Row 4: no parseable size cell (em-dash placeholder) → sizeBytes null, peers still read.
        val noSize = byId.getValue("1049193")
        assertNull("em-dash size cell must yield null sizeBytes", noSize.sizeBytes)
        assertEquals(3, noSize.seeders)
        assertEquals(1, noSize.leechers)
    }

    @Test
    fun `Cyrillic titles are preserved verbatim without mojibake`() {
        val html = loader.load("search", "search-missing-fields-2026-04-30.html")
        val result = parser.parse(html, pageHint = 0)

        // UTF-8 decode must keep the exact Cyrillic prose — a charset regression would surface
        // replacement chars or Latin-1 mojibake, not the literal Russian text.
        val titles = result.items.map { it.title }
        assertTrue(
            "Cyrillic title must round-trip exactly; got $titles",
            titles.any { it.contains("Раздача без магнет-ссылки") },
        )
        assertTrue(
            "no title may contain the Unicode replacement char (mojibake signal); got $titles",
            titles.none { it.contains('�') },
        )
    }
}

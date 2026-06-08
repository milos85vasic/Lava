package lava.tracker.kinozal.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edge-case unit tests for [KinozalSearchParser].
 *
 * These exercise the REAL production parser against malformed, truncated, and
 * partial HTML — the kinds of input a flaky upstream or mid-stream connection
 * drop produces — and assert the user-visible contract: the parser MUST NOT
 * throw and MUST return a valid (possibly empty) [lava.tracker.api.model.SearchResult].
 *
 * Selectors mirror the production fixture `search-normal-2026-05-02.html`
 * (table.tumblers > tr, a.namer with id= in href, span.sider for size/S:/L:,
 * a[href^=magnet:] for the magnet). No fabricated selectors.
 */
class KinozalSearchParserEdgeCaseTest {

    private val parser = KinozalSearchParser()

    @Test
    fun `empty string yields empty result without throwing`() {
        val result = parser.parse("", pageHint = 0)
        assertTrue(result.items.isEmpty())
        assertEquals(0, result.currentPage)
    }

    @Test
    fun `garbage non-html input yields empty result without throwing`() {
        val result = parser.parse("}{ not html at all <<<>>> &&&", pageHint = 2)
        assertTrue(result.items.isEmpty())
        // pageHint is carried through verbatim.
        assertEquals(2, result.currentPage)
    }

    @Test
    fun `truncated html cut mid-row yields empty result without throwing`() {
        // A response that was cut off while streaming the first row: the
        // table opens, the title anchor never closes, the document ends.
        val html = """
            <html><body><table class="tumblers"><tr>
            <td><a href="/details.php?id=999" class="nam
        """.trimIndent()
        val result = parser.parse(html, pageHint = 0)
        // Jsoup tolerates the truncation; the half-written anchor lacks the
        // production a.namer class so no item is emitted, but nothing throws.
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun `row whose title anchor lacks an id is skipped`() {
        // Real upstream occasionally renders a promo/sticky row with a.namer
        // but no id= query param. The parser must drop it, not crash.
        val html = """
            <html><body><table class="tumblers">
            <tr><td><a href="/promo.php" class="namer">No Id Here</a></td></tr>
            <tr><td><a href="/details.php?id=42" class="namer">Real Movie</a>
                <span class="sider">700 MB</span></td></tr>
            </table></body></html>
        """.trimIndent()
        val result = parser.parse(html, pageHint = 0)
        assertEquals(1, result.items.size)
        assertEquals("42", result.items.first().torrentId)
    }

    @Test
    fun `row missing seeders leechers and magnet still parses with null optional fields`() {
        val html = """
            <html><body><table class="tumblers">
            <tr><td><a href="/details.php?id=7" class="namer">Minimal Row</a>
                <span class="sider">1.2 GB</span></td></tr>
            </table></body></html>
        """.trimIndent()
        val result = parser.parse(html, pageHint = 0)
        assertEquals(1, result.items.size)
        val item = result.items.first()
        assertEquals("7", item.torrentId)
        assertEquals("Minimal Row", item.title)
        assertNull(item.seeders)
        assertNull(item.leechers)
        assertNull(item.magnetUri)
        // The search parser deliberately leaves sizeBytes null (size kept as text only).
        assertNull(item.sizeBytes)
    }

    @Test
    fun `magnet present on a row is surfaced verbatim`() {
        val magnet = "magnet:?xt=urn:btih:ABC123DEF4567890ABC123DEF4567890ABC1234"
        val html = """
            <html><body><table class="tumblers">
            <tr><td><a href="/details.php?id=8" class="namer">With Magnet</a>
                <span class="sider">S: 99</span><span class="sider">L: 4</span>
                <a href="$magnet" class="magnet">m</a></td></tr>
            </table></body></html>
        """.trimIndent()
        val result = parser.parse(html, pageHint = 0)
        val item = result.items.single()
        assertEquals(magnet, item.magnetUri)
        assertEquals(99, item.seeders)
        assertEquals(4, item.leechers)
    }

    @Test
    fun `magnet absent on a row leaves magnetUri null`() {
        val html = """
            <html><body><table class="tumblers">
            <tr><td><a href="/details.php?id=9" class="namer">No Magnet</a>
                <span class="sider">S: 1</span></td></tr>
            </table></body></html>
        """.trimIndent()
        val result = parser.parse(html, pageHint = 0)
        assertNull(result.items.single().magnetUri)
    }

    @Test
    fun `non-numeric seeders text degrades to null rather than throwing`() {
        val html = """
            <html><body><table class="tumblers">
            <tr><td><a href="/details.php?id=10" class="namer">Bad Counts</a>
                <span class="sider">S: n/a</span><span class="sider">L: --</span></td></tr>
            </table></body></html>
        """.trimIndent()
        val result = parser.parse(html, pageHint = 0)
        val item = result.items.single()
        assertNull(item.seeders)
        assertNull(item.leechers)
    }

    @Test
    fun `cyrillic title is decoded and surfaced intact`() {
        val html = """
            <html><head><meta charset="utf-8"></head><body><table class="tumblers">
            <tr><td><a href="/details.php?id=11" class="namer">Кино 2024</a></td></tr>
            </table></body></html>
        """.trimIndent()
        val result = parser.parse(html, pageHint = 0)
        assertEquals("Кино 2024", result.items.single().title)
    }

    @Test
    fun `pagination defaults to one page when no page links exist`() {
        val html = """
            <html><body><table class="tumblers">
            <tr><td><a href="/details.php?id=12" class="namer">Solo</a></td></tr>
            </table></body></html>
        """.trimIndent()
        // No a[href*=page=] anchors -> maxPage default 0, totalPages = 0 + 1.
        assertEquals(1, parser.parse(html, pageHint = 0).totalPages)
    }
}

package lava.tracker.nnmclub.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edge-case unit tests for [NnmclubSearchParser].
 *
 * Exercises the REAL production parser against malformed/truncated/partial HTML
 * and asserts the user-visible contract: never throw, always return a valid
 * (possibly empty) [lava.tracker.api.model.SearchResult].
 *
 * Selectors mirror `search-normal-2026-05-02.html` and
 * `search-with-magnet-2026-06-08.html` (table.forumline > tr, skip th rows,
 * a.genmed with t= in href, .seedmed / .leechmed, size in the 6th td,
 * a[href^=magnet:]). No fabricated selectors.
 */
class NnmclubSearchParserEdgeCaseTest {

    private val parser = NnmclubSearchParser()

    @Test
    fun `empty string yields empty result without throwing`() {
        val result = parser.parse("", pageHint = 0)
        assertTrue(result.items.isEmpty())
        assertEquals(0, result.currentPage)
    }

    @Test
    fun `garbage non-html input yields empty result without throwing`() {
        val result = parser.parse("  not html %%% <<>>", pageHint = 1)
        assertTrue(result.items.isEmpty())
        assertEquals(1, result.currentPage)
    }

    @Test
    fun `truncated html cut mid-table yields empty result without throwing`() {
        val html = """
            <html><body><table class="forumline"><tr>
            <td><a href="viewtopic.php?t=500" class="genm
        """.trimIndent()
        val result = parser.parse(html, pageHint = 0)
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun `row whose anchor lacks a t param is skipped`() {
        val html = """
            <html><body><table class="forumline">
            <tr><td><a href="viewforum.php?f=1" class="genmed">Forum Link</a></td></tr>
            <tr><td><a href="viewtopic.php?t=600" class="genmed">Real Topic</a></td>
                <td>a</td><td class="seedmed">3</td><td class="leechmed">1</td><td>d</td><td>700 MB</td></tr>
            </table></body></html>
        """.trimIndent()
        val result = parser.parse(html, pageHint = 0)
        assertEquals(1, result.items.size)
        assertEquals("600", result.items.single().torrentId)
    }

    @Test
    fun `row with fewer than six columns parses with null size`() {
        // The size lives in the 6th td; a short row must not throw and must
        // leave sizeBytes null rather than index out of bounds.
        val html = """
            <html><body><table class="forumline">
            <tr><td><a href="viewtopic.php?t=601" class="genmed">Short Row</a></td>
                <td class="seedmed">5</td></tr>
            </table></body></html>
        """.trimIndent()
        val result = parser.parse(html, pageHint = 0)
        val item = result.items.single()
        assertEquals("601", item.torrentId)
        assertEquals(5, item.seeders)
        assertNull(item.leechers)
        assertNull(item.sizeBytes)
    }

    @Test
    fun `unparseable size text degrades to null`() {
        val html = """
            <html><body><table class="forumline">
            <tr><td><a href="viewtopic.php?t=602" class="genmed">Weird Size</a></td>
                <td>a</td><td class="seedmed">1</td><td class="leechmed">0</td><td>d</td><td>unknown</td></tr>
            </table></body></html>
        """.trimIndent()
        assertNull(parser.parse(html, pageHint = 0).items.single().sizeBytes)
    }

    @Test
    fun `non-breaking-space and comma decimal size is parsed`() {
        // Real nnmclub renders "4,5 GB" with a non-breaking space (U+00A0);
        // the parser normalizes the NBSP and treats comma as the decimal mark.
        val nbsp = " "
        val html = """
            <html><body><table class="forumline">
            <tr><td><a href="viewtopic.php?t=603" class="genmed">Comma Size</a></td>
                <td>a</td><td class="seedmed">1</td><td class="leechmed">0</td><td>d</td><td>4,5${nbsp}GB</td></tr>
            </table></body></html>
        """.trimIndent()
        val size = parser.parse(html, pageHint = 0).items.single().sizeBytes
        assertEquals((4.5 * 1024 * 1024 * 1024).toLong(), size)
    }

    @Test
    fun `magnet present on a row is surfaced verbatim`() {
        val magnet = "magnet:?xt=urn:btih:def4567890abcdef1234567890abcdef12345678&dn=ubuntu"
        val html = """
            <html><body><table class="forumline">
            <tr><td><a href="viewtopic.php?t=604" class="genmed">With Magnet</a>
                <a href="$magnet">[Magnet]</a></td>
                <td>a</td><td class="seedmed">1</td><td class="leechmed">0</td><td>d</td><td>1 GB</td></tr>
            </table></body></html>
        """.trimIndent()
        assertEquals(magnet, parser.parse(html, pageHint = 0).items.single().magnetUri)
    }

    @Test
    fun `magnet absent on a row leaves magnetUri null`() {
        val html = """
            <html><body><table class="forumline">
            <tr><td><a href="viewtopic.php?t=605" class="genmed">No Magnet</a></td>
                <td>a</td><td class="seedmed">1</td><td class="leechmed">0</td><td>d</td><td>1 GB</td></tr>
            </table></body></html>
        """.trimIndent()
        assertNull(parser.parse(html, pageHint = 0).items.single().magnetUri)
    }

    @Test
    fun `pagination defaults to one page when no start links exist`() {
        val html = """
            <html><body><table class="forumline">
            <tr><td><a href="viewtopic.php?t=606" class="genmed">Solo</a></td>
                <td>a</td><td class="seedmed">1</td><td class="leechmed">0</td><td>d</td><td>1 GB</td></tr>
            </table></body></html>
        """.trimIndent()
        assertEquals(1, parser.parse(html, pageHint = 0).totalPages)
    }
}

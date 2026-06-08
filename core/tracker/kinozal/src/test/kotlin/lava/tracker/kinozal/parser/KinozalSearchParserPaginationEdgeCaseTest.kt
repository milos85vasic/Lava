package lava.tracker.kinozal.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pagination + multi-row edge-case tests for [KinozalSearchParser].
 *
 * These exercise the REAL production parser on inputs the existing
 * [KinozalSearchParserEdgeCaseTest] does NOT reach: a result page that carries
 * SEVERAL `page=` anchors (so the "take the max" pagination contract is
 * exercised, not just the single-page default), a body with MORE THAN ONE valid
 * data row (so row ordering / completeness is exercised, not just "1 item"), and
 * the bare-`B` size branch alongside seeders so the size-vs-seeder
 * discrimination in `span.sider` handling is exercised.
 *
 * Bluff-Audit notes (production mutation that flips each test red):
 *  - last page derived from max page anchor: change `parsePagination`'s
 *    `.maxOrNull()` to `.minOrNull()`/`.firstOrNull()` → totalPages wrong.
 *  - every valid data row emitted: collapse `rows.mapNotNull` to first-only → 1 item.
 *  - bare-B size doesn't corrupt seeders: drop the `text.startsWith("S:")` guard.
 */
class KinozalSearchParserPaginationEdgeCaseTest {

    private val parser = KinozalSearchParser()

    @Test
    fun `the last page is derived from the maximum page anchor not the first`() {
        val html = """
            <html><body><table class="tumblers">
            <tr><td><a href="/browse.php?id=1" class="namer">Row</a></td></tr>
            </table>
            <div class="pager">
              <a href="/browse.php?s=q&page=0">1</a>
              <a href="/browse.php?s=q&page=1">2</a>
              <a href="/browse.php?s=q&page=3">4</a>
              <a href="/browse.php?s=q&page=2">3</a>
            </div></body></html>
        """.trimIndent()
        val result = parser.parse(html, pageHint = 1)
        assertEquals(4, result.totalPages)
        assertEquals(1, result.currentPage)
    }

    @Test
    fun `every valid data row is emitted in document order`() {
        val html = """
            <html><body><table class="tumblers">
            <tr><th>Header</th></tr>
            <tr><td><a href="/details.php?id=100" class="namer">First Movie</a>
                <span class="sider">1.5 GB</span></td></tr>
            <tr><td><a href="/details.php?id=200" class="namer">Second Movie</a>
                <span class="sider">700 MB</span></td></tr>
            </table></body></html>
        """.trimIndent()
        val result = parser.parse(html, pageHint = 0)
        assertEquals(2, result.items.size)
        assertEquals("100", result.items[0].torrentId)
        assertEquals("First Movie", result.items[0].title)
        assertEquals("200", result.items[1].torrentId)
        assertEquals("Second Movie", result.items[1].title)
    }

    @Test
    fun `bare-B size on a row does not corrupt the seeders count`() {
        val html = """
            <html><body><table class="tumblers">
            <tr><td><a href="/details.php?id=300" class="namer">Tiny File</a>
                <span class="sider">512 B</span>
                <span class="sider">S: 5</span>
                <span class="sider">L: 0</span></td></tr>
            </table></body></html>
        """.trimIndent()
        val item = parser.parse(html, pageHint = 0).items.single()
        assertEquals("300", item.torrentId)
        assertEquals(5, item.seeders)
        assertEquals(0, item.leechers)
        assertNull(item.sizeBytes)
    }
}

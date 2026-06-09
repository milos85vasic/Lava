package lava.tracker.kinozal.parser

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * LVA-027 regression. KinozalSearchParser parsed the size string into a local
 * var then emitted `sizeBytes = null`, so every Kinozal search row dropped its
 * size. These tests pin the size-string → byte conversion AND the end-to-end
 * flow into TorrentItem.sizeBytes (the user-visible value the size sort/filter +
 * cross-tracker ranking read).
 */
class KinozalSizeParserTest {

    @Test
    fun `parses Latin GB to binary bytes`() {
        assertEquals(1_610_612_736L, KinozalSizeParser.parse("1.5 GB"))
    }

    @Test
    fun `parses comma-decimal`() {
        assertEquals(1_610_612_736L, KinozalSizeParser.parse("1,5 GB"))
    }

    @Test
    fun `parses Cyrillic units defensively`() {
        assertEquals(1_073_741_824L, KinozalSizeParser.parse("1 ГБ"))
    }

    @Test
    fun `non-size text returns null`() {
        assertEquals(null, KinozalSizeParser.parse("S: 12"))
    }

    /**
     * End-to-end: a search row's size flows into TorrentItem.sizeBytes.
     *
     * Falsifiability: revert KinozalSearchParser to `sizeBytes = null` and this
     * fails "expected:<1610612736> but was:<null>".
     */
    @Test
    fun `search row carries sizeBytes end to end`() {
        val html = """
            <html><body><table class="tumblers">
            <tr><th>header</th></tr>
            <tr>
              <td><a class="namer" href="/details.php?id=42">Some Movie</a></td>
              <td>
                <span class="sider">1.5 GB</span>
                <span class="sider">S: 12</span>
                <span class="sider">L: 3</span>
              </td>
              <td><a href="magnet:?xt=urn:btih:abc">m</a></td>
            </tr>
            </table></body></html>
        """.trimIndent()

        val item = KinozalSearchParser().parse(html).items.single()
        assertEquals("42", item.torrentId)
        assertEquals(1_610_612_736L, item.sizeBytes)
        assertEquals(12, item.seeders)
        assertEquals(3, item.leechers)
    }
}

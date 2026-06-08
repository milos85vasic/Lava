package lava.tracker.rutor.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-field transform tests for the REAL [RuTorSearchParser], driving inline rutor.info
 * row HTML through the production parser (no SUT mocking; no network). These close
 * gaps the fixture-based [RuTorSearchParserTest] / [RuTorBrowseParserTest] do not cover:
 * the three USER-VISIBLE value transforms the parser performs per row are asserted
 * exactly, not just for presence/length.
 *
 * Why each test matters to a real user:
 *  - infoHash is the cross-tracker dedup + magnet-equality key. The parser lowercases it
 *    (RuTorSearchParser.kt `INFO_HASH_PATTERN.find(it)?.value?.lowercase()`); if an
 *    uppercase rutor magnet were surfaced verbatim, the SAME torrent found on two trackers
 *    would compare unequal and the dedup engine would show the user a duplicate row.
 *  - downloadUrl is the .torrent download button. rutor emits a protocol-relative
 *    `//d.rutor.info/download/<id>`; the parser promotes it to `https:` (line ~79). A drift
 *    that surfaces the bare `//...` ships a download button that resolves against the wrong
 *    scheme on a real device.
 *  - torrentId must be the numeric path segment; a non-numeric `/torrent/<slug>` anchor (the
 *    rutor top-of-page news block links look like torrent anchors) MUST be rejected, or the
 *    user sees a news article masquerading as a torrent that 404s on open.
 *
 * Falsifiability (Sixth Law clause 2 / §6.AB.3) — recorded per test in its body.
 */
class RuTorSearchParserTransformTest {

    private val parser = RuTorSearchParser()

    /** A minimal but production-shaped rutor result page wrapping a single `<tr>` under div#index. */
    private fun page(rowHtml: String): String =
        """
        <html><body>
        <div id="index">
          <table>
            <tr class="backgr"><td>Дата</td><td>Название</td><td>Размер</td><td>Пиры</td></tr>
            $rowHtml
          </table>
        </div>
        </body></html>
        """.trimIndent()

    @Test
    fun `uppercase magnet info-hash is lowercased on the parsed item`() {
        // Mutation that fails this test: drop `.lowercase()` in RuTorSearchParser.parseRow
        // (`INFO_HASH_PATTERN.find(it)?.value` without lowercase). Observed failure:
        // expected:<...e09e> but was:<...E09E> on assertEquals below.
        val upperHash = "FB3E518132E636B798C4AE4B346B60578665E09E"
        val row = """
            <tr class="gai">
              <td>10 Июн 26</td>
              <td colspan="2"><a href="/torrent/1052665/ubuntu">Ubuntu 24.04</a>
                <a href="magnet:?xt=urn:btih:$upperHash&dn=ubuntu">M</a></td>
              <td>4.25 GB</td>
              <td><span class="green">7</span> <span class="red">2</span></td>
            </tr>
        """.trimIndent()

        val item = parser.parse(page(row), pageHint = 0).items.single()

        // infoHash is surfaced fully lower-cased — the canonical dedup key form.
        assertEquals(upperHash.lowercase(), item.infoHash)
        assertEquals(40, item.infoHash!!.length)
        // The magnetUri itself is preserved verbatim (NOT lowercased) — only the extracted
        // infoHash is normalised.
        assertTrue(
            "magnetUri must be surfaced verbatim incl. the uppercase hash; got ${item.magnetUri}",
            item.magnetUri!!.contains(upperHash),
        )
    }

    @Test
    fun `protocol-relative download href is promoted to https`() {
        // Mutation that fails this test: replace `"https:$it"` with `it` in the download-URL
        // promotion branch. Observed failure: expected:<https://d.rutor.info/download/1052665>
        // but was:<//d.rutor.info/download/1052665>.
        val row = """
            <tr class="gai">
              <td>10 Июн 26</td>
              <td colspan="2"><a href="/torrent/1052665/ubuntu">Ubuntu 24.04</a>
                <a class="downgif" href="//d.rutor.info/download/1052665">D</a></td>
              <td>4.25 GB</td>
              <td><span class="green">7</span> <span class="red">2</span></td>
            </tr>
        """.trimIndent()

        val item = parser.parse(page(row), pageHint = 0).items.single()

        assertEquals("https://d.rutor.info/download/1052665", item.downloadUrl)
    }

    @Test
    fun `already-absolute download href is left untouched`() {
        // Guards the other branch of the promotion: an https URL must NOT gain a second scheme.
        // Mutation that fails this test: unconditionally prefix `"https:"`. Observed failure:
        // expected:<https://d.rutor.info/download/9> but was:<https:https://d.rutor.info/download/9>.
        val row = """
            <tr class="tum">
              <td>09 Июн 26</td>
              <td colspan="2"><a href="/torrent/9/debian">Debian</a>
                <a class="downgif" href="https://d.rutor.info/download/9">D</a></td>
              <td>3 GB</td>
              <td><span class="green">1</span> <span class="red">0</span></td>
            </tr>
        """.trimIndent()

        val item = parser.parse(page(row), pageHint = 0).items.single()

        assertEquals("https://d.rutor.info/download/9", item.downloadUrl)
    }

    @Test
    fun `news-style anchor with a non-numeric torrent path is rejected`() {
        // rutor's top-of-page news block renders /torrent/<slug> anchors that are NOT torrents.
        // The parser rejects a row whose first numeric path segment is not all-digits.
        // Mutation that fails this test: remove the `!torrentId.all { it.isDigit() }` guard.
        // Observed failure: expected:<1> but was:<2> on the items.size assertion (the news row
        // leaks in as a bogus TorrentItem).
        val row = """
            <tr class="gai">
              <td>10 Июн 26</td>
              <td colspan="2"><a href="/torrent/news-roundup/weekly">Weekly News</a></td>
              <td>1 GB</td>
              <td><span class="green">0</span></td>
            </tr>
            <tr class="tum">
              <td>10 Июн 26</td>
              <td colspan="2"><a href="/torrent/777/real">Real Torrent</a></td>
              <td>2 GB</td>
              <td><span class="green">5</span> <span class="red">1</span></td>
            </tr>
        """.trimIndent()

        val items = parser.parse(page(row), pageHint = 0).items

        // Only the numeric-id row survives.
        assertEquals(1, items.size)
        assertEquals("777", items.single().torrentId)
        assertEquals("Real Torrent", items.single().title)
    }

    @Test
    fun `row with a magnet but no download anchor yields magnet and null downloadUrl`() {
        // Asymmetry guard: a magnet-only row must surface the magnet (and its infoHash) while
        // leaving downloadUrl null — never fabricating a download link.
        // Mutation that fails this test: default downloadUrl to detailUrl when the download
        // anchor is absent. Observed failure: expected null downloadUrl but was a non-null URL.
        val row = """
            <tr class="gai">
              <td>10 Июн 26</td>
              <td colspan="2"><a href="/torrent/42/x">Magnet Only</a>
                <a href="magnet:?xt=urn:btih:abcdef0123456789abcdef0123456789abcdef01">M</a></td>
              <td>700 MB</td>
              <td><span class="green">3</span></td>
            </tr>
        """.trimIndent()

        val item = parser.parse(page(row), pageHint = 0).items.single()

        assertEquals("abcdef0123456789abcdef0123456789abcdef01", item.infoHash)
        assertTrue("magnetUri must be present", item.magnetUri!!.startsWith("magnet:?xt=urn:btih:"))
        assertNull("a row without a download anchor must expose null downloadUrl", item.downloadUrl)
        assertEquals(700L * 1024 * 1024, item.sizeBytes)
    }
}

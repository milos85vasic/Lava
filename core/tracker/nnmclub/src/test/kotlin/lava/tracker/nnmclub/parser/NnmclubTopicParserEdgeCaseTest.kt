package lava.tracker.nnmclub.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edge-case unit tests for [NnmclubTopicParser].
 *
 * Exercises the REAL production parser against malformed/partial topic HTML and
 * asserts the no-throw, valid-result contract a topic screen depends on.
 *
 * Selectors mirror `topic-normal-2026-05-02.html` (.maintitle / <title>,
 * #pagecontent .postbody, a[href^=magnet:], a[href*=download.php]). The
 * 40-hex info-hash extraction is the real INFO_HASH_PATTERN. No fabricated
 * selectors.
 */
class NnmclubTopicParserEdgeCaseTest {

    private val parser = NnmclubTopicParser()

    @Test
    fun `empty string yields a detail with empty fields without throwing`() {
        val result = parser.parse("", topicIdHint = "70")
        assertEquals("70", result.torrent.torrentId)
        assertEquals("", result.torrent.title)
        assertNull(result.torrent.magnetUri)
        assertNull(result.torrent.infoHash)
        assertNull(result.torrent.downloadUrl)
        assertNull(result.description)
        assertTrue(result.files.isEmpty())
    }

    @Test
    fun `title falls back to document title when maintitle absent`() {
        val html = "<html><head><title>Doc Title — NNM</title></head><body></body></html>"
        assertEquals("Doc Title — NNM", parser.parse(html, topicIdHint = "1").torrent.title)
    }

    @Test
    fun `maintitle takes precedence over document title`() {
        val html = """
            <html><head><title>Doc Title</title></head>
            <body><div class="maintitle">Main Title</div></body></html>
        """.trimIndent()
        assertEquals("Main Title", parser.parse(html, topicIdHint = "1").torrent.title)
    }

    @Test
    fun `magnet with a 40-hex btih yields a lowercased info hash`() {
        val hash = "DEF4567890ABCDEF1234567890ABCDEF12345678"
        val magnet = "magnet:?xt=urn:btih:$hash&dn=x"
        val html = """
            <html><body><div class="maintitle">Hashable</div>
            <a href="$magnet">Magnet</a></body></html>
        """.trimIndent()
        val result = parser.parse(html, topicIdHint = "2")
        assertEquals(magnet, result.torrent.magnetUri)
        assertEquals(hash.lowercase(), result.torrent.infoHash)
    }

    @Test
    fun `magnet without a 40-hex btih leaves info hash null but keeps the magnet`() {
        // Matches the normal fixture's "urn:btih:abc123" shape: a magnet is
        // present but the hash is too short to match the 40-hex pattern.
        val magnet = "magnet:?xt=urn:btih:abc123&dn=x"
        val html = """
            <html><body><div class="maintitle">Short Hash</div>
            <a href="$magnet">Magnet</a></body></html>
        """.trimIndent()
        val result = parser.parse(html, topicIdHint = "3")
        assertEquals(magnet, result.torrent.magnetUri)
        assertNull(result.torrent.infoHash)
    }

    @Test
    fun `magnet absent leaves magnet info hash and download null`() {
        val html = "<html><body><div class=\"maintitle\">No Links</div></body></html>"
        val result = parser.parse(html, topicIdHint = "4")
        assertNull(result.torrent.magnetUri)
        assertNull(result.torrent.infoHash)
        assertNull(result.torrent.downloadUrl)
    }

    @Test
    fun `download link is surfaced when present`() {
        val html = """
            <html><body><div class="maintitle">Has Download</div>
            <a href="download.php?id=4001">.torrent</a></body></html>
        """.trimIndent()
        assertEquals("download.php?id=4001", parser.parse(html, topicIdHint = "5").torrent.downloadUrl)
    }

    @Test
    fun `blank topic id hint yields empty torrent id and null detail url`() {
        val html = "<html><body><div class=\"maintitle\">Blank Id</div></body></html>"
        val result = parser.parse(html, topicIdHint = "   ")
        assertEquals("", result.torrent.torrentId)
        assertNull(result.torrent.detailUrl)
    }

    @Test
    fun `non-empty topic id hint builds the detail url`() {
        val html = "<html><body><div class=\"maintitle\">With Id</div></body></html>"
        val result = parser.parse(html, topicIdHint = "808")
        assertEquals("808", result.torrent.torrentId)
        assertEquals("/forum/viewtopic.php?t=808", result.torrent.detailUrl)
    }

    @Test
    fun `description is null when postbody is empty`() {
        val html = """
            <html><body><div id="pagecontent"><div class="maintitle">Empty Body</div>
            <div class="postbody">   </div></div></body></html>
        """.trimIndent()
        assertNull(parser.parse(html, topicIdHint = "6").description)
    }
}

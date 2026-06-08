package lava.tracker.kinozal.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edge-case unit tests for [KinozalTopicParser].
 *
 * Exercises the REAL production parser against malformed/partial topic HTML and
 * asserts the no-throw, valid-result contract a topic screen depends on.
 *
 * Selectors mirror `topic-normal-2026-05-02.html` (h1 / <title> for title,
 * a.magnet or a[href^=magnet:] for magnet, div.content for description).
 */
class KinozalTopicParserEdgeCaseTest {

    private val parser = KinozalTopicParser()

    @Test
    fun `empty string yields a detail with empty title and no magnet without throwing`() {
        val result = parser.parse("", topicIdHint = "55")
        assertEquals("55", result.torrent.torrentId)
        assertEquals("", result.torrent.title)
        assertNull(result.torrent.magnetUri)
        assertNull(result.description)
        assertTrue(result.files.isEmpty())
    }

    @Test
    fun `title falls back to document title when h1 absent`() {
        val html = "<html><head><title>Fallback Title</title></head><body></body></html>"
        val result = parser.parse(html, topicIdHint = "1")
        assertEquals("Fallback Title", result.torrent.title)
    }

    @Test
    fun `h1 takes precedence over document title`() {
        val html = """
            <html><head><title>Page Title</title></head>
            <body><h1>Heading Title</h1></body></html>
        """.trimIndent()
        assertEquals("Heading Title", parser.parse(html, topicIdHint = "1").torrent.title)
    }

    @Test
    fun `magnet present is surfaced and description extracted`() {
        val magnet = "magnet:?xt=urn:btih:ABC123DEF4567890ABC123DEF4567890ABC1234"
        val html = """
            <html><body><h1>Has Everything</h1>
            <a href="$magnet" class="magnet">m</a>
            <div class="content">Full description text.</div></body></html>
        """.trimIndent()
        val result = parser.parse(html, topicIdHint = "2")
        assertEquals(magnet, result.torrent.magnetUri)
        assertEquals("Full description text.", result.description)
    }

    @Test
    fun `magnet absent leaves magnetUri null and description null`() {
        val html = "<html><body><h1>No Magnet No Desc</h1></body></html>"
        val result = parser.parse(html, topicIdHint = "3")
        assertNull(result.torrent.magnetUri)
        assertNull(result.description)
    }

    @Test
    fun `magnet without the magnet class is still found via the href prefix selector`() {
        val magnet = "magnet:?xt=urn:btih:DEAD0000DEAD0000DEAD0000DEAD0000DEAD0000"
        val html = """
            <html><body><h1>Plain Anchor</h1>
            <a href="$magnet">download</a></body></html>
        """.trimIndent()
        assertEquals(magnet, parser.parse(html, topicIdHint = "4").torrent.magnetUri)
    }

    @Test
    fun `null topic id hint produces empty torrent id without throwing`() {
        val html = "<html><body><h1>Anonymous</h1></body></html>"
        val result = parser.parse(html, topicIdHint = null)
        assertEquals("", result.torrent.torrentId)
        assertEquals("Anonymous", result.torrent.title)
    }
}

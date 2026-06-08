package lava.tracker.gutenberg.feature

import kotlinx.coroutines.runBlocking
import lava.tracker.gutenberg.http.GutenbergHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Edge-case tests for [GutenbergTopic] single-book JSON (Gutendex) parsing.
 *
 * Drives the REAL feature + REAL [GutenbergHttpClient] over MockWebServer.
 * Covers /books/{id}/ shapes: missing optional collections, unknown extra
 * fields, and the file-per-format mapping. Only the network socket is faked.
 */
class GutenbergTopicEdgeCaseTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun feature(): GutenbergTopic =
        GutenbergTopic(GutenbergHttpClient(), server.url("/").toString().trimEnd('/'))

    @Test
    fun `book with absent optional collections maps to defaults without throwing`() = runBlocking {
        val json = """{"id": 99, "title": "Defaults Only"}"""
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))

        val result = feature().getTopic("99")

        assertEquals("Defaults Only", result.torrent.title)
        assertEquals("", result.torrent.metadata["creator"])
        assertEquals("Unknown", result.torrent.metadata["format"])
        assertEquals("0", result.torrent.metadata["downloads"])
        assertNull(result.torrent.category)
        assertNull(result.torrent.downloadUrl)
        // No formats -> no per-format files.
        assertTrue(result.files.isEmpty())
    }

    @Test
    fun `unknown extra fields are ignored and known fields map`() = runBlocking {
        val json = """
            {
              "id": 1342,
              "title": "Pride and Prejudice",
              "authors": [{"name": "Austen, Jane", "birth_year": 1775, "death_year": 1817}],
              "translators": [],
              "bookshelves": ["Best Books Ever Listings"],
              "languages": ["en"],
              "copyright": false,
              "media_type": "Text",
              "subjects": ["Love stories", "England -- Fiction"],
              "formats": {
                "application/epub+zip": "https://g.org/1342.epub",
                "text/html": "https://g.org/1342.html"
              },
              "download_count": 47000
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))

        val result = feature().getTopic("1342")

        assertEquals("Pride and Prejudice", result.torrent.title)
        assertEquals("Austen, Jane", result.torrent.metadata["creator"])
        assertEquals("Love stories", result.torrent.category)
        assertEquals("EPUB", result.torrent.metadata["format"])
        assertEquals("https://g.org/1342.epub", result.torrent.downloadUrl)
        // One TorrentFile per format entry.
        assertEquals(2, result.files.size)
    }
}

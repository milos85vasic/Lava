package lava.tracker.gutenberg.feature

import kotlinx.coroutines.runBlocking
import lava.tracker.api.model.SearchRequest
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
 * Edge-case tests for [GutenbergSearch] JSON-API (Gutendex) parsing.
 *
 * Drives the REAL feature + REAL [GutenbergHttpClient] (production `Json` with
 * ignoreUnknownKeys + isLenient) over MockWebServer. Covers the Gutendex book
 * list shapes: unknown extra fields, missing optional fields (authors, formats,
 * subjects, download_count), multi-book payloads, and the no-throw contract.
 * Only the network socket is faked.
 */
class GutenbergSearchEdgeCaseTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun feature(): GutenbergSearch =
        GutenbergSearch(GutenbergHttpClient(), server.url("/").toString().trimEnd('/'))

    @Test
    fun `unknown top-level and per-book fields are ignored`() = runBlocking {
        // Gutendex emits next/previous + per-book fields (languages, copyright,
        // bookshelves, translators, media_type) our model does not declare.
        val json = """
            {
              "count": 1,
              "next": "https://gutendex.com/books/?page=2",
              "previous": null,
              "results": [
                {
                  "id": 11,
                  "title": "Alice's Adventures in Wonderland",
                  "authors": [{"name": "Carroll, Lewis", "birth_year": 1832, "death_year": 1898}],
                  "translators": [],
                  "subjects": ["Fantasy fiction"],
                  "bookshelves": ["Children's Literature"],
                  "languages": ["en"],
                  "copyright": false,
                  "media_type": "Text",
                  "formats": {"application/epub+zip": "https://g.org/11.epub"},
                  "download_count": 30000
                }
              ]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))

        val item = feature().search(SearchRequest(query = "alice"), page = 0).items.single()

        assertEquals("11", item.torrentId)
        assertEquals("Alice's Adventures in Wonderland", item.title)
        // birth_year/death_year on the author are unknown keys; name still maps.
        assertEquals("Carroll, Lewis", item.metadata["creator"])
        assertEquals("Fantasy fiction", item.category)
        assertEquals("EPUB", item.metadata["format"])
        assertEquals("30000", item.metadata["downloads"])
        assertEquals("https://g.org/11.epub", item.downloadUrl)
    }

    @Test
    fun `book missing optional collections defaults to empty without throwing`() = runBlocking {
        // authors, formats, subjects, download_count all omitted -> model defaults.
        val json = """{"count": 1, "results": [{"id": 5, "title": "Sparse Book"}]}"""
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))

        val item = feature().search(SearchRequest(query = "x"), page = 0).items.single()

        assertEquals("5", item.torrentId)
        assertEquals("Sparse Book", item.title)
        assertEquals("", item.metadata["creator"])
        assertEquals("Unknown", item.metadata["format"])
        assertEquals("0", item.metadata["downloads"])
        assertNull(item.category)
        assertNull(item.downloadUrl)
    }

    @Test
    fun `missing results array defaults to empty list`() = runBlocking {
        // Some Gutendex error envelopes omit results entirely; default = empty.
        val json = """{"count": 0}"""
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))

        val result = feature().search(SearchRequest(query = "x"), page = 0)

        assertTrue(result.items.isEmpty())
        assertEquals(1, result.totalPages)
    }

    @Test
    fun `multiple books preserve order and per-book field presence`() = runBlocking {
        val json = """
            {"count": 65, "results": [
              {"id": 1, "title": "One", "authors": [{"name": "Auth One"}],
               "formats": {"text/plain": "https://g.org/1.txt"}, "download_count": 10},
              {"id": 2, "title": "Two", "subjects": ["History"],
               "formats": {"text/html": "https://g.org/2.html"}}
            ]}
        """.trimIndent()
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))

        val result = feature().search(SearchRequest(query = "x"), page = 0)

        assertEquals(2, result.items.size)
        assertEquals("Auth One", result.items[0].metadata["creator"])
        assertEquals("Text", result.items[0].metadata["format"])
        assertEquals("History", result.items[1].category)
        assertEquals("HTML", result.items[1].metadata["format"])
        // count 65 -> ceil(65/32) = 3 pages.
        assertEquals(3, result.totalPages)
    }

    @Test
    fun `lenient json tolerates surrounding whitespace`() = runBlocking {
        val json = "  \n {\"count\": 0, \"results\": []}  \n"
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))

        val result = feature().search(SearchRequest(query = "x"), page = 0)

        assertTrue(result.items.isEmpty())
    }
}

package lava.tracker.gutenberg.feature

import kotlinx.coroutines.runBlocking
import lava.tracker.api.model.SearchRequest
import lava.tracker.gutenberg.http.GutenbergHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * MockWebServer-driven tests for [GutenbergSearch].
 */
class GutenbergSearchTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `search hits canonical URL and surfaces parsed results`() = runBlocking {
        val json = """
            {
                "count": 1,
                "results": [
                    {
                        "id": 123,
                        "title": "Pride and Prejudice",
                        "authors": [{"name": "Jane Austen"}],
                        "formats": {"application/epub+zip": "https://example.com/epub"},
                        "download_count": 50000,
                        "subjects": ["Fiction"]
                    }
                ]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = GutenbergSearch(GutenbergHttpClient(), baseUrl)

        val result = feature.search(SearchRequest(query = "prejudice"), page = 1)

        val recordedPath = server.takeRequest().path
        assertEquals("/books?search=prejudice&page=1", recordedPath)
        assertEquals(1, result.items.size)
        assertEquals("Pride and Prejudice", result.items.first().title)
        assertEquals("Jane Austen", result.items.first().metadata["creator"])
        assertTrue(result.totalPages >= 1)
    }

    @Test
    fun `empty result page returns empty SearchResult`() = runBlocking {
        val json = """{"count": 0, "results": []}"""
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = GutenbergSearch(GutenbergHttpClient(), baseUrl)

        val result = feature.search(SearchRequest(query = "xyz nonsense"), page = 0)

        assertEquals(0, result.items.size)
        assertEquals(0, result.currentPage)
    }
}

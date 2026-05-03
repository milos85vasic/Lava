package lava.tracker.gutenberg.feature

import kotlinx.coroutines.runBlocking
import lava.tracker.gutenberg.http.GutenbergHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class GutenbergTopicTest {

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
    fun `getTopic hits canonical URL and surfaces parsed detail`() = runBlocking {
        val json = """
            {
                "id": 789,
                "title": "Moby Dick",
                "authors": [{"name": "Herman Melville"}],
                "formats": {"application/epub+zip": "https://example.com/epub"},
                "download_count": 1000,
                "subjects": ["Fiction", "Whales"]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = GutenbergTopic(GutenbergHttpClient(), baseUrl)

        val result = feature.getTopic("789")

        assertEquals("/books/789/", server.takeRequest().path)
        assertEquals("Moby Dick", result.torrent.title)
        assertEquals("Herman Melville", result.torrent.metadata["creator"])
        assertEquals("Fiction", result.torrent.category)
        assertEquals("https://example.com/epub", result.torrent.downloadUrl)
    }

    @Test
    fun `getTopicPage returns single-page envelope`() = runBlocking {
        val json = """
            {
                "id": 1,
                "title": "Test",
                "authors": [],
                "formats": {},
                "download_count": 0
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = GutenbergTopic(GutenbergHttpClient(), baseUrl)

        val page = feature.getTopicPage("1", page = 0)

        assertEquals(1, page.totalPages)
        assertEquals(0, page.currentPage)
        assertEquals("Test", page.topic.torrent.title)
    }
}

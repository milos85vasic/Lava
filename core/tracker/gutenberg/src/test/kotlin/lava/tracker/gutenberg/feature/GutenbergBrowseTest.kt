package lava.tracker.gutenberg.feature

import kotlinx.coroutines.runBlocking
import lava.tracker.gutenberg.http.GutenbergHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class GutenbergBrowseTest {

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
    fun `browse with topic hits canonical URL`() = runBlocking {
        val json = """
            {
                "count": 2,
                "results": [
                    {"id": 1, "title": "Science A", "authors": [], "formats": {}, "download_count": 0},
                    {"id": 2, "title": "Science B", "authors": [], "formats": {}, "download_count": 0}
                ]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = GutenbergBrowse(GutenbergHttpClient(), baseUrl)

        val result = feature.browse(category = "science", page = 2)

        assertEquals("/books?topic=science&page=2", server.takeRequest().path)
        assertEquals(2, result.items.size)
        assertEquals("science", result.category?.id)
    }

    @Test
    fun `browse with null category omits topic param`() = runBlocking {
        val json = """{"count": 1, "results": [{"id": 1, "title": "Any", "authors": [], "formats": {}, "download_count": 0}]}"""
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = GutenbergBrowse(GutenbergHttpClient(), baseUrl)

        feature.browse(category = null, page = 0)

        assertEquals("/books", server.takeRequest().path)
    }

    @Test
    fun `getForumTree returns static subject list`() = runBlocking {
        val feature = GutenbergBrowse(GutenbergHttpClient(), "https://unused")
        val tree = feature.getForumTree()
        assertNotNull(tree)
        assertEquals(6, tree.rootCategories.size)
        assertEquals("Fiction", tree.rootCategories[0].name)
    }
}

package lava.tracker.archiveorg.feature

import kotlinx.coroutines.runBlocking
import lava.tracker.api.model.SearchRequest
import lava.tracker.archiveorg.http.ArchiveOrgHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ArchiveOrgSearchTest {

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
    fun `search hits the canonical archive org URL shape and surfaces parsed results`() = runBlocking {
        val json = """
            {"response":{"numFound":123,"start":0,"docs":[
                {"identifier":"item-1","title":"Test Item","creator":"Alice","downloads":42,"item_size":1048576,"mediatype":"movies","year":"2023"}
            ]}}
        """.trimIndent()
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = ArchiveOrgSearch(ArchiveOrgHttpClient(), baseUrl)

        val result = feature.search(SearchRequest(query = "ubuntu"), page = 0)

        val recordedPath = server.takeRequest().path
        assertTrue(
            "search path must contain /advancedsearch.php",
            recordedPath!!.startsWith("/advancedsearch.php"),
        )
        assertTrue(
            "query param must contain ubuntu",
            recordedPath.contains("q=ubuntu"),
        )
        assertEquals(1, result.items.size)
        assertEquals("item-1", result.items[0].torrentId)
        assertEquals("Test Item", result.items[0].title)
        assertEquals(1048576L, result.items[0].sizeBytes)
        assertEquals("movies", result.items[0].category)
        assertEquals("Alice", result.items[0].metadata["creator"])
        assertEquals("42", result.items[0].metadata["downloads"])
        assertEquals(0, result.currentPage)
    }

    @Test
    fun `empty result page returns an empty SearchResult, not an exception`() = runBlocking {
        val json = """{"response":{"numFound":0,"start":0,"docs":[]}}"""
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = ArchiveOrgSearch(ArchiveOrgHttpClient(), baseUrl)

        val result = feature.search(SearchRequest(query = "nonsense"), page = 0)

        assertEquals(0, result.items.size)
        assertEquals(1, result.totalPages)
    }

    @Test
    fun `cyrillic query is percent-encoded as UTF-8`() = runBlocking {
        val json = """{"response":{"numFound":0,"start":0,"docs":[]}}"""
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = ArchiveOrgSearch(ArchiveOrgHttpClient(), baseUrl)

        feature.search(SearchRequest(query = "Кино"), page = 0)

        val recordedPath = server.takeRequest().path
        assertTrue(
            "Cyrillic query must be percent-encoded",
            recordedPath!!.contains("%D0%9A%D0%B8%D0%BD%D0%BE"),
        )
    }

    @Test
    fun `page 0 maps to API page 1`() = runBlocking {
        val json = """{"response":{"numFound":0,"start":0,"docs":[]}}"""
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = ArchiveOrgSearch(ArchiveOrgHttpClient(), baseUrl)

        feature.search(SearchRequest(query = "x"), page = 0)

        val recordedPath = server.takeRequest().path
        assertTrue(
            "page=0 must map to API page=1",
            recordedPath!!.contains("page=1"),
        )
    }

    @Test
    fun `page 1 maps to API page 2`() = runBlocking {
        val json = """{"response":{"numFound":0,"start":0,"docs":[]}}"""
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = ArchiveOrgSearch(ArchiveOrgHttpClient(), baseUrl)

        feature.search(SearchRequest(query = "x"), page = 1)

        val recordedPath = server.takeRequest().path
        assertTrue(
            "page=1 must map to API page=2",
            recordedPath!!.contains("page=2"),
        )
    }
}

package lava.tracker.nnmclub.feature

import kotlinx.coroutines.runBlocking
import lava.tracker.api.model.SearchRequest
import lava.tracker.nnmclub.http.NnmclubHttpClient
import lava.tracker.nnmclub.parser.NnmclubSearchParser
import lava.tracker.testing.LavaFixtureLoader
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NnmclubSearchTest {

    private lateinit var server: MockWebServer
    private val loader = LavaFixtureLoader(tracker = "nnmclub")
    private val parser = NnmclubSearchParser()

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `search hits the canonical NNM-Club URL shape and surfaces parsed results`() = runBlocking {
        val html = loader.load("search", "search-normal-2026-05-02.html")
        server.enqueue(MockResponse().setBody(html).setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = NnmclubSearch(NnmclubHttpClient(), parser, baseUrl)

        val result = feature.search(SearchRequest(query = "linux"), page = 0)

        val recordedPath = server.takeRequest().path
        assertEquals(
            "user-typed query 'linux' on page 0 must hit canonical tracker.php shape",
            "/forum/tracker.php?nm=linux",
            recordedPath,
        )
        assertTrue("expected at least 1 result row", result.items.isNotEmpty())
        assertEquals(0, result.currentPage)
    }

    @Test
    fun `search with page greater than zero appends start offset`() = runBlocking {
        val html = loader.load("search", "search-empty-2026-05-02.html")
        server.enqueue(MockResponse().setBody(html).setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = NnmclubSearch(NnmclubHttpClient(), parser, baseUrl)

        feature.search(SearchRequest(query = "ubuntu"), page = 2)

        val recordedPath = server.takeRequest().path
        assertEquals(
            "page 2 must append start=100",
            "/forum/tracker.php?nm=ubuntu&start=100",
            recordedPath,
        )
    }
}

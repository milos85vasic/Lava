package lava.tracker.kinozal.feature

import kotlinx.coroutines.runBlocking
import lava.tracker.api.model.SearchRequest
import lava.tracker.kinozal.http.KinozalHttpClient
import lava.tracker.kinozal.parser.KinozalSearchParser
import lava.tracker.testing.LavaFixtureLoader
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KinozalSearchTest {
    private lateinit var server: MockWebServer
    private val loader = LavaFixtureLoader(tracker = "kinozal")
    private val parser = KinozalSearchParser()

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `search hits canonical URL shape and surfaces parsed results`() = runBlocking {
        val html = loader.load("search", "search-normal-2026-05-02.html")
        server.enqueue(MockResponse().setBody(html).setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = KinozalSearch(KinozalHttpClient(), parser, baseUrl)

        val result = feature.search(SearchRequest(query = "test"), page = 0)

        val recordedPath = server.takeRequest().path
        assertEquals(
            "user-typed query 'test' on page 0 must hit canonical /browse.php?s=test&page=0",
            "/browse.php?s=test&page=0",
            recordedPath,
        )
        assertTrue("expected at least 1 result row", result.items.isNotEmpty())
        assertTrue(
            "first result title should mention Test — got '${result.items.first().title}'",
            result.items.first().title.contains("Test", ignoreCase = true),
        )
        assertEquals(0, result.currentPage)
    }
}

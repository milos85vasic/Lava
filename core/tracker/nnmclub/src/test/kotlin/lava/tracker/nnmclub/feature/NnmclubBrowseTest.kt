package lava.tracker.nnmclub.feature

import kotlinx.coroutines.runBlocking
import lava.tracker.nnmclub.http.NnmclubHttpClient
import lava.tracker.nnmclub.parser.NnmclubBrowseParser
import lava.tracker.testing.LavaFixtureLoader
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NnmclubBrowseTest {

    private lateinit var server: MockWebServer
    private val loader = LavaFixtureLoader(tracker = "nnmclub")
    private val parser = NnmclubBrowseParser()

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `browse with null category hits the all-categories slot`() = runBlocking {
        val html = loader.load("browse", "browse-normal-2026-05-02.html")
        server.enqueue(MockResponse().setBody(html).setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = NnmclubBrowse(NnmclubHttpClient(), parser, baseUrl)

        val result = feature.browse(category = null, page = 0)

        assertEquals("/forum/viewforum.php?f=0", server.takeRequest().path)
        assertTrue("expected at least 1 row", result.items.isNotEmpty())
    }

    @Test
    fun `browse with explicit category id places it in the category slot`() = runBlocking {
        val html = loader.load("browse", "browse-normal-2026-05-02.html")
        server.enqueue(MockResponse().setBody(html).setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = NnmclubBrowse(NnmclubHttpClient(), parser, baseUrl)

        feature.browse(category = "5", page = 2)

        assertEquals("/forum/viewforum.php?f=5&start=100", server.takeRequest().path)
    }

    @Test
    fun `getForumTree returns null because NNM-Club has no wired forum tree (Capability Honesty)`() = runBlocking {
        val feature = NnmclubBrowse(NnmclubHttpClient(), parser, "https://unused")
        assertNull(feature.getForumTree())
    }
}

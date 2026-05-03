package lava.tracker.nnmclub.feature

import kotlinx.coroutines.runBlocking
import lava.tracker.nnmclub.http.NnmclubHttpClient
import lava.tracker.nnmclub.parser.NnmclubTopicParser
import lava.tracker.testing.LavaFixtureLoader
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NnmclubTopicTest {

    private lateinit var server: MockWebServer
    private val loader = LavaFixtureLoader(tracker = "nnmclub")
    private val parser = NnmclubTopicParser()

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getTopic fetches the canonical topic URL and returns parsed detail`() = runBlocking {
        val html = loader.load("topic", "topic-normal-2026-05-02.html")
        server.enqueue(MockResponse().setBody(html).setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = NnmclubTopic(NnmclubHttpClient(), parser, baseUrl)

        val detail = feature.getTopic("1001")

        assertEquals("/forum/viewtopic.php?t=1001", server.takeRequest().path)
        assertTrue("topic title should be non-empty", detail.torrent.title.isNotBlank())
        assertTrue("magnet should be present", !detail.torrent.magnetUri.isNullOrEmpty())
    }

    @Test
    fun `getTopicPage returns single-page envelope`() = runBlocking {
        val html = loader.load("topic", "topic-normal-2026-05-02.html")
        server.enqueue(MockResponse().setBody(html).setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = NnmclubTopic(NnmclubHttpClient(), parser, baseUrl)

        val page = feature.getTopicPage("1001", page = 0)

        assertEquals(1, page.totalPages)
        assertEquals(0, page.currentPage)
        assertTrue(page.topic.torrent.title.isNotBlank())
    }
}

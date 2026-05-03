package lava.tracker.kinozal.feature

import kotlinx.coroutines.runBlocking
import lava.tracker.kinozal.http.KinozalHttpClient
import lava.tracker.kinozal.parser.KinozalTopicParser
import lava.tracker.testing.LavaFixtureLoader
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KinozalTopicTest {
    private lateinit var server: MockWebServer
    private val loader = LavaFixtureLoader(tracker = "kinozal")
    private val parser = KinozalTopicParser()

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getTopic fetches details and returns parsed topic`() = runBlocking {
        val html = loader.load("topic", "topic-normal-2026-05-02.html")
        server.enqueue(MockResponse().setBody(html).setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = KinozalTopic(KinozalHttpClient(), parser, baseUrl)

        val result = feature.getTopic("12345")

        val recordedPath = server.takeRequest().path
        assertEquals("/details.php?id=12345", recordedPath)
        assertTrue("title should contain Test Movie", result.torrent.title.contains("Test Movie"))
        assertTrue("description should contain description text", result.description?.contains("Description") == true)
    }
}

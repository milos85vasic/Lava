package lava.tracker.kinozal.feature

import kotlinx.coroutines.runBlocking
import lava.tracker.kinozal.http.KinozalHttpClient
import lava.tracker.kinozal.parser.KinozalSearchParser
import lava.tracker.testing.LavaFixtureLoader
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class KinozalBrowseTest {
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
    fun `browse with explicit category id places it in the category slot`() = runBlocking {
        val html = loader.load("browse", "browse-normal-2026-05-02.html")
        server.enqueue(MockResponse().setBody(html).setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = KinozalBrowse(KinozalHttpClient(), parser, baseUrl)

        feature.browse(category = "5", page = 0)

        val recordedPath = server.takeRequest().path
        assertEquals("/browse.php?c=5&page=0", recordedPath)
    }
}

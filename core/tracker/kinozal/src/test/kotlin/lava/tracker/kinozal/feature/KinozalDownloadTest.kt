package lava.tracker.kinozal.feature

import kotlinx.coroutines.runBlocking
import lava.tracker.kinozal.http.KinozalHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class KinozalDownloadTest {
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
    fun `downloadTorrentFile fetches download URL and returns body bytes`() = runBlocking {
        server.enqueue(MockResponse().setBody("torrent-data").setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = KinozalDownload(KinozalHttpClient(), baseUrl)

        val bytes = feature.downloadTorrentFile("12345")

        val recordedPath = server.takeRequest().path
        assertEquals("/download.php?id=12345", recordedPath)
        assertEquals("torrent-data", String(bytes))
    }
}

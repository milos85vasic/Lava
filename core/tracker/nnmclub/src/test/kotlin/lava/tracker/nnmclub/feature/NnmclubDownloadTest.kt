package lava.tracker.nnmclub.feature

import kotlinx.coroutines.runBlocking
import lava.tracker.nnmclub.http.NnmclubHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class NnmclubDownloadTest {

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
        val expectedBytes = "d8:announce11:nnmclub.tests".toByteArray()
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(expectedBytes))
                .setResponseCode(200),
        )
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = NnmclubDownload(NnmclubHttpClient(), baseUrl)

        val bytes = feature.downloadTorrentFile("1001")

        assertEquals("/forum/download.php?id=1001", server.takeRequest().path)
        assertArrayEquals(expectedBytes, bytes)
    }

    @Test
    fun `getMagnetLink returns null because NNM-Club has no synchronous magnet (Capability Honesty)`() {
        val feature = NnmclubDownload(NnmclubHttpClient(), "https://unused")
        assertNull(feature.getMagnetLink("1001"))
    }
}

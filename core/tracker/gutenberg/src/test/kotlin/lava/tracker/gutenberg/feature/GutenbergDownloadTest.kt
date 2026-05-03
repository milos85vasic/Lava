package lava.tracker.gutenberg.feature

import kotlinx.coroutines.runBlocking
import lava.tracker.gutenberg.http.GutenbergHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class GutenbergDownloadTest {

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
    fun `downloadTorrentFile picks epub over plain text`() = runBlocking {
        val metaJson = """
            {
                "id": 1,
                "title": "Test",
                "authors": [],
                "formats": {
                    "application/epub+zip": "${server.url("/epub")}",
                    "text/plain": "${server.url("/txt")}"
                },
                "download_count": 0
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(metaJson).setResponseCode(200))
        server.enqueue(MockResponse().setBody("epub-bytes").setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = GutenbergDownload(GutenbergHttpClient(), baseUrl)

        val bytes = feature.downloadTorrentFile("1")

        assertEquals("/books/1/", server.takeRequest().path)
        assertEquals("/epub", server.takeRequest().path)
        assertEquals("epub-bytes", String(bytes))
    }

    @Test
    fun `downloadTorrentFile falls back to plain text when no epub`() = runBlocking {
        val metaJson = """
            {
                "id": 2,
                "title": "Test",
                "authors": [],
                "formats": {
                    "text/plain": "${server.url("/txt")}"
                },
                "download_count": 0
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(metaJson).setResponseCode(200))
        server.enqueue(MockResponse().setBody("txt-bytes").setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = GutenbergDownload(GutenbergHttpClient(), baseUrl)

        val bytes = feature.downloadTorrentFile("2")

        assertEquals("/books/2/", server.takeRequest().path)
        assertEquals("/txt", server.takeRequest().path)
        assertEquals("txt-bytes", String(bytes))
    }

    @Test
    fun `getMagnetLink returns null`() {
        val feature = GutenbergDownload(GutenbergHttpClient(), "https://unused")
        assertNull(feature.getMagnetLink("1"))
    }
}

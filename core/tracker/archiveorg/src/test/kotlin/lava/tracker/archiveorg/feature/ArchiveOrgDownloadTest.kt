package lava.tracker.archiveorg.feature

import kotlinx.coroutines.runBlocking
import lava.tracker.archiveorg.http.ArchiveOrgHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ArchiveOrgDownloadTest {

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
    fun `downloadTorrentFile fetches binary from download endpoint`() = runBlocking {
        val bytes = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)).setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = ArchiveOrgDownload(ArchiveOrgHttpClient(), baseUrl)

        val result = feature.downloadTorrentFile("item-1/file.bin")

        val recordedPath = server.takeRequest().path
        assertEquals("/download/item-1/file.bin", recordedPath)
        assertTrue(result.contentEquals(bytes))
    }

    @Test
    fun `getMagnetLink returns null`() {
        val feature = ArchiveOrgDownload(ArchiveOrgHttpClient(), "http://localhost")
        assertNull(feature.getMagnetLink("any-id"))
    }

    @Test
    fun `downloadTorrentFile throws on invalid id format`() = runBlocking {
        val feature = ArchiveOrgDownload(ArchiveOrgHttpClient(), "http://localhost")
        try {
            feature.downloadTorrentFile("noseparator")
            throw AssertionError("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("identifier/filename"))
        }
    }
}

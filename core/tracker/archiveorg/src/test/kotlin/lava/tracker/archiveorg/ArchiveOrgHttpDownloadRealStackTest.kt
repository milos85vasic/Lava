package lava.tracker.archiveorg

import kotlinx.coroutines.runBlocking
import lava.tracker.api.TrackerCapability
import lava.tracker.api.feature.DownloadableTracker
import lava.tracker.api.feature.HttpDownloadableTracker
import lava.tracker.archiveorg.feature.ArchiveOrgBrowse
import lava.tracker.archiveorg.feature.ArchiveOrgDownload
import lava.tracker.archiveorg.feature.ArchiveOrgSearch
import lava.tracker.archiveorg.feature.ArchiveOrgTopic
import lava.tracker.archiveorg.http.ArchiveOrgHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §6.D / §6.G / §6.E real-stack test for the LVA-044 HTTP_DOWNLOAD capability
 * on Internet Archive (`archiveorg`).
 *
 * Traverses the REAL production path a user's "download this file" action
 * would cross: the production [ArchiveOrgClient.getFeature] routing →
 * [HttpDownloadableTracker] → [ArchiveOrgDownload.downloadHttpFile] → the real
 * [ArchiveOrgHttpClient] OkHttp call. Only the network socket boundary is
 * faked (MockWebServer), per Seventh Law clause 4. The primary assertion is on
 * user-visible state: the downloaded file BYTES, the source URL, and the
 * suggested filename.
 *
 * Capability Honesty (§6.E), both directions:
 *  - HTTP_DOWNLOAD declared ⇒ getFeature(HttpDownloadableTracker) non-null;
 *  - TORRENT_DOWNLOAD NOT declared ⇒ getFeature(DownloadableTracker) null.
 */
class ArchiveOrgHttpDownloadRealStackTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun newClient(downloadBaseUrl: String): ArchiveOrgClient {
        val http = ArchiveOrgHttpClient()
        return ArchiveOrgClient(
            http = http,
            search = ArchiveOrgSearch(http),
            browse = ArchiveOrgBrowse(http),
            topic = ArchiveOrgTopic(http),
            download = ArchiveOrgDownload(http, downloadBaseUrl),
        )
    }

    @Test
    fun `archiveorg declares HTTP_DOWNLOAD and resolves the feature`() {
        assertTrue(
            "archiveorg descriptor must declare HTTP_DOWNLOAD",
            TrackerCapability.HTTP_DOWNLOAD in ArchiveOrgDescriptor.capabilities,
        )
        val client = newClient("https://unused")
        assertNotNull(
            "HTTP_DOWNLOAD declared ⇒ getFeature(HttpDownloadableTracker) must be non-null (§6.E)",
            client.getFeature(HttpDownloadableTracker::class),
        )
        assertNull(
            "TORRENT_DOWNLOAD not declared ⇒ getFeature(DownloadableTracker) must be null (§6.E)",
            client.getFeature(DownloadableTracker::class),
        )
    }

    @Test
    fun `resolved HTTP_DOWNLOAD feature downloads the real file bytes plus source metadata`() = runBlocking {
        val fileBytes = "PKepub-content".toByteArray() // EPUB-shaped payload
        server.enqueue(MockResponse().setBody(okio.Buffer().write(fileBytes)).setResponseCode(200))

        val baseUrl = server.url("/").toString().trimEnd('/')
        val client = newClient(baseUrl)

        val feature = client.getFeature(HttpDownloadableTracker::class)
        assertNotNull("getFeature(HttpDownloadableTracker) must resolve", feature)

        val result = feature!!.downloadHttpFile("greatbook/greatbook.epub")

        // The OkHttp call hit the real archive.org download route shape.
        assertEquals("/download/greatbook/greatbook.epub", server.takeRequest().path)
        // User-visible artifact: the exact bytes the server served.
        assertTrue(
            "downloaded bytes must equal the served file bytes",
            result.bytes.contentEquals(fileBytes),
        )
        assertEquals("greatbook.epub", result.fileName)
        assertTrue(
            "sourceUrl must point at the resolved download endpoint",
            result.sourceUrl.endsWith("/download/greatbook/greatbook.epub"),
        )
    }
}

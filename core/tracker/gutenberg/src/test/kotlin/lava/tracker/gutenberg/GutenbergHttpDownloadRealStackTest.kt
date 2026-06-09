package lava.tracker.gutenberg

import kotlinx.coroutines.runBlocking
import lava.tracker.api.TrackerCapability
import lava.tracker.api.feature.DownloadableTracker
import lava.tracker.api.feature.HttpDownloadableTracker
import lava.tracker.gutenberg.feature.GutenbergBrowse
import lava.tracker.gutenberg.feature.GutenbergDownload
import lava.tracker.gutenberg.feature.GutenbergSearch
import lava.tracker.gutenberg.feature.GutenbergTopic
import lava.tracker.gutenberg.http.GutenbergHttpClient
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
 * on Project Gutenberg (`gutenberg`).
 *
 * Traverses the REAL production path a user's "download this e-book" action
 * would cross: the production [GutenbergClient.getFeature] routing →
 * [HttpDownloadableTracker] → [GutenbergDownload.downloadHttpFile] (which
 * fetches the Gutendex metadata, picks the best format, then downloads it) →
 * the real [GutenbergHttpClient] OkHttp calls. Only the network socket
 * boundary is faked (MockWebServer), per Seventh Law clause 4. The primary
 * assertion is on user-visible state: the downloaded e-book BYTES + filename.
 *
 * Capability Honesty (§6.E), both directions:
 *  - HTTP_DOWNLOAD declared ⇒ getFeature(HttpDownloadableTracker) non-null;
 *  - TORRENT_DOWNLOAD NOT declared ⇒ getFeature(DownloadableTracker) null
 *    (gutenberg serves EPUB/text/HTML, never `.torrent`).
 */
class GutenbergHttpDownloadRealStackTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun newClient(downloadBaseUrl: String): GutenbergClient {
        val http = GutenbergHttpClient()
        return GutenbergClient(
            http = http,
            search = GutenbergSearch(http),
            browse = GutenbergBrowse(http),
            topic = GutenbergTopic(http),
            download = GutenbergDownload(http, downloadBaseUrl),
        )
    }

    @Test
    fun `gutenberg declares HTTP_DOWNLOAD and resolves the feature`() {
        assertTrue(
            "gutenberg descriptor must declare HTTP_DOWNLOAD",
            TrackerCapability.HTTP_DOWNLOAD in GutenbergDescriptor.capabilities,
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
    fun `resolved HTTP_DOWNLOAD feature downloads the real e-book bytes plus source metadata`() = runBlocking {
        val epubBytes = "PKepub-real-content".toByteArray()

        // 1st request: Gutendex book metadata, pointing the EPUB format at the
        // MockWebServer's /downloads/pg1342.epub path.
        val epubUrl = server.url("/downloads/pg1342.epub")
        val metaJson = """
            {
                "id": 1342,
                "title": "Pride and Prejudice",
                "authors": [],
                "formats": {
                    "application/epub+zip": "$epubUrl"
                },
                "download_count": 0
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(metaJson).setResponseCode(200))
        // 2nd request: the EPUB file bytes themselves.
        server.enqueue(MockResponse().setBody(okio.Buffer().write(epubBytes)).setResponseCode(200))

        val baseUrl = server.url("/").toString().trimEnd('/')
        val client = newClient(baseUrl)

        val feature = client.getFeature(HttpDownloadableTracker::class)
        assertNotNull("getFeature(HttpDownloadableTracker) must resolve", feature)

        val result = feature!!.downloadHttpFile("1342")

        // First the metadata request, then the file request.
        assertEquals("/books/1342/", server.takeRequest().path)
        assertEquals("/downloads/pg1342.epub", server.takeRequest().path)
        // User-visible artifact: the exact bytes the server served for the EPUB.
        assertTrue(
            "downloaded bytes must equal the served e-book bytes",
            result.bytes.contentEquals(epubBytes),
        )
        assertEquals("pg1342.epub", result.fileName)
        assertTrue(
            "sourceUrl must be the resolved EPUB format url",
            result.sourceUrl.endsWith("/downloads/pg1342.epub"),
        )
    }
}

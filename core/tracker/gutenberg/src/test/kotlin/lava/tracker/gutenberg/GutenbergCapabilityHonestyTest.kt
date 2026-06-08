package lava.tracker.gutenberg

import kotlinx.coroutines.runBlocking
import lava.tracker.api.TrackerCapability
import lava.tracker.api.feature.BrowsableTracker
import lava.tracker.api.feature.DownloadableTracker
import lava.tracker.api.feature.SearchableTracker
import lava.tracker.api.feature.TopicTracker
import lava.tracker.gutenberg.http.GutenbergHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Constitutional clause 6.E (Capability Honesty) — gutenberg.
 *
 * The contract this test pins:
 *  1. Every declared [TrackerCapability] that maps to a feature interface MUST
 *     resolve to a non-null impl via [GutenbergClient.getFeature].
 *  2. The download-capability honesty rule: a provider MAY declare
 *     [TrackerCapability.TORRENT_DOWNLOAD] ONLY IF the wired
 *     [DownloadableTracker.downloadTorrentFile] returns a real `.torrent`
 *     artifact (a bencoded byte stream — bencoded dictionaries begin with
 *     the ASCII byte 'd'). Project Gutenberg serves EPUB / plain-text / HTML
 *     e-books over HTTP, NOT `.torrent` files, so declaring TORRENT_DOWNLOAD
 *     is a bluff: capability-declared but the claimed artifact type is not
 *     produced.
 *
 * Against the historical bluff (descriptor declares TORRENT_DOWNLOAD while
 * the download impl returns EPUB bytes) the download-honesty assertion below
 * fails: the returned artifact is "epub-bytes...", which does not begin with
 * the bencode 'd' marker.
 */
class GutenbergCapabilityHonestyTest {

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
            search = lava.tracker.gutenberg.feature.GutenbergSearch(http),
            browse = lava.tracker.gutenberg.feature.GutenbergBrowse(http),
            topic = lava.tracker.gutenberg.feature.GutenbergTopic(http),
            download = lava.tracker.gutenberg.feature.GutenbergDownload(http, downloadBaseUrl),
        )
    }

    @Test
    fun `every declared capability resolves to a non-null feature`() {
        val client = newClient("https://unused")
        val caps = GutenbergDescriptor.capabilities

        if (TrackerCapability.SEARCH in caps) {
            assertNotNull(
                "SEARCH declared but getFeature(SearchableTracker) is null",
                client.getFeature(SearchableTracker::class),
            )
        }
        if (TrackerCapability.BROWSE in caps) {
            assertNotNull(
                "BROWSE declared but getFeature(BrowsableTracker) is null",
                client.getFeature(BrowsableTracker::class),
            )
        }
        if (TrackerCapability.TOPIC in caps) {
            assertNotNull(
                "TOPIC declared but getFeature(TopicTracker) is null",
                client.getFeature(TopicTracker::class),
            )
        }
    }

    @Test
    fun `download capability is honest about the artifact it produces`() = runBlocking {
        val caps = GutenbergDescriptor.capabilities

        if (TrackerCapability.TORRENT_DOWNLOAD in caps) {
            // If TORRENT_DOWNLOAD is declared, getFeature MUST return a real
            // DownloadableTracker AND downloadTorrentFile MUST yield a real
            // `.torrent` (bencoded dictionary, first byte == 'd').
            val baseUrl = server.url("/").toString().trimEnd('/')
            val client = newClient(baseUrl)
            val feature = client.getFeature(DownloadableTracker::class)
            assertNotNull(
                "TORRENT_DOWNLOAD declared but getFeature(DownloadableTracker) is null",
                feature,
            )

            // Gutendex returns the book metadata; gutenberg picks the EPUB url.
            val metaJson = """
                {
                    "id": 1342,
                    "title": "Pride and Prejudice",
                    "authors": [],
                    "formats": {
                        "application/epub+zip": "${server.url("/epub")}"
                    },
                    "download_count": 0
                }
            """.trimIndent()
            server.enqueue(MockResponse().setBody(metaJson).setResponseCode(200))
            server.enqueue(MockResponse().setBody("epub-bytes-not-a-torrent").setResponseCode(200))

            val bytes = feature!!.downloadTorrentFile("1342")
            assertTrue(
                "TORRENT_DOWNLOAD declared but downloaded artifact is not a bencoded " +
                    ".torrent (first byte was '${bytes.firstOrNull()?.toInt()?.toChar()}', " +
                    "expected 'd')",
                bytes.isNotEmpty() && bytes[0] == 'd'.code.toByte(),
            )
        } else {
            // TORRENT_DOWNLOAD NOT declared (honest state for an e-book HTTP
            // library): getFeature(DownloadableTracker) MUST return null so a
            // consumer never receives a non-.torrent artifact through the
            // torrent-download surface.
            assertFalse(
                "gutenberg does not produce .torrent files; TORRENT_DOWNLOAD must not be declared",
                TrackerCapability.TORRENT_DOWNLOAD in caps,
            )
            val client = newClient("https://unused")
            assertNull(
                "TORRENT_DOWNLOAD undeclared but getFeature(DownloadableTracker) is non-null",
                client.getFeature(DownloadableTracker::class),
            )
        }
    }
}

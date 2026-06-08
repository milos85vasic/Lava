package lava.tracker.kinozal.feature

import kotlinx.coroutines.runBlocking
import lava.tracker.kinozal.http.KinozalHttpClient
import lava.tracker.kinozal.magnet.KinozalMagnetCache
import lava.tracker.kinozal.parser.KinozalTopicParser
import lava.tracker.testing.LavaFixtureLoader
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KinozalDownloadTest {
    private lateinit var server: MockWebServer
    private val loader = LavaFixtureLoader(tracker = "kinozal")

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
        val feature = KinozalDownload(KinozalHttpClient(), KinozalMagnetCache(), baseUrl)

        val bytes = feature.downloadTorrentFile("12345")

        val recordedPath = server.takeRequest().path
        assertEquals("/download.php?id=12345", recordedPath)
        assertEquals("torrent-data", String(bytes))
    }

    /**
     * §6.E Capability Honesty: the Kinozal descriptor declares MAGNET_LINK and
     * the topic page DOES carry a real magnet (parsed by [KinozalTopicParser]).
     * This test drives the SAME production path a user crosses — open topic
     * (`KinozalTopic.getTopic`) then request the magnet
     * (`KinozalDownload.getMagnetLink`) — and asserts the real magnet reaches
     * the user. Before the fix, `getMagnetLink` hardcoded `null`, so the
     * declared capability returned nothing (a bluff).
     *
     * FALSIFIABILITY REHEARSAL: reverting `getMagnetLink` to `= null` (or
     * removing the `KinozalTopic.getTopic` cache write) makes the
     * `startsWith("magnet:")` assertion fail with
     * "magnet should be exposed after topic view".
     */
    @Test
    fun `getMagnetLink returns real magnet after the topic page has been viewed`() = runBlocking {
        val html = loader.load("topic", "topic-normal-2026-05-02.html")
        server.enqueue(MockResponse().setBody(html).setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')

        // Shared cache wires the user's topic view to the synchronous magnet lookup,
        // exactly as KinozalClient/KinozalClientFactory wire them in production.
        val cache = KinozalMagnetCache()
        val topic = KinozalTopic(KinozalHttpClient(), KinozalTopicParser(), cache, baseUrl)
        val download = KinozalDownload(KinozalHttpClient(), cache, baseUrl)

        // Before any topic view there is no synchronously-available magnet (honest null).
        assertNull("no magnet before topic view", download.getMagnetLink("12345"))

        // User opens the topic — the production parse extracts the real magnet.
        val detail = topic.getTopic("12345")
        assertTrue(
            "topic parse must expose magnet",
            detail.torrent.magnetUri?.startsWith("magnet:") == true,
        )

        // User taps "magnet" — the declared MAGNET_LINK capability now returns the real value.
        val magnet = download.getMagnetLink("12345")
        assertTrue(
            "magnet should be exposed after topic view",
            magnet?.startsWith("magnet:?xt=urn:btih:") == true,
        )
        assertEquals(detail.torrent.magnetUri, magnet)
    }
}

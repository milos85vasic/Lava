package lava.tracker.rutor.feature

import kotlinx.coroutines.runBlocking
import lava.tracker.api.model.SearchRequest
import lava.tracker.rutor.http.RuTorHttpClient
import lava.tracker.rutor.magnet.RuTorMagnetCache
import lava.tracker.rutor.parser.RuTorSearchParser
import lava.tracker.rutor.parser.RuTorTopicParser
import lava.tracker.testing.LavaFixtureLoader
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §6.E Capability Honesty regression test for the RuTor MAGNET_LINK capability.
 *
 * [lava.tracker.rutor.RuTorDescriptor] declares TrackerCapability.MAGNET_LINK.
 * Per §6.E that declaration MUST resolve to a feature that genuinely exposes a
 * magnet for a topic/result whose HTML carries one — NOT an unconditional
 * `null`.
 *
 * RuTor embeds the magnet in the topic page (`/torrent/<id>`) and in search
 * rows (`/search/...`), already extracted by [RuTorTopicParser] /
 * [RuTorSearchParser] into TorrentItem.magnetUri. The synchronous
 * [RuTorDownload.getMagnetLink] surfaces that genuinely-parsed magnet through an
 * in-memory [RuTorMagnetCache] populated by the real topic/search fetch — no
 * fabricated string, real production parse path.
 *
 * FALSIFIABILITY REHEARSAL: if `RuTorDownload.getMagnetLink` is reverted to
 * `return null` (the historical bluff that shipped before this fix), the first
 * two assertion blocks fail with "getMagnetLink must expose the genuinely-parsed
 * magnet after a topic fetch ...".
 */
class RuTorMagnetExposureTest {

    private lateinit var server: MockWebServer
    private val loader = LavaFixtureLoader(tracker = "rutor")

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getMagnetLink exposes the parsed magnet after the topic page surfaces it`() = runBlocking {
        val topicHtml = loader.load("topic", "topic-normal-2026-08-10.html")
        // RuTorTopic does a two-fetch dance: /torrent/<id> then /descriptions/<id>.files.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path?.contains("/torrent/") == true) {
                    MockResponse().setBody(topicHtml).setResponseCode(200)
                } else {
                    MockResponse().setBody("Not found").setResponseCode(404)
                }
        }
        val baseUrl = server.url("/").toString().trimEnd('/')

        val cache = RuTorMagnetCache()
        val http = RuTorHttpClient()
        val topic = RuTorTopic(http, RuTorTopicParser(), cache, baseUrl)
        val download = RuTorDownload(http, cache, baseUrl)

        // User opens the topic — the real fetch+parse path surfaces the magnet.
        val detail = topic.getTopic("1052665")
        assertTrue(
            "topic fixture must carry a magnet for this test to be meaningful",
            !detail.torrent.magnetUri.isNullOrEmpty(),
        )

        // The user-visible magnet exposed via getMagnetLink MUST be the genuine,
        // parsed magnet — not null, and identical to what the topic page yielded.
        val magnet = download.getMagnetLink("1052665")
        assertTrue(
            "getMagnetLink must expose the genuinely-parsed magnet after a topic fetch, got: $magnet",
            magnet != null && magnet.startsWith("magnet:?xt=urn:btih:"),
        )
        assertEquals(
            "getMagnetLink must return exactly the magnet the topic page surfaced",
            detail.torrent.magnetUri,
            magnet,
        )
    }

    @Test
    fun `getMagnetLink exposes the parsed magnet after a search row surfaces it`() = runBlocking {
        val html = loader.load("search", "search-normal-2026-08-10.html")
        server.enqueue(MockResponse().setBody(html).setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')

        val cache = RuTorMagnetCache()
        val http = RuTorHttpClient()
        val search = RuTorSearch(http, RuTorSearchParser(), cache, baseUrl)
        val download = RuTorDownload(http, cache, baseUrl)

        val result = search.search(SearchRequest(query = "test"), page = 0)
        val rowMagnet = result.items.firstOrNull { !it.magnetUri.isNullOrEmpty() }
        assertTrue(
            "search fixture must carry at least one row magnet for this test to be meaningful",
            rowMagnet != null,
        )

        val magnet = download.getMagnetLink(rowMagnet!!.torrentId)
        assertTrue(
            "getMagnetLink must expose the genuinely-parsed search-row magnet, got: $magnet",
            magnet != null && magnet.startsWith("magnet:?xt=urn:btih:"),
        )
        assertEquals(
            "getMagnetLink must return exactly the magnet the search row surfaced",
            rowMagnet.magnetUri,
            magnet,
        )
    }

    @Test
    fun `getMagnetLink returns null for an id never surfaced (honest absence)`() {
        val cache = RuTorMagnetCache()
        val download = RuTorDownload(RuTorHttpClient(), cache, "https://unused")
        // No topic/search fetch has surfaced this id — synchronous magnet is
        // genuinely unavailable without an HTTP fetch (per DownloadableTracker
        // contract). Honest null, not a bluff: the cache is empty.
        assertNull(download.getMagnetLink("99999"))
    }
}

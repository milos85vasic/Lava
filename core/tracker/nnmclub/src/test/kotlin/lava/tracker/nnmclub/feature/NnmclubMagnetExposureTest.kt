package lava.tracker.nnmclub.feature

import kotlinx.coroutines.runBlocking
import lava.tracker.nnmclub.http.NnmclubHttpClient
import lava.tracker.nnmclub.http.NnmclubMagnetCache
import lava.tracker.nnmclub.parser.NnmclubSearchParser
import lava.tracker.nnmclub.parser.NnmclubTopicParser
import lava.tracker.testing.LavaFixtureLoader
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §6.E Capability Honesty regression test for the NNM-Club MAGNET_LINK capability.
 *
 * NnmclubDescriptor declares TrackerCapability.MAGNET_LINK. Per §6.E that
 * declaration MUST resolve to a feature that genuinely exposes a magnet for a
 * topic whose HTML carries one — NOT an unconditional `null`.
 *
 * NNM-Club embeds the magnet in the topic page (`viewtopic.php?t=<id>`), already
 * extracted by [NnmclubTopicParser] into TopicDetail.torrent.magnetUri. The
 * synchronous [NnmclubDownload.getMagnetLink] surfaces that genuinely-parsed
 * magnet through an in-memory [NnmclubMagnetCache] populated by the real
 * topic-page fetch — no fabricated string, real production parse path.
 *
 * FALSIFIABILITY REHEARSAL: if `NnmclubDownload.getMagnetLink` is reverted to
 * `return null` (the historical bluff), the first assertion below fails with
 * "getMagnetLink must expose the genuinely-parsed magnet after a topic fetch ...".
 */
class NnmclubMagnetExposureTest {

    private lateinit var server: MockWebServer
    private val loader = LavaFixtureLoader(tracker = "nnmclub")

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
        val html = loader.load("topic", "topic-normal-2026-05-02.html")
        server.enqueue(MockResponse().setBody(html).setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')

        val cache = NnmclubMagnetCache()
        val http = NnmclubHttpClient()
        val topic = NnmclubTopic(http, NnmclubTopicParser(), cache, baseUrl)
        val download = NnmclubDownload(http, cache, baseUrl)

        // User opens the topic — the real fetch+parse path surfaces the magnet.
        val detail = topic.getTopic("1001")
        assertTrue(
            "topic fixture must carry a magnet for this test to be meaningful",
            !detail.torrent.magnetUri.isNullOrEmpty(),
        )

        // The user-visible magnet exposed via getMagnetLink MUST be the genuine,
        // parsed magnet — not null, and identical to what the topic page yielded.
        val magnet = download.getMagnetLink("1001")
        assertTrue(
            "getMagnetLink must expose the genuinely-parsed magnet after a topic fetch, got: $magnet",
            magnet != null && magnet.startsWith("magnet:?xt=urn:btih:"),
        )
        org.junit.Assert.assertEquals(
            "getMagnetLink must return exactly the magnet the topic page surfaced",
            detail.torrent.magnetUri,
            magnet,
        )
    }

    @Test
    fun `getMagnetLink exposes the parsed magnet after a search row surfaces it`() = runBlocking {
        val html = loader.load("search", "search-with-magnet-2026-06-08.html")
        server.enqueue(MockResponse().setBody(html).setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')

        val cache = NnmclubMagnetCache()
        val http = NnmclubHttpClient()
        val search = NnmclubSearch(http, NnmclubSearchParser(), cache, baseUrl)
        val download = NnmclubDownload(http, cache, baseUrl)

        val result = search.search(
            lava.tracker.api.model.SearchRequest(query = "ubuntu"),
            page = 0,
        )
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
        org.junit.Assert.assertEquals(
            "getMagnetLink must return exactly the magnet the search row surfaced",
            rowMagnet.magnetUri,
            magnet,
        )
    }

    @Test
    fun `getMagnetLink returns null for an id never surfaced (honest absence)`() {
        val cache = NnmclubMagnetCache()
        val download = NnmclubDownload(NnmclubHttpClient(), cache, "https://unused")
        // No topic/search fetch has surfaced this id — synchronous magnet is
        // genuinely unavailable without an HTTP fetch (per DownloadableTracker
        // contract). Honest null, not a bluff: the cache is empty.
        assertNull(download.getMagnetLink("99999"))
    }
}

package lava.tracker.iptorrents.feature

import kotlinx.coroutines.runBlocking
import lava.tracker.api.model.SearchRequest
import lava.tracker.iptorrents.http.IPTorrentsJackettApi
import lava.tracker.iptorrents.model.IPTorrentsResultCache
import lava.tracker.iptorrents.model.JackettResultMapper
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §6.E Capability-Honesty cold-cache regression for the IPTorrents MAGNET_LINK
 * capability.
 *
 * THE BLUFF THIS TEST EXISTS TO CATCH
 * -----------------------------------
 * [lava.tracker.iptorrents.IPTorrentsDescriptor] declares
 * `TrackerCapability.MAGNET_LINK`, and [IPTorrentsClient.getFeature] resolves it
 * to a non-null [IPTorrentsDownload]. Per §6.E a declared capability MUST resolve
 * to a feature that genuinely works AND tells the truth about absence:
 * [IPTorrentsDownload.getMagnetLink] reads from the parse-populated
 * [IPTorrentsResultCache] and MUST return an HONEST `null` on a cold cache (an id
 * the search path never surfaced) — NOT a fabricated magnet, NOT a crash.
 *
 * The sibling [IPTorrentsSearchDelegationTest] proves the cache is populated by
 * the real `/jackett/search` route parse path; this test pins the OTHER half of
 * the §6.E contract that no existing IPTorrents test asserts in isolation:
 *   (a) cold cache → getMagnetLink returns null (honest absence);
 *   (b) after the REAL production search parse populates the cache, getMagnetLink
 *       returns exactly the `magnet:?xt=urn:btih:<40-hex>` the route row carried,
 *       with the right info-hash;
 *   (c) an id that exists in the search result but carried NO magnet stays null
 *       (the torrent-only row) — absence is per-id-and-per-surface, not blanket.
 *
 * ## What is real, what is faked (Seventh Law clause 4)
 * SUT = the full production cold-cache path:
 *   [IPTorrentsSearch] → [IPTorrentsJackettApi] (real OkHttp) → real HTTP GET →
 *   [JackettResultMapper] (real JSON decode) → [IPTorrentsResultCache] →
 *   [IPTorrentsDownload.getMagnetLink]. Only the network SOCKET is faked via
 *   [MockWebServer]; the fixture is the EXACT JSON wire format lava-api-go's
 *   `GET /jackett/search` route emits.
 *
 * ## Primary assertion (Sixth Law clause 3 — user-visible state)
 * The magnet URI the user would tap-to-copy/open after a search, surfaced through
 * the declared MAGNET_LINK capability — and its honest null when none exists.
 *
 * FALSIFIABILITY REHEARSAL (§6.J clause 2):
 *   Mutation A — make [IPTorrentsDownload.getMagnetLink] return a hardcoded
 *     `"magnet:?xt=urn:btih:" + "0".repeat(40)` regardless of the cache. The
 *     cold-cache assertion FAILS with
 *     "getMagnetLink on a cold cache must be an honest null, got: magnet:?xt=...".
 *   Mutation B — drop the `cache.put(...)` line in [IPTorrentsSearch.search]. The
 *     after-search assertion FAILS with
 *     "getMagnetLink must surface the parsed magnet after a search, got: null".
 *   Mutation C — in [JackettResultMapper.toTorrentItem] map `magnetUri` from
 *     `downloadUrl` instead of `magnetLink`. The exact-magnet equality assertion
 *     FAILS (the cached value would be the /dl/ link, not the btih magnet).
 *   Each reverted; re-run green.
 */
class IPTorrentsMagnetColdCacheTest {

    private lateinit var server: MockWebServer

    private val searchFixture: String =
        readResource("fixtures/iptorrents/search/jackett-search-iptorrents-2026-06-08.json")

    // The fixture's three rows (see jackett-search-iptorrents-2026-06-08.json):
    //   row 0: magnet + .torrent  (id .../details.php?id=8675309)
    //   row 1: magnet only        (id .../details.php?id=1111111)
    //   row 2: .torrent only      (id .../details.php?id=2222222)  ← no magnet
    private val rowWithMagnetId = "https://iptorrents.com/details.php?id=8675309"
    private val rowTorrentOnlyId = "https://iptorrents.com/details.php?id=2222222"
    private val neverSurfacedId = "https://iptorrents.com/details.php?id=404404"

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun newSearch(cache: IPTorrentsResultCache): IPTorrentsSearch =
        IPTorrentsSearch(
            api = IPTorrentsJackettApi(),
            mapper = JackettResultMapper(),
            cache = cache,
            baseUrl = server.url("/").toString().trimEnd('/'),
        )

    @Test
    fun `getMagnetLink on a cold cache returns null (honest absence, no fabricated magnet, no crash)`() {
        val cache = IPTorrentsResultCache()
        val download = IPTorrentsDownload(api = IPTorrentsJackettApi(), cache = cache)

        // No search has populated the cache — the synchronous magnet is genuinely
        // unavailable. Honest null, NOT a fabricated magnet (per DownloadableTracker
        // contract). This is the half the delegation test never asserts in isolation.
        val magnet = download.getMagnetLink(rowWithMagnetId)
        assertNull(
            "getMagnetLink on a cold cache must be an honest null, got: $magnet",
            magnet,
        )
    }

    @Test
    fun `getMagnetLink surfaces the parsed magnet after the real search populates the cache`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(searchFixture))
        val cache = IPTorrentsResultCache()
        val search = newSearch(cache)
        val download = IPTorrentsDownload(api = IPTorrentsJackettApi(), cache = cache)

        // The user runs an IPTorrents search — the real route parse populates the cache.
        val result = search.search(SearchRequest(query = "ubuntu"), page = 0)
        val parsedRow = result.items.first { it.torrentId == rowWithMagnetId }
        assertTrue(
            "search fixture must carry a magnet for this row for the test to be meaningful",
            !parsedRow.magnetUri.isNullOrEmpty(),
        )

        // The user taps "magnet" — the declared MAGNET_LINK capability now returns
        // the GENUINELY-parsed magnet, identical to what the search row carried.
        val magnet = download.getMagnetLink(rowWithMagnetId)
        assertTrue(
            "getMagnetLink must surface the parsed magnet after a search, got: $magnet",
            magnet != null && magnet.startsWith("magnet:?xt=urn:btih:"),
        )
        assertEquals(
            "getMagnetLink must return exactly the magnet the search row surfaced",
            parsedRow.magnetUri,
            magnet,
        )
        // The right info-hash reached the user: the magnet's btih must equal the
        // row's parsed infoHash (lowercase 40-hex), proving it is not a fabricated
        // or mismatched value.
        assertEquals(
            "magnet info-hash must match the parsed row infoHash",
            parsedRow.infoHash,
            magnet!!.substringAfter("urn:btih:").substringBefore("&"),
        )
        assertEquals(
            "info-hash must be a 40-char SHA-1 hex",
            40,
            magnet.substringAfter("urn:btih:").substringBefore("&").length,
        )
    }

    @Test
    fun `getMagnetLink stays null for a torrent-only row that carried no magnet`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(searchFixture))
        val cache = IPTorrentsResultCache()
        val search = newSearch(cache)
        val download = IPTorrentsDownload(api = IPTorrentsJackettApi(), cache = cache)

        val result = search.search(SearchRequest(query = "ubuntu"), page = 0)
        val torrentOnly = result.items.first { it.torrentId == rowTorrentOnlyId }
        assertNull(
            "fixture's torrent-only row must carry NO magnet for this test to be meaningful",
            torrentOnly.magnetUri,
        )

        // Even after a successful search that DID populate other rows' magnets, an
        // id whose row carried no magnet stays an honest null — absence is per-id
        // and per-surface, never blanket-fabricated.
        assertNull(
            "torrent-only row must have no magnet after search",
            download.getMagnetLink(rowTorrentOnlyId),
        )
        // And an id that the search never returned at all also stays null.
        assertNull(
            "an id never surfaced by any search row must return null",
            download.getMagnetLink(neverSurfacedId),
        )
    }

    private fun readResource(path: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "missing test resource: $path"
        }.bufferedReader().use { it.readText() }
}

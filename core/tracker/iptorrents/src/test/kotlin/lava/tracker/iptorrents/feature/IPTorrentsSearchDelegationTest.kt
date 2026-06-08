package lava.tracker.iptorrents.feature

import kotlinx.coroutines.runBlocking
import lava.tracker.api.model.SearchRequest
import lava.tracker.iptorrents.http.IPTorrentsJackettApi
import lava.tracker.iptorrents.model.IPTorrentsResultCache
import lava.tracker.iptorrents.model.JackettResultMapper
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Delegation + mapping Challenge Test for the IPTorrents Jackett-delegating
 * provider.
 *
 * ## What is real, what is faked (Seventh Law clause 4)
 * The SUT is the FULL production delegation path:
 *   [IPTorrentsSearch] → [IPTorrentsJackettApi] (real OkHttp) → real HTTP GET →
 *   [JackettResultMapper] (real JSON decode) → domain [TorrentItem]s →
 *   [IPTorrentsResultCache] → [IPTorrentsDownload] (.torrent fetch + magnet).
 * Only the network SOCKET is faked, via [MockWebServer]. The fixture
 * [searchFixture] is the EXACT JSON wire format lava-api-go's
 * `GET /jackett/search` route emits (a `provider.SearchResult` — see
 * `lava-api-go/internal/handlers/v1/jackett.go`), NOT invented site HTML. That
 * makes this honest: it tests the contract lava-api-go actually produces.
 *
 * ## Primary assertions (Sixth Law clause 3 — user-visible state)
 *   - the request hit `/jackett/search?indexer=iptorrents&q=<query>` (the route
 *     the user's IPTorrents search triggers);
 *   - the mapped domain items carry the title, .torrent downloadUrl, magnet, and
 *     infohash the user sees in results;
 *   - downloading by id returns the real .torrent BYTES MockWebServer served at
 *     the cached /dl/ link.
 *
 * ## Falsifiability rehearsal (§6.J clause 2)
 * Deliberate break during authoring: in [JackettResultMapper.toTorrentItem],
 * map `downloadUrl` from `magnetLink` instead of `downloadUrl`. Re-run →
 * `first result .torrent downloadUrl must be the Jackett /dl/ link` FAILS
 * (`expected https://…/dl/… but was magnet:?xt=…`). Reverted. Second rehearsal:
 * drop the `cache.put(...)` line in [IPTorrentsSearch.search] → the by-id
 * download assertion FAILS with the cache-miss `error(...)` message. Reverted.
 */
class IPTorrentsSearchDelegationTest {

    private lateinit var server: MockWebServer

    private val searchFixture: String =
        readResource("fixtures/iptorrents/search/jackett-search-iptorrents-2026-06-08.json")

    // The .torrent bytes MockWebServer serves at the /dl/ link. A real .torrent
    // begins with the bencode dictionary marker for "8:announce..."; any
    // distinctive byte sequence proves the bytes flowed through unmodified.
    private val torrentBytes: ByteArray = "d8:announce11:test-trackere".toByteArray()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
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
    fun `search delegates to the jackett route and maps the JSON into domain items`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(searchFixture))
        val cache = IPTorrentsResultCache()

        val result = newSearch(cache).search(SearchRequest(query = "ubuntu"), page = 0)

        // --- the request hit the right route (the user's IPTorrents search path) ---
        val recorded: RecordedRequest = server.takeRequest()
        assertEquals("GET", recorded.method)
        val path = recorded.path ?: ""
        assertTrue("must hit /jackett/search; was '$path'", path.startsWith("/jackett/search"))
        assertTrue("must scope the indexer=iptorrents; was '$path'", path.contains("indexer=iptorrents"))
        assertTrue("must carry the query q=ubuntu; was '$path'", path.contains("q=ubuntu"))

        // --- the mapped domain items carry the user-visible fields ---
        assertEquals(3, result.items.size)
        val first = result.items[0]
        assertEquals("iptorrents", first.trackerId)
        assertEquals("Ubuntu 24.04 LTS Desktop amd64", first.title)
        assertEquals(6221934592L, first.sizeBytes)
        assertEquals(142, first.seeders)
        assertEquals("c12fe1c06bba254a9dc9f519b335aa7c1367a88a", first.infoHash)
        assertTrue(
            "first result .torrent downloadUrl must be the Jackett /dl/ link; was ${first.downloadUrl}",
            first.downloadUrl!!.contains("/jackett/dl/iptorrents/"),
        )
        assertTrue(
            "first result magnet must be the magnet URI; was ${first.magnetUri}",
            first.magnetUri!!.startsWith("magnet:?xt=urn:btih:c12fe1c0"),
        )

        // --- a magnet-only row maps magnet but no .torrent downloadUrl ---
        val magnetOnly = result.items[1]
        assertEquals("Debian 12 netinst", magnetOnly.title)
        assertTrue("magnet-only row keeps its magnet", magnetOnly.magnetUri!!.startsWith("magnet:?xt="))

        // --- a .torrent-only row maps downloadUrl but no magnet ---
        val torrentOnly = result.items[2]
        assertEquals("Arch Linux 2026.06 ISO", torrentOnly.title)
        assertTrue("torrent-only row keeps its /dl/ link", torrentOnly.downloadUrl!!.contains("/jackett/dl/"))
    }

    @Test
    fun `downloadTorrentFile resolves the cached dl link and returns the real bytes`() = runBlocking {
        // 1st response: the search JSON. 2nd response: the .torrent bytes at /dl/.
        server.enqueue(MockResponse().setResponseCode(200).setBody(searchFixture))
        server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(torrentBytes)))

        val cache = IPTorrentsResultCache()
        val search = newSearch(cache)
        val download = IPTorrentsDownload(api = IPTorrentsJackettApi(), cache = cache)

        // The fixture's downloadUrl points at https://localhost:8443/...; MockWebServer
        // serves a different host:port, so rewrite the cached /dl/ link to THIS server
        // for the download leg. (In production the route hands back the real sidecar
        // /dl/ link and no rewrite is needed.)
        search.search(SearchRequest(query = "ubuntu"), page = 0)
        server.takeRequest() // consume the search request
        val firstId = "https://iptorrents.com/details.php?id=8675309"
        cache.put(
            torrentId = firstId,
            magnetUri = null,
            downloadUrl = server.url("/jackett/dl/iptorrents/ubuntu-24.04.torrent").toString(),
        )

        val bytes = download.downloadTorrentFile(firstId)

        // Primary assertion: the EXACT bytes MockWebServer served came back through
        // the real OkHttp download path — proves the .torrent download genuinely works.
        assertTrue("downloaded .torrent must be non-empty", bytes.isNotEmpty())
        assertEquals(
            "downloaded .torrent bytes must match what the /dl/ link served",
            torrentBytes.toList(),
            bytes.toList(),
        )

        // And the magnet surfaces synchronously from the cache for the same id.
        cache.put(firstId, magnetUri = "magnet:?xt=urn:btih:c12fe1c0", downloadUrl = null)
        assertEquals("magnet:?xt=urn:btih:c12fe1c0", download.getMagnetLink(firstId))
    }

    private fun readResource(path: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "missing test resource: $path"
        }.bufferedReader().use { it.readText() }
}

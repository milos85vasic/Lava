package lava.tracker.client

import kotlinx.coroutines.test.runTest
import lava.tracker.api.AuthType
import lava.tracker.api.RemoteTrackerDescriptor
import lava.tracker.api.TrackerCapability
import lava.tracker.api.feature.AuthenticatableTracker
import lava.tracker.api.feature.BrowsableTracker
import lava.tracker.api.feature.DownloadableTracker
import lava.tracker.api.feature.SearchableTracker
import lava.tracker.api.model.AuthState
import lava.tracker.api.model.LoginRequest
import lava.tracker.api.model.SearchRequest
import okhttp3.OkHttpClient
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
 * Real-stack test for [ApiBackedTrackerClient] (Dynamic Provider Discovery,
 * spec §4.2, plan Task 4.1).
 *
 * Anti-Bluff posture (§6.J / Seventh Law clause 4):
 *  - The SUT ([ApiBackedTrackerClient]) is a REAL instance — never mocked.
 *  - The ONLY faked boundary is the network socket, replaced by [MockWebServer]
 *    (the same boundary [lava.data.provider.ProviderCatalogRepository] fakes).
 *  - Primary assertions are on user-visible/wire-observable state: the recorded
 *    HTTP request PATH the client issued, and the parsed domain result the user
 *    would see (§6.AB clause 1 — observable outcome, not "mock was called").
 *
 * FALSIFIABILITY REHEARSAL (Sixth Law clause 2 / §6.AB clause 3):
 *  Mutating [ApiBackedTrackerClient.search]'s path template from
 *  "/v1/$trackerId/search" to "/v1/$trackerId/find" makes
 *  [search_issuesV1ProviderSearchPath_andParsesResult] FAIL with
 *  "expected:</v1/rutracker/search?...> but was:</v1/rutracker/find?...>".
 *  Mutating the capability gate so BrowsableTracker resolves unconditionally
 *  makes [getFeature_returnsNull_forUndeclaredCapability] FAIL.
 */
class ApiBackedTrackerClientTest {

    private lateinit var server: MockWebServer
    private val httpClient = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** A descriptor that declares SEARCH + TORRENT_DOWNLOAD only (no BROWSE, no AUTH). */
    private fun searchDownloadDescriptor() = RemoteTrackerDescriptor.from(
        trackerId = "rutracker",
        displayName = "RuTracker.org",
        capabilities = listOf("SEARCH", "TORRENT_DOWNLOAD"),
        authType = "NONE",
        baseUrls = listOf("https://rutracker.org"),
        encoding = "UTF-8",
        supportsAnonymous = true,
    )

    private fun newClient(descriptor: RemoteTrackerDescriptor) =
        ApiBackedTrackerClient(
            descriptor = descriptor,
            apiBaseUrl = server.url("/").toString().trimEnd('/'),
            httpClient = httpClient,
        )

    @Test
    fun getFeature_returnsNonNull_forDeclaredSearchCapability() {
        val client = newClient(searchDownloadDescriptor())
        assertNotNull(
            "SEARCH declared ⇒ SearchableTracker MUST resolve (6.E capability honesty)",
            client.getFeature(SearchableTracker::class),
        )
    }

    @Test
    fun getFeature_returnsNull_forUndeclaredCapability() {
        val client = newClient(searchDownloadDescriptor())
        assertNull(
            "BROWSE not declared ⇒ BrowsableTracker MUST be null (capability honesty)",
            client.getFeature(BrowsableTracker::class),
        )
        assertNull(
            "AUTH_REQUIRED not declared ⇒ AuthenticatableTracker MUST be null",
            client.getFeature(AuthenticatableTracker::class),
        )
    }

    @Test
    fun search_issuesV1ProviderSearchPath_andParsesResult() = runTest {
        // Real lava-api-go SearchResult wire shape (internal/provider/provider.go).
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "provider": "rutracker",
                  "page": 0,
                  "totalPages": 3,
                  "results": [
                    {
                      "id": "12345",
                      "title": "Ubuntu 24.04 LTS",
                      "sizeBytes": 4700000000,
                      "seeders": 42,
                      "leechers": 3,
                      "magnetLink": "magnet:?xt=urn:btih:ABCDEF",
                      "downloadUrl": "https://rutracker.org/dl/12345.torrent",
                      "infoHash": "ABCDEF",
                      "category": "OS"
                    }
                  ]
                }
                """.trimIndent(),
            ).setHeader("Content-Type", "application/json"),
        )

        val client = newClient(searchDownloadDescriptor())
        val searchable = client.getFeature(SearchableTracker::class)!!
        val result = searchable.search(SearchRequest(query = "ubuntu"), page = 0)

        // Primary assertion #1 — the exact wire path the client issued.
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/v1/rutracker/search?query=ubuntu&page=0", recorded.path)

        // Primary assertion #2 — the parsed domain result a user would see.
        assertEquals(3, result.totalPages)
        assertEquals(0, result.currentPage)
        assertEquals(1, result.items.size)
        val item = result.items.single()
        assertEquals("rutracker", item.trackerId)
        assertEquals("12345", item.torrentId)
        assertEquals("Ubuntu 24.04 LTS", item.title)
        assertEquals(4700000000L, item.sizeBytes)
        assertEquals(42, item.seeders)
        assertEquals("magnet:?xt=urn:btih:ABCDEF", item.magnetUri)
        assertEquals("https://rutracker.org/dl/12345.torrent", item.downloadUrl)
    }

    @Test
    fun download_issuesV1ProviderDownloadPath_andReturnsBytes() = runTest {
        val torrentBytes = byteArrayOf(0x64, 0x38, 0x3A) // "d8:" — bencode start
        server.enqueue(
            MockResponse()
                .setBody(okio.Buffer().write(torrentBytes))
                .setHeader("Content-Type", "application/x-bittorrent"),
        )

        val client = newClient(searchDownloadDescriptor())
        val downloadable = client.getFeature(DownloadableTracker::class)!!
        val bytes = downloadable.downloadTorrentFile("12345")

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/v1/rutracker/download/12345", recorded.path)
        assertTrue("downloaded torrent bytes round-trip", torrentBytes.contentEquals(bytes))
    }

    @Test
    fun auth_isResolved_andLoginIssuesV1ProviderLoginPath() = runTest {
        val authDescriptor = RemoteTrackerDescriptor.from(
            trackerId = "rutracker",
            displayName = "RuTracker.org",
            capabilities = listOf("SEARCH", "AUTH_REQUIRED"),
            authType = "FORM_LOGIN",
            baseUrls = listOf("https://rutracker.org"),
            encoding = "UTF-8",
            supportsAnonymous = false,
        )
        server.enqueue(
            MockResponse().setBody(
                """{"state":"Authenticated","sessionToken":"sess-abc"}""",
            ).setHeader("Content-Type", "application/json"),
        )

        val client = newClient(authDescriptor)
        val auth = client.getFeature(AuthenticatableTracker::class)
        assertNotNull("AUTH_REQUIRED declared ⇒ AuthenticatableTracker MUST resolve", auth)

        val result = auth!!.login(LoginRequest(username = "alice", password = "pw"))

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/rutracker/login", recorded.path)
        assertEquals(AuthState.Authenticated, result.state)
        assertEquals("sess-abc", result.sessionToken)
    }

    @Test
    fun descriptor_isTheRemoteDescriptor() {
        val descriptor = searchDownloadDescriptor()
        val client = newClient(descriptor)
        assertEquals(descriptor, client.descriptor)
        assertEquals("rutracker", client.descriptor.trackerId)
        assertTrue(TrackerCapability.SEARCH in client.descriptor.capabilities)
        assertEquals(AuthType.NONE, client.descriptor.authType)
    }
}

package lava.tracker.client

import kotlinx.coroutines.test.runTest
import lava.tracker.api.RemoteTrackerDescriptor
import lava.tracker.api.feature.DownloadableTracker
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Real-stack contract test for the goapi-catalogue `.torrent`-DOWNLOAD path of
 * [ApiBackedTrackerClient] — the dynamic client the app builds for a provider
 * vended by `GET /v1/providers`.
 *
 * The production defect this guards (incident
 * `.lava-ci-evidence/sixth-law-incidents/2026-07-03-rutracker-kinozal-torrent-download-stall.json`):
 * the `.torrent` byte-fetch went to the endpoint's ROOT `/download/:id`, which the
 * goapi routes to RuTracker ONLY. So a KINOZAL (or any non-rutracker) download
 * fetched from rutracker.org with a foreign id → an HTML error page → the bencode
 * guard rejected it → DownloadState.Error on device. The fix routes the fetch
 * PROVIDER-AWARE to `/v1/{trackerId}/download/:id` (this client's `download` op).
 *
 * Anti-Bluff posture (§6.J / Seventh Law clause 4):
 *  - The SUT ([ApiBackedTrackerClient]) is a REAL instance — never mocked.
 *  - The ONLY faked boundary is the network socket ([MockWebServer]).
 *  - Primary assertions are on the user-visible artifact: the exact BYTES that
 *    would be written to the user's Downloads folder — AND that the request hit
 *    the PROVIDER-AWARE `/v1/kinozal/download/:id` route (NOT root `/download`),
 *    carrying BOTH auth gates (`Lava-Auth` client key + `Auth-Token` provider
 *    session). Those two facts ARE the bug and the fix.
 *
 * FALSIFIABILITY / REPRODUCE-FIRST (Sixth Law clause 2 / §6.T.1) — actually run:
 *  Mutation: change [ApiBackedTrackerClient.baseUrl] to build the ROOT route
 *            (`apiBaseUrl + "/download/" + op` — the shipped-bug shape) for the
 *            download op.
 *  Observed: [downloadTorrentFile_hitsProviderAwareRouteWithBothAuthGates] RED at
 *            `download MUST GET /v1/kinozal/download/6875869 — was /download/6875869`.
 *  Reverted: yes — the /v1/{provider}/download route IS the fix under test.
 */
class ApiBackedTrackerClientTorrentDownloadContractTest {

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
        // The session-token holder is process-wide — clear it so this test never
        // leaks a kinozal session into a sibling test.
        ProviderSessionTokenHolder.reset()
    }

    /** A kinozal-shaped descriptor: TORRENT_DOWNLOAD capability, auth-required. */
    private fun kinozalDescriptor() = RemoteTrackerDescriptor.from(
        trackerId = "kinozal",
        displayName = "Kinozal",
        capabilities = listOf("SEARCH", "TOPIC", "TORRENT_DOWNLOAD"),
        authType = "FORM_LOGIN",
        baseUrls = listOf("https://kinozal.tv"),
        encoding = "windows-1251",
        supportsAnonymous = false,
    )

    private fun client(descriptor: RemoteTrackerDescriptor = kinozalDescriptor()) =
        ApiBackedTrackerClient(
            descriptor = descriptor,
            apiBaseUrl = server.url("/").toString().trimEnd('/'),
            httpClient = httpClient,
            authFieldName = "Lava-Auth",
            authKey = "client-key",
        )

    /**
     * The load-bearing test: a non-rutracker TORRENT_DOWNLOAD provider MUST expose
     * the feature, and downloading MUST (a) return the exact server bytes, (b) hit
     * the PROVIDER-AWARE `/v1/kinozal/download/:id` route — NOT the rutracker-only
     * root `/download/:id`, and (c) carry BOTH auth gates.
     */
    @Test
    fun downloadTorrentFile_hitsProviderAwareRouteWithBothAuthGates() = runTest {
        // The provider login session (Gate 2) is captured at login time; the client
        // reads it LIVE from the holder and formats it as `{provider}:cookie:{sess}`.
        ProviderSessionTokenHolder.set("kinozal", "kinozal-session-cookie")

        val torrentBytes = "d8:announce16:http://x/announce4:infod4:name8:file.iso12:piece lengthi262144eee"
            .toByteArray(Charsets.UTF_8)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/x-bittorrent")
                .setBody(Buffer().write(torrentBytes)),
        )

        val feature = client().getFeature(DownloadableTracker::class)
        assertNotNull(
            "a TORRENT_DOWNLOAD descriptor MUST expose the Downloadable feature (Capability Honesty §6.E)",
            feature,
        )

        val bytes = feature!!.downloadTorrentFile("6875869")

        // PRIMARY (§6.J clause 3) — the user-visible artifact: the exact bytes that
        // land on disk (so a BitTorrent client can open the saved file).
        assertArrayEquals(
            "downloaded bytes MUST equal the server-served .torrent bytes",
            torrentBytes,
            bytes,
        )

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        // (b) PROVIDER-AWARE route — the whole point of the fix: the fetch hits
        // /v1/kinozal/download/:id, NOT the endpoint's rutracker-only root
        // /download/:id (which the goapi routes to rutracker.org and which fails
        // for every non-rutracker provider).
        assertEquals(
            "download MUST GET the provider-aware /v1/kinozal/download/:id route",
            "/v1/kinozal/download/6875869",
            recorded.path,
        )
        // (c) BOTH auth gates present.
        assertEquals("Gate 1: Lava-Auth client key", "client-key", recorded.getHeader("Lava-Auth"))
        assertEquals(
            "Gate 2: Auth-Token carries the provider login session in {provider}:cookie:{sess} form",
            "kinozal:cookie:kinozal-session-cookie",
            recorded.getHeader("Auth-Token"),
        )
    }

    /**
     * Discrimination / non-vacuity guard (§6.AB clause 3): a provider that does
     * NOT declare TORRENT_DOWNLOAD MUST NOT expose the feature — proving the
     * positive test resolves the feature because the capability is honored, not
     * because getFeature blindly returns non-null.
     */
    @Test
    fun noTorrentDownloadCapability_doesNotExposeFeature() {
        val httpOnly = RemoteTrackerDescriptor.from(
            trackerId = "gutenberg",
            displayName = "Project Gutenberg",
            capabilities = listOf("SEARCH", "HTTP_DOWNLOAD"),
            authType = "NONE",
            baseUrls = listOf("https://gutenberg.org"),
            encoding = "UTF-8",
            supportsAnonymous = true,
        )
        assertNull(
            "a provider without TORRENT_DOWNLOAD MUST NOT expose the Downloadable feature",
            client(httpOnly).getFeature(DownloadableTracker::class),
        )
    }
}

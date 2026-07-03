package lava.tracker.client

import kotlinx.coroutines.runBlocking
import lava.sdk.api.PluginConfig
import lava.tracker.api.RemoteTrackerDescriptor
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.registry.DefaultTrackerRegistry
import lava.tracker.registry.TrackerClientFactory
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 2026-07-03 reroute — real-stack verification (§6.G) that the PROVIDER-AWARE
 * `.torrent`-download capability is USER-REACHABLE through the SDK seam this
 * increment adds:
 *
 *   LavaTrackerSdk.downloadTorrentFile(trackerId, id)
 *     → clientFor(trackerId)                                  [provider-aware]
 *     → real ApiBackedTrackerClient.getFeature(DownloadableTracker)  [§6.E gate]
 *     → real downloadTorrentFile(id) → GET /v1/{trackerId}/download/{id}
 *     → real OkHttp transport over the (mock) network socket.
 *
 * This is the `.torrent` twin of [LavaTrackerSdkHttpDownloadRealStackTest]. The
 * SUT is the SDK routing chain + the real production [ApiBackedTrackerClient] +
 * the real OkHttp transport; the ONLY faked boundary is the HTTP socket
 * ([MockWebServer]).
 *
 * Primary assertion is on user-visible state: the BYTES the SDK returns are
 * exactly the bytes the (mock) server served (the bytes that would be written to
 * the user's Downloads folder), AND the request hit the PROVIDER-AWARE
 * `/v1/kinozal/download/{id}` route — the whole point of the fix (the shipped bug
 * fetched from the endpoint's rutracker-only root `/download/{id}`).
 *
 * ## Falsifiability rehearsal (§6.J clause 2 / §6.T.1 fail-first)
 *   Mutation A — [LavaTrackerSdk.downloadTorrentFile] resolve
 *     `getFeature(DownloadableTracker::class)` → hard-coded `null`. Test
 *     `downloadTorrentFile returns the exact server bytes…` FAILS at
 *     `assertNotNull(bytes)` → "SDK `.torrent` seam returns null; not
 *     user-reachable".
 *   Mutation B — [ApiBackedTrackerClient.baseUrl] built as the ROOT
 *     `apiBaseUrl + "/download/" + op` (the shipped-bug shape). Test FAILS at
 *     `download MUST hit /v1/kinozal/download/…` — the request went to the
 *     rutracker-only root route, exactly the device failure.
 */
class LavaTrackerSdkTorrentDownloadRealStackTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /**
     * Registers a factory under a kinozal-shaped [RemoteTrackerDescriptor]
     * (TORRENT_DOWNLOAD) whose [create] returns a production
     * [ApiBackedTrackerClient] pointed at [baseUrl] (the mock host) — the exact
     * dynamic client the app builds for a goapi-catalogue provider.
     */
    private fun sdkForKinozal(baseUrl: String): LavaTrackerSdk {
        val remoteDescriptor = RemoteTrackerDescriptor.from(
            trackerId = "kinozal",
            displayName = "Kinozal",
            capabilities = listOf("SEARCH", "TOPIC", "TORRENT_DOWNLOAD"),
            authType = "FORM_LOGIN",
            baseUrls = listOf("https://kinozal.tv"),
            encoding = "windows-1251",
            supportsAnonymous = false,
        )
        val client = ApiBackedTrackerClient(
            descriptor = remoteDescriptor,
            apiBaseUrl = baseUrl,
            httpClient = OkHttpClient(),
            authFieldName = "Lava-Auth",
            authKey = "client-key",
        )
        val factory = object : TrackerClientFactory {
            override val descriptor: TrackerDescriptor = remoteDescriptor
            override fun create(config: PluginConfig): TrackerClient = client
        }
        val registry = DefaultTrackerRegistry().apply { register(factory) }
        return LavaTrackerSdk(registry)
    }

    @Test
    fun `downloadTorrentFile returns the exact server bytes for a kinozal TORRENT provider`() = runBlocking {
        val expected = "d8:announce16:http://x/announce4:infod4:name8:file.iso12:piece lengthi262144eee"
            .toByteArray(Charsets.UTF_8)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(expected)),
        )
        val sdk = sdkForKinozal(server.url("/").toString().trimEnd('/'))

        val bytes = sdk.downloadTorrentFile("kinozal", "6875869")

        // §6.J primary — user-visible state: the exact bytes that would land on disk.
        assertNotNull("SDK must resolve the TORRENT_DOWNLOAD feature and return bytes", bytes)
        assertArrayEquals("downloaded bytes must equal the server-served bytes", expected, bytes)

        // Proves the SDK routed PROVIDER-AWARE (not the rutracker-only root route).
        val recorded = server.takeRequest()
        assertTrue(
            "download MUST GET /v1/kinozal/download/6875869 — was ${recorded.path}",
            recorded.path?.endsWith("/v1/kinozal/download/6875869") == true,
        )
    }

    @Test
    fun `downloadTorrentFile returns null for an unknown provider (no fabricated artifact)`() = runBlocking {
        val sdk = sdkForKinozal(server.url("/").toString().trimEnd('/'))

        // An unknown tracker id must resolve to null, never throw, and never
        // fabricate bytes (§6.E capability honesty).
        val bytes = sdk.downloadTorrentFile("does-not-exist", "anything")

        assertNull("unknown provider must yield null, not fabricated bytes", bytes)
    }
}

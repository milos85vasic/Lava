package lava.tracker.client

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import lava.sdk.api.MapPluginConfig
import lava.sdk.api.PluginConfig
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.archiveorg.ArchiveOrgClient
import lava.tracker.archiveorg.ArchiveOrgClientFactory
import lava.tracker.archiveorg.ArchiveOrgDescriptor
import lava.tracker.archiveorg.http.ArchiveOrgHttpClient
import lava.tracker.gutenberg.GutenbergDescriptor
import lava.tracker.registry.CLONE_BASE_URL_CONFIG_KEY
import lava.tracker.registry.DefaultTrackerRegistry
import lava.tracker.registry.TrackerClientFactory
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import javax.inject.Provider

/**
 * LVA-052 — real-stack verification (§6.G) that the HTTP-download capability is
 * USER-REACHABLE through the production SDK seam added by this increment:
 *
 *   LavaTrackerSdk.downloadHttpFile(trackerId, id)
 *     → real ArchiveOrgClient.getFeature(HttpDownloadableTracker)  [§6.E gate]
 *     → real ArchiveOrgDownload.downloadHttpFile(id)
 *     → real ArchiveOrgHttpClient over the (mock) network socket.
 *
 * The SUT is the SDK routing chain + the real production client + the real
 * download feature impl + the real OkHttp transport. The ONLY boundary faked is
 * the HTTP socket — replaced with a [MockWebServer] (Seventh Law clause 4:
 * faking a boundary BELOW the SUT, not the SUT itself). The mock host is wired
 * through the production [ArchiveOrgClientFactory]'s clone-override path
 * ([CLONE_BASE_URL_CONFIG_KEY]) — the exact seam `LavaTrackerSdk.clientFor`
 * uses for a cloned provider — and registered under the real `archiveorg`
 * descriptor so `LavaTrackerSdk.clientFor("archiveorg")` resolves it.
 *
 * Primary assertion is on user-visible state: the BYTES the SDK returns are
 * exactly the bytes the (mock) server served — i.e. the bytes that would be
 * written to the user's Downloads folder — plus the resolved source URL + the
 * suggested filename.
 *
 * ## Falsifiability rehearsal (§6.J clause 2 / §6.T.1 fail-first)
 * Performed before commit; recorded in the commit body Bluff-Audit stamp:
 *   Mutation A — LavaTrackerSdk.downloadHttpFile(trackerId,id) resolve
 *     `getFeature(HttpDownloadableTracker::class)` → hard-coded `null`. Test
 *     `downloadHttpFile returns the exact server bytes...` FAILS at
 *     `assertNotNull(result)` → "SDK HTTP-download seam returns null; not
 *     user-reachable".
 *   Mutation B — ArchiveOrgDownload.downloadHttpFile returns
 *     HttpDownloadResult(ByteArray(0), ...). Test FAILS at
 *     `assertArrayEquals(expectedBytes, result.bytes)` → empty artifact would
 *     reach the user's disk.
 */
class LavaTrackerSdkHttpDownloadRealStackTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
        unmockkAll()
    }

    /**
     * Registers a factory under the REAL [ArchiveOrgDescriptor] whose [create]
     * always returns the production [ArchiveOrgClient] built via the production
     * [ArchiveOrgClientFactory]'s clone-override path pointed at [baseUrl] (the
     * mock host). The SDK calls `clientFor("archiveorg")` with an empty config,
     * so we pre-build the clone-override client and hand the same instance back
     * for every config — the exact pattern the prior LVA-052 design used.
     */
    private fun sdkForArchiveOrg(baseUrl: String): LavaTrackerSdk {
        val http = ArchiveOrgHttpClient()
        val realFactory = ArchiveOrgClientFactory(
            // The singleton provider is the production default; it is never
            // taken here because we always create via the clone-override config.
            Provider { error("singleton path must not be taken in this test") },
            http,
        )
        val cloneConfig = MapPluginConfig(mapOf(CLONE_BASE_URL_CONFIG_KEY to baseUrl))
        val client = realFactory.create(cloneConfig) as ArchiveOrgClient
        val factory = object : TrackerClientFactory {
            override val descriptor: TrackerDescriptor = realFactory.descriptor
            override fun create(config: PluginConfig): TrackerClient = client
        }
        val registry = DefaultTrackerRegistry().apply { register(factory) }
        return LavaTrackerSdk(registry)
    }

    @Test
    fun `downloadHttpFile returns the exact server bytes for an archiveorg HTTP_DOWNLOAD provider`() = runBlocking {
        val expectedBytes = "EPUB-BYTES- -end".toByteArray()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(expectedBytes)),
        )
        val baseUrl = server.url("/").toString().trimEnd('/')
        val sdk = sdkForArchiveOrg(baseUrl)

        // archive.org id form: "identifier/filename".
        val result = sdk.downloadHttpFile("archiveorg", "moby-dick/mobydick.epub")

        // §6.J primary — user-visible state: the exact bytes that would land on disk.
        assertNotNull("SDK must resolve the HTTP_DOWNLOAD feature and return an artifact", result)
        assertArrayEquals(
            "downloaded bytes must equal the server-served bytes",
            expectedBytes,
            result!!.bytes,
        )
        assertEquals("filename must be derived from the resolved URL", "mobydick.epub", result.fileName)

        // The real download impl built the canonical /download/{id}/{file} URL
        // against the mock host — proves the production path was traversed.
        val recorded = server.takeRequest()
        assertEquals("/download/moby-dick/mobydick.epub", recorded.path)
        assertTrue(
            "sourceUrl must point at the resolved download URL",
            result.sourceUrl.endsWith("/download/moby-dick/mobydick.epub"),
        )
    }

    @Test
    fun `downloadHttpFile returns null for an unknown provider (no fabricated artifact)`() = runBlocking {
        val sdk = sdkForArchiveOrg(server.url("/").toString().trimEnd('/'))

        // An unknown tracker id must resolve to null, never throw, and never
        // fabricate bytes (§6.E capability honesty).
        val result = sdk.downloadHttpFile("does-not-exist", "anything/here")

        assertNull("unknown provider must yield null, not a fabricated artifact", result)
    }

    @Test
    fun `both archiveorg and gutenberg declare HTTP_DOWNLOAD so the seam is honest for both`() {
        assertTrue(
            "archiveorg must declare HTTP_DOWNLOAD for the SDK seam to resolve it",
            TrackerCapability.HTTP_DOWNLOAD in ArchiveOrgDescriptor.capabilities,
        )
        assertTrue(
            "gutenberg must declare HTTP_DOWNLOAD for the SDK seam to resolve it",
            TrackerCapability.HTTP_DOWNLOAD in GutenbergDescriptor.capabilities,
        )
    }
}

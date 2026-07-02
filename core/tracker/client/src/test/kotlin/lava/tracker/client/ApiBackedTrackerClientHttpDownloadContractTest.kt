package lava.tracker.client

import kotlinx.coroutines.test.runTest
import lava.tracker.api.RemoteTrackerDescriptor
import lava.tracker.api.feature.HttpDownloadableTracker
import okhttp3.OkHttpClient
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

/**
 * Real-stack contract test for the goapi-catalogue HTTP-DOWNLOAD path of
 * [ApiBackedTrackerClient] — the dynamic client the app builds for a provider
 * vended by `GET /v1/providers`.
 *
 * The production defect this guards: HTTP-file providers (Project Gutenberg,
 * Internet Archive) declare `HTTP_DOWNLOAD`, and their topic screen renders a
 * download button, but tapping it dead-ended because
 * `ApiBackedTrackerClient.getFeature(HttpDownloadableTracker::class)` returned
 * a hard-coded `null` ("does not yet surface these feature families"). So
 * `sdk.downloadHttpFile(id)` short-circuited to null and no DownloadDialog ever
 * appeared. On device this failed the download Challenge.
 *
 * Anti-Bluff posture (§6.J / Seventh Law clause 4):
 *  - The SUT ([ApiBackedTrackerClient]) is a REAL instance — never mocked.
 *  - The ONLY faked boundary is the network socket ([MockWebServer]).
 *  - Primary assertions are on the user-visible artifact: the exact BYTES that
 *    would be written to the user's Downloads folder + the filename the file is
 *    saved under — never "mock was called".
 *
 * FALSIFIABILITY / REPRODUCE-FIRST (Sixth Law clause 2 / §6.T.1) — actually run:
 *  Mutation: restore `HttpDownloadableTracker::class -> null` in
 *            [ApiBackedTrackerClient.getFeature] (the pre-fix line 268 state).
 *  Observed: [downloadHttpFile_returnsRealBytesAndFilename] RED at
 *            `assertNotNull(...)` — "an HTTP_DOWNLOAD descriptor MUST expose the
 *            HttpDownloadable feature ... expected not null" (getFeature null ⇒
 *            no dialog ⇒ dead-end download, exactly the device failure).
 *  Reverted: yes — the CapHTTPDownload-gated feature IS the fix under test.
 */
class ApiBackedTrackerClientHttpDownloadContractTest {

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

    /** A gutenberg-shaped descriptor: HTTP_DOWNLOAD capability, no-auth provider. */
    private fun gutenbergDescriptor() = RemoteTrackerDescriptor.from(
        trackerId = "gutenberg",
        displayName = "Project Gutenberg",
        capabilities = listOf("SEARCH", "BROWSE", "TOPIC", "HTTP_DOWNLOAD"),
        authType = "NONE",
        baseUrls = listOf("https://gutenberg.org"),
        encoding = "UTF-8",
        supportsAnonymous = true,
    )

    private fun client(descriptor: RemoteTrackerDescriptor = gutenbergDescriptor()) =
        ApiBackedTrackerClient(
            descriptor = descriptor,
            apiBaseUrl = server.url("/").toString().trimEnd('/'),
            httpClient = httpClient,
            authFieldName = "Lava-Auth",
            authKey = "k",
        )

    /**
     * The load-bearing test: an HTTP_DOWNLOAD provider MUST expose the feature,
     * and downloading MUST return the exact server bytes + the filename parsed
     * from Content-Disposition (so the saved file is named correctly).
     */
    @Test
    fun downloadHttpFile_returnsRealBytesAndFilename() = runTest {
        val epub = "PK-fake-epub-bytes-for-pg1342-".toByteArray()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/epub+zip")
                .setHeader("Content-Disposition", "attachment; filename=\"pg1342.epub3.images\"")
                .setBody(Buffer().write(epub)),
        )

        val feature = client().getFeature(HttpDownloadableTracker::class)
        assertNotNull(
            "an HTTP_DOWNLOAD descriptor MUST expose the HttpDownloadable feature (Capability Honesty §6.E)",
            feature,
        )

        val result = feature!!.downloadHttpFile("1342")

        // PRIMARY (§6.J clause 3) — the user-visible artifact.
        assertArrayEquals(
            "downloaded bytes MUST equal the server-served epub bytes",
            epub,
            result.bytes,
        )
        assertEquals(
            "fileName MUST come from the Content-Disposition header",
            "pg1342.epub3.images",
            result.fileName,
        )
        assertTrue(
            "sourceUrl MUST point at the /v1/{id}/http-download/{id} route — was ${result.sourceUrl}",
            result.sourceUrl.endsWith("/v1/gutenberg/http-download/1342"),
        )

        // Proves the client hit the real HTTP_DOWNLOAD contract route (not a mock).
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertTrue(
            "download MUST GET /v1/gutenberg/http-download/1342 — was ${recorded.path}",
            recorded.path?.endsWith("/v1/gutenberg/http-download/1342") == true,
        )
        assertEquals("k", recorded.getHeader("Lava-Auth"))
    }

    /**
     * Fallback: when the server omits Content-Disposition, the filename is
     * derived from the URL's last path segment (never blank), so the user still
     * gets a sensibly-named file.
     */
    @Test
    fun downloadHttpFile_fallsBackToUrlSegmentWhenNoContentDisposition() = runTest {
        val bytes = "raw-media-bytes".toByteArray()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(bytes)),
        )

        val result = client().getFeature(HttpDownloadableTracker::class)!!
            .downloadHttpFile("1342")

        assertArrayEquals(bytes, result.bytes)
        assertEquals(
            "with no Content-Disposition, fileName falls back to the URL last segment",
            "1342",
            result.fileName,
        )
    }

    /**
     * Discrimination / non-vacuity guard (§6.AB clause 3): a provider that does
     * NOT declare HTTP_DOWNLOAD MUST NOT expose the feature — proving the
     * positive test resolves the feature because the capability is honored, not
     * because getFeature blindly returns non-null.
     */
    @Test
    fun noHttpDownloadCapability_doesNotExposeFeature() {
        val torrentOnly = RemoteTrackerDescriptor.from(
            trackerId = "rutracker",
            displayName = "RuTracker.org",
            capabilities = listOf("SEARCH", "TORRENT_DOWNLOAD"),
            authType = "CAPTCHA_LOGIN",
            baseUrls = listOf("https://rutracker.org"),
            encoding = "windows-1251",
            supportsAnonymous = false,
        )
        assertNull(
            "a provider without HTTP_DOWNLOAD MUST NOT expose the HttpDownloadable feature",
            client(torrentOnly).getFeature(HttpDownloadableTracker::class),
        )
    }
}

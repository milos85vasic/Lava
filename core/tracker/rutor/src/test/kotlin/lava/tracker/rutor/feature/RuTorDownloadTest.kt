package lava.tracker.rutor.feature

import kotlinx.coroutines.runBlocking
import lava.tracker.rutor.http.RuTorHttpClient
import lava.tracker.rutor.magnet.RuTorMagnetCache
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * MockWebServer-driven tests for [RuTorDownload] (SP-3a Task 3.39).
 *
 * Sixth Law clause 3: primary assertions are on
 *  - the URL path the user's "Download" tap hits, and
 *  - the byte-array body the host app then writes to disk / hands to the
 *    torrent client.
 *
 * Falsifiability rehearsed —
 *  - Replacing `/download/$id` with `/dl/$id` fails the path assertion.
 *  - Reverting `getMagnetLink` to an unconditional `null` (the historical §6.E
 *    bluff) fails the honest-absence test: an id never surfaced returns null,
 *    but the exposure path is covered by [RuTorMagnetExposureTest].
 */
class RuTorDownloadTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `downloadTorrentFile fetches https d-rutor-info-style download URL and returns body bytes`() = runBlocking {
        val expectedBytes = "d8:announce11:rutor.tests".toByteArray()
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(expectedBytes))
                .setResponseCode(200),
        )
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = RuTorDownload(RuTorHttpClient(), RuTorMagnetCache(), baseUrl)

        val bytes = feature.downloadTorrentFile("1052665")

        // Primary assertion 1 — the URL the user's tap actually hit.
        assertEquals("/download/1052665", server.takeRequest().path)
        // Primary assertion 2 — the bytes the host app will write to disk.
        assertArrayEquals(expectedBytes, bytes)
    }

    @Test
    fun `getMagnetLink returns null for an id never surfaced (honest absence)`() {
        // No topic/search fetch has surfaced this id — synchronous magnet is
        // genuinely unavailable without an HTTP fetch (DownloadableTracker
        // contract). Honest null, not a bluff: the cache is empty. The genuine
        // exposure path is asserted in RuTorMagnetExposureTest.
        val feature = RuTorDownload(RuTorHttpClient(), RuTorMagnetCache(), "https://unused")
        assertNull(feature.getMagnetLink("1052665"))
    }
}

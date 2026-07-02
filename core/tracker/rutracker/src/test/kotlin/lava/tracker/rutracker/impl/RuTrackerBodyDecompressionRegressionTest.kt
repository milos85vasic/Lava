package lava.tracker.rutracker.impl

import kotlinx.coroutines.runBlocking
import lava.tracker.rutracker.RuTrackerHttpClientFactory
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Regression test for the 2026-07-02 rutracker-brotli-undecoded-body defect
 * (.lava-ci-evidence/sixth-law-incidents/2026-07-02-rutracker-brotli-undecoded-body.json).
 *
 * DEFECT: both rutracker HttpClient paths set a MANUAL
 * `Accept-Encoding: gzip, deflate, br` default header. The Ktor OkHttp engine
 * only transparently decompresses an encoding it negotiated itself, so a manual
 * Accept-Encoding makes `bodyAsText()` return the RAW compressed bytes. rutracker
 * serves brotli, so every HTML-body parse (mainPage/search/browse/topic) saw
 * garbage and failed — while login (Set-Cookie header only) appeared to succeed.
 * The whole rutracker provider was unusable for real users.
 *
 * §6.J primary assertion: on USER-VISIBLE decoded content. The server returns a
 * gzip-compressed logged-in index page; the REAL production client
 * ([RuTrackerHttpClientFactory] + [RuTrackerInnerApiImpl]) MUST hand back the
 * DECODED HTML (containing the logged-in marker), not raw gzip bytes.
 *
 * FALSIFIABILITY REHEARSAL (§6.J clause 2 / §6.N):
 *   Mutation: re-add `header("Accept-Encoding", "gzip, deflate, br")` to
 *             RuTrackerHttpClientFactory.create()'s defaultRequest.
 *   Observed-Failure: the primary assertion fails — mainPage() returns raw gzip
 *             bytes; the decoded logged-in marker is absent.
 *   Reverted: yes.
 */
class RuTrackerBodyDecompressionRegressionTest {

    private lateinit var server: MockWebServer

    // A representative logged-in rutracker index page. The real markup uses
    // profile.php?mode=viewprofile&u=<id>, matched by GetCurrentProfileUseCase.
    private val loggedInHtml = """
        <!DOCTYPE html><html><head><title>RuTracker.org</title></head><body>
        <a id="logged-in-username" href="https://rutracker.org/forum/profile.php?mode=viewprofile&u=12345">tester</a>
        <a href="./login.php?logout=1">Vyhod</a>
        </body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun gzip(s: String): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(s.toByteArray(Charsets.UTF_8)) }
        return bos.toByteArray()
    }

    @Test
    fun `mainPage decompresses a gzip-encoded response body`() = runBlocking {
        val gz = gzip(loggedInHtml)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Encoding", "gzip")
                .addHeader("Content-Type", "text/html; charset=utf-8")
                .setBody(Buffer().write(gz)),
        )

        val client = RuTrackerHttpClientFactory.create(server.url("/forum/").toString())
        val api = RuTrackerInnerApiImpl(client)

        val body = api.mainPage("bb_session=test-session")

        // PRIMARY (user-visible): the parseable logged-in marker survives decode.
        // Raw undecoded gzip bytes would fail this — and every real Jsoup parse.
        assertTrue(
            "body must be decompressed to real HTML (contains #logged-in-username + the " +
                "mode=viewprofile&u= profile link). first 32 chars=[${body.take(32)}]",
            body.contains("logged-in-username") &&
                body.contains("profile.php?mode=viewprofile&u=12345"),
        )
        // Guard: real HTML, not raw compressed bytes (raw gzip starts with 0x1f8b).
        assertTrue(
            "body must be real HTML, not raw compressed bytes; first 16 chars=[${body.take(16)}]",
            body.contains("<html") || body.contains("<!DOCTYPE"),
        )
    }
}

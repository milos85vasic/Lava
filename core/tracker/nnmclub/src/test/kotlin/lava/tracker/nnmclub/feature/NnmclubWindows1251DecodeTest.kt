package lava.tracker.nnmclub.feature

import kotlinx.coroutines.runBlocking
import lava.tracker.api.model.SearchRequest
import lava.tracker.nnmclub.http.NnmclubHttpClient
import lava.tracker.nnmclub.http.NnmclubMagnetCache
import lava.tracker.nnmclub.parser.NnmclubSearchParser
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.nio.charset.Charset

/**
 * Reproduce-first regression test for the NNM-Club windows-1251 mojibake bug.
 *
 * nnmclub.to is a phpBB site whose HTML is encoded in **windows-1251**. Before the
 * fix, [NnmclubSearch] read the response via OkHttp's `ResponseBody.string()`, which
 * decodes with the charset from the HTTP `Content-Type` header and DEFAULTS TO UTF-8
 * when that header omits a charset (which the live site does — it declares the charset
 * only in a `<meta>` tag that OkHttp ignores). The result was Cyrillic mojibake in
 * every search-result title the user sees. The fix decodes the raw bytes with an
 * explicit windows-1251 charset via [NnmclubHttpClient.bodyString], mirroring the
 * sibling kinozal plugin.
 *
 * Primary assertion (§6.J clause 3): the user-visible parsed [SearchResult] title
 * equals the correct Cyrillic string byte-for-byte, NOT its mojibake form.
 *
 * FALSIFIABILITY REHEARSAL: reverting the decode in `NnmclubHttpClient.bodyString`
 * from `String(bytes, nnmCharset)` back to `String(bytes, Charsets.UTF_8)` (equivalently,
 * the pre-fix state where NnmclubSearch used `it.body?.string()`) makes the assertions
 * below fail with:
 *   `expected:<[Дюна: Часть вторая] (2024)> but was:<[����: ����� ������] (2024)>`
 * because the windows-1251 Cyrillic bytes (0xC0–0xFF) are invalid UTF-8, so the UTF-8
 * decoder replaces each with U+FFFD — the mojibake a real user would see in results.
 */
class NnmclubWindows1251DecodeTest {

    private lateinit var server: MockWebServer
    private val parser = NnmclubSearchParser()
    private val windows1251: Charset = Charset.forName("windows-1251")

    /** A realistic nnmclub search-results row carrying a Cyrillic title. */
    private val cyrillicTitle = "Дюна: Часть вторая (2024)"
    private val html =
        """
        <html><head></head><body>
          <table class="forumline">
            <tr><th>Название</th><th>S</th><th>L</th><th>x</th><th>Добавлено</th><th>Размер</th></tr>
            <tr>
              <td><a class="genmed" href="viewtopic.php?t=987654">$cyrillicTitle</a></td>
              <td class="seedmed">42</td>
              <td class="leechmed">7</td>
              <td>—</td>
              <td>2024-03-01</td>
              <td>12.5 GB</td>
            </tr>
          </table>
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

    private fun win1251Body(): Buffer = Buffer().write(html.toByteArray(windows1251))

    @Test
    fun `search decodes windows-1251 Cyrillic title when Content-Type omits charset`() = runBlocking {
        // The live nnmclub.to serves windows-1251 bytes with NO charset in the header.
        // This is the reproduce-first discriminator: OkHttp's default UTF-8 decode
        // yields mojibake; the fix's explicit windows-1251 decode yields the real title.
        server.enqueue(MockResponse().setBody(win1251Body()).setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = NnmclubSearch(NnmclubHttpClient(), parser, NnmclubMagnetCache(), baseUrl)

        val result = feature.search(SearchRequest(query = "дюна"), page = 0)

        assertEquals("expected exactly one parsed result row", 1, result.items.size)
        assertEquals(
            "search result title must be the real Cyrillic string, not windows-1251 mojibake",
            cyrillicTitle,
            result.items.first().title,
        )
    }

    @Test
    fun `search decodes windows-1251 Cyrillic title when Content-Type declares windows-1251`() = runBlocking {
        // Some mirrors DO advertise the charset in the header. The fix decodes the raw
        // bytes with windows-1251 unconditionally, so this case must also stay correct
        // (non-regression alongside the header-absent case above).
        server.enqueue(
            MockResponse()
                .setBody(win1251Body())
                .addHeader("Content-Type", "text/html; charset=windows-1251")
                .setResponseCode(200),
        )
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = NnmclubSearch(NnmclubHttpClient(), parser, NnmclubMagnetCache(), baseUrl)

        val result = feature.search(SearchRequest(query = "дюна"), page = 0)

        assertEquals("expected exactly one parsed result row", 1, result.items.size)
        assertEquals(
            "search result title must be the real Cyrillic string when charset is declared",
            cyrillicTitle,
            result.items.first().title,
        )
    }
}

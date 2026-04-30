package lava.tracker.rutor.feature

import kotlinx.coroutines.runBlocking
import lava.tracker.api.model.SearchRequest
import lava.tracker.rutor.http.RuTorHttpClient
import lava.tracker.rutor.parser.RuTorSearchParser
import lava.tracker.testing.LavaFixtureLoader
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * MockWebServer-driven tests for [RuTorSearch] (SP-3a Task 3.36).
 *
 * Sixth Law clause 1 (same surfaces): the test feeds the production
 * [RuTorHttpClient] + [RuTorSearchParser] stack — only the wire is faked.
 *
 * Sixth Law clause 3 (user-visible primary assertions): assertions run on
 *   - the actual URL path the server received (the URL a real user would
 *     hit when typing a query),
 *   - the rendered title text of the first result item.
 *
 * Falsifiability rehearsal (clause 6.6.2): rehearsed locally —
 *  - Replaced `/search/$page/0/000/0/...` with `/find/...` in [RuTorSearch] →
 *    the path-equality assertion fails with "expected /search/... but got /find/...".
 *  - Replaced `Charsets.UTF_8` URL encoding with `Charsets.ISO_8859_1` →
 *    Cyrillic-query test fails because the encoded query no longer matches
 *    the expected percent-escaped UTF-8 byte sequence.
 *  - Replaced `parser.parse(body)` with `SearchResult(emptyList(), 0, 0)` →
 *    the items.size assertion fails with `0 != >=10`.
 */
class RuTorSearchTest {

    private lateinit var server: MockWebServer
    private val loader = LavaFixtureLoader(tracker = "rutor")
    private val parser = RuTorSearchParser()

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `search hits the canonical rutor URL shape and surfaces parsed results`() = runBlocking {
        val html = loader.load("search", "search-normal-2026-04-30.html")
        server.enqueue(MockResponse().setBody(html).setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = RuTorSearch(RuTorHttpClient(), parser, baseUrl)

        val result = feature.search(SearchRequest(query = "ubuntu"), page = 1)

        // Primary assertion 1 — the URL the user's action constructed.
        val recordedPath = server.takeRequest().path
        assertEquals(
            "user-typed query 'ubuntu' on page 1 must hit canonical /search/<page>/0/000/0/<query>",
            "/search/1/0/000/0/ubuntu",
            recordedPath,
        )
        // Primary assertion 2 — the rendered list cell text the search ViewModel will show.
        assertTrue(
            "expected at least 10 result rows, got ${result.items.size}",
            result.items.size >= 10,
        )
        assertTrue(
            "first result title should mention Ubuntu — got '${result.items.first().title}'",
            result.items.first().title.contains("Ubuntu", ignoreCase = true),
        )
        assertEquals(1, result.currentPage)
    }

    @Test
    fun `cyrillic query is percent-encoded as UTF-8 with %20 for spaces`() = runBlocking {
        val html = loader.load("search", "search-cyrillic-2026-04-30.html")
        server.enqueue(MockResponse().setBody(html).setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = RuTorSearch(RuTorHttpClient(), parser, baseUrl)

        // "Кино новинки" — Cyrillic + space; rutor expects %20 path encoding.
        feature.search(SearchRequest(query = "Кино новинки"), page = 0)

        val recordedPath = server.takeRequest().path
        assertEquals(
            "Cyrillic query must be UTF-8 percent-encoded with %20 for spaces",
            "/search/0/0/000/0/" +
                "%D0%9A%D0%B8%D0%BD%D0%BE%20%D0%BD%D0%BE%D0%B2%D0%B8%D0%BD%D0%BA%D0%B8",
            recordedPath,
        )
    }

    @Test
    fun `empty result page returns an empty SearchResult, not an exception`() = runBlocking {
        val html = loader.load("search", "search-empty-2026-04-30.html")
        server.enqueue(MockResponse().setBody(html).setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = RuTorSearch(RuTorHttpClient(), parser, baseUrl)

        val result = feature.search(SearchRequest(query = "lkjasdlkfjasdlkfjasldkfj"), page = 0)

        assertEquals("empty page → empty items", 0, result.items.size)
    }
}

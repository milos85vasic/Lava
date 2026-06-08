package lava.tracker.gutenberg.feature

import kotlinx.coroutines.runBlocking
import lava.tracker.api.model.SearchRequest
import lava.tracker.gutenberg.http.GutenbergHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * URL-contract edge-case tests for [GutenbergSearch.buildSearchUrl].
 *
 * Drives the REAL feature + REAL [GutenbergHttpClient] over MockWebServer and
 * inspects the request Gutendex actually receives. The existing
 * [GutenbergSearchTest] only asserts the page=1 / non-blank-query path; it never
 * exercises the two conditional branches in `buildSearchUrl`:
 *   - `if (page > 0)` — page 0 MUST omit the `page` param (Gutendex is 1-based).
 *   - `if (query.isNotBlank())` — blank query MUST omit `search` (empty filters to nothing).
 * Only the network socket is faked.
 *
 * Bluff-Audit:
 *  - page 0 omits page: change `if (page > 0)` to `if (page >= 0)` → path has page=0.
 *  - blank query omits search: delete the `if (query.isNotBlank())` guard → path has search=.
 */
class GutenbergSearchUrlContractEdgeCaseTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun feature(): GutenbergSearch =
        GutenbergSearch(GutenbergHttpClient(), server.url("/").toString().trimEnd('/'))

    @Test
    fun `page 0 omits the page query parameter`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"count":0,"results":[]}""").setResponseCode(200))

        feature().search(SearchRequest(query = "tolstoy"), page = 0)

        assertEquals("/books?search=tolstoy", server.takeRequest().path)
    }

    @Test
    fun `blank query omits the search query parameter`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"count":0,"results":[]}""").setResponseCode(200))

        feature().search(SearchRequest(query = ""), page = 0)

        assertEquals("/books", server.takeRequest().path)
    }

    @Test
    fun `blank query with a page produces only the page parameter`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"count":0,"results":[]}""").setResponseCode(200))

        feature().search(SearchRequest(query = ""), page = 2)

        assertEquals("/books?page=2", server.takeRequest().path)
    }
}

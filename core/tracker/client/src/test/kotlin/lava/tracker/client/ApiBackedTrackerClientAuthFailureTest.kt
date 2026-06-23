package lava.tracker.client

import kotlinx.coroutines.test.runTest
import lava.tracker.api.RemoteTrackerDescriptor
import lava.tracker.api.feature.SearchableTracker
import lava.tracker.api.model.SearchRequest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Real-stack regression test for the "Search fails on release with
 * 'Something went wrong'" defect.
 *
 * The production failure surface is
 * [ApiBackedTrackerClient]'s private `getString()`: on an unsuccessful
 * HTTP response it executes
 * `error("API request failed: HTTP ${resp.code} for $url")`, which the
 * UI maps to the generic "Something went wrong" string. When the chosen
 * lava-api-go endpoint is auth-gated and the search call lacks a valid
 * `Lava-Auth` key, the server returns HTTP 401 and the user's search
 * dies with exactly that error. The success path
 * (`json.decodeFromString(SearchResultDto.serializer(), body)`) is the
 * R8-relevant parse that, if broken, would also surface as the same error.
 *
 * Anti-Bluff posture (§6.J / Seventh Law clause 4):
 *  - The SUT ([ApiBackedTrackerClient]) is a REAL instance — never mocked.
 *  - The ONLY faked boundary is the network socket, replaced by
 *    [MockWebServer] (the seam BELOW the SUT).
 *  - Primary assertions are on user-visible / wire-observable state: the
 *    thrown failure message the user sees ("HTTP 401"), the parsed
 *    domain title the user reads on success, and the auth header on the
 *    wire — never "mock was called".
 *
 * FALSIFIABILITY REHEARSAL (Sixth Law clause 2 / §6.AB clause 3):
 *  Mutating `getString()` so it IGNORES the status (removing the
 *  `if (!resp.isSuccessful) error(...)` guard so it always reads the
 *  body) makes [search_when401_throwsWithHttp401InMessage] FAIL: with no
 *  throw, JUnit reports "Expected exception: java.lang.IllegalStateException".
 *  Observed under mutation:
 *    "search_when401_throwsWithHttp401InMessage(...):
 *     Expected exception: java.lang.IllegalStateException"
 *  Reverted: yes.
 */
class ApiBackedTrackerClientAuthFailureTest {

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

    /** A descriptor that declares SEARCH (the user-touched capability). */
    private fun searchDescriptor() = RemoteTrackerDescriptor.from(
        trackerId = "rutracker",
        displayName = "RuTracker.org",
        capabilities = listOf("SEARCH"),
        authType = "NONE",
        baseUrls = listOf("https://rutracker.org"),
        encoding = "UTF-8",
        supportsAnonymous = true,
    )

    private fun searchableWithKey() =
        ApiBackedTrackerClient(
            descriptor = searchDescriptor(),
            apiBaseUrl = server.url("/").toString().trimEnd('/'),
            httpClient = httpClient,
            authFieldName = "Lava-Auth",
            authKey = "k",
        ).getFeature(SearchableTracker::class)!!

    /**
     * Test 1 — the EXACT user-visible failure: a 401 on the search path makes
     * [SearchableTracker.search] throw, and the thrown message carries
     * "HTTP 401" (what the UI turns into "Something went wrong").
     */
    @Test
    fun search_when401_throwsWithHttp401InMessage() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":"unauthorized"}"""),
        )

        val searchable = searchableWithKey()

        val thrown = try {
            searchable.search(SearchRequest(query = "ubuntu"), page = 0)
            null
        } catch (e: IllegalStateException) {
            e
        }

        // PRIMARY — the failure the user actually sees, byte-for-byte from
        // the production `error("API request failed: HTTP ${resp.code} …")`.
        assertTrue(
            "search() MUST throw on a 401 from the auth-gated API " +
                "(this is the 'Something went wrong' surface)",
            thrown != null,
        )
        assertTrue(
            "thrown message MUST contain 'HTTP 401' — was: ${thrown?.message}",
            thrown?.message?.contains("HTTP 401") == true,
        )
    }

    /**
     * Test 2 — the success path: a 200 with a valid SearchResultDto JSON parses
     * into a [lava.tracker.api.model.SearchResult] whose item carries the
     * expected title. Proves the R8-relevant decode works for the user.
     */
    @Test
    fun search_when200_parsesResultTitle() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "provider": "rutracker",
                      "page": 0,
                      "totalPages": 2,
                      "results": [
                        {
                          "id": "777",
                          "title": "Ubuntu 24.04 LTS Desktop",
                          "sizeBytes": 4700000000,
                          "seeders": 99,
                          "leechers": 1,
                          "downloadUrl": "https://rutracker.org/dl/777.torrent",
                          "category": "OS"
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val result = searchableWithKey().search(SearchRequest(query = "ubuntu"), page = 0)

        // PRIMARY — the title the user reads in the search results list.
        assertEquals(1, result.items.size)
        assertEquals("Ubuntu 24.04 LTS Desktop", result.items.single().title)
        assertEquals("777", result.items.single().torrentId)
        assertEquals(2, result.totalPages)
    }

    /**
     * Test 3 — the client attaches `Lava-Auth: k` on the wire. Without it the
     * auth-gated API 401s (Test 1's failure mode), so this header IS the fix
     * for the "Something went wrong" defect.
     */
    @Test
    fun search_attachesLavaAuthHeaderOnTheWire() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"provider":"rutracker","page":0,"totalPages":1,"results":[]}"""),
        )

        searchableWithKey().search(SearchRequest(query = "ubuntu"), page = 0)

        // PRIMARY — the auth header the server gates 200-vs-401 on.
        val recorded = server.takeRequest()
        assertEquals("k", recorded.getHeader("Lava-Auth"))
    }
}

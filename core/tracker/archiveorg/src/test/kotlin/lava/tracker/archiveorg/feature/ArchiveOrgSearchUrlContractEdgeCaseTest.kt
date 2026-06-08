package lava.tracker.archiveorg.feature

import kotlinx.coroutines.runBlocking
import lava.tracker.api.model.SearchRequest
import lava.tracker.archiveorg.http.ArchiveOrgHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * URL-contract edge-case test for [ArchiveOrgSearch].
 *
 * Drives the REAL feature + REAL [ArchiveOrgHttpClient] over MockWebServer and
 * inspects the request the upstream actually receives. The existing
 * [ArchiveOrgSearchTest] asserts parsed results + the page-mapping, but NONE
 * assert the `rows=50` query parameter — yet `SearchResponseDto.toDomain`
 * divides `numFound` by the literal 50 to compute `totalPages`. A drift to
 * `rows=100` (the classic page-size-vs-divisor pagination bug) would survive
 * every current test; this pins the two halves together at the URL boundary.
 * Only the network socket is faked.
 *
 * Bluff-Audit: change `buildSearchUrl`'s `rows=50` to `rows=100` → the recorded
 * request path contains `rows=100`, the assertion fails. Reverted.
 */
class ArchiveOrgSearchUrlContractEdgeCaseTest {

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
    fun `search requests exactly rows 50 matching the pagination divisor`() = runBlocking {
        val json = """{"response":{"numFound":0,"start":0,"docs":[]}}"""
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = ArchiveOrgSearch(ArchiveOrgHttpClient(), baseUrl)

        feature.search(SearchRequest(query = "ubuntu"), page = 0)

        val recordedPath = server.takeRequest().path
        assertTrue(
            "search must request rows=50 to match the ceil(numFound/50) page math; was: $recordedPath",
            recordedPath!!.contains("rows=50"),
        )
    }
}

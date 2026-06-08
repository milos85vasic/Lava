package lava.tracker.archiveorg.feature

import kotlinx.coroutines.runBlocking
import lava.tracker.api.model.SearchRequest
import lava.tracker.archiveorg.http.ArchiveOrgHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Edge-case tests for [ArchiveOrgSearch] JSON-API parsing.
 *
 * Drives the REAL feature + REAL [ArchiveOrgHttpClient] (the production `Json`
 * config with ignoreUnknownKeys + isLenient) over MockWebServer. Covers the
 * advancedsearch.php JSON shapes the upstream actually emits: extra/unknown
 * top-level and per-doc fields, missing optional doc fields, multi-doc payloads,
 * and the no-throw contract. Only the network socket is faked.
 */
class ArchiveOrgSearchEdgeCaseTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun feature(): ArchiveOrgSearch =
        ArchiveOrgSearch(ArchiveOrgHttpClient(), server.url("/").toString().trimEnd('/'))

    @Test
    fun `unknown top-level and per-doc fields are ignored`() = runBlocking {
        // archive.org adds fields over time; ignoreUnknownKeys must keep parsing.
        val json = """
            {
              "responseHeader": {"status": 0, "QTime": 5},
              "response": {
                "numFound": 1,
                "start": 0,
                "extraTopLevel": "ignored",
                "docs": [
                  {"identifier":"id-1","title":"Has Extras","creator":"Alice",
                   "publicdate":"2020-01-01T00:00:00Z","collection":["movies"],
                   "week":3,"item_size":2048,"mediatype":"movies"}
                ]
              }
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))

        val result = feature().search(SearchRequest(query = "x"), page = 0)

        assertEquals(1, result.items.size)
        val item = result.items.single()
        assertEquals("id-1", item.torrentId)
        assertEquals("Has Extras", item.title)
        assertEquals(2048L, item.sizeBytes)
        assertEquals("movies", item.category)
        assertEquals("Alice", item.metadata["creator"])
    }

    @Test
    fun `doc missing every optional field maps to nulls and empty metadata`() = runBlocking {
        val json = """{"response":{"numFound":1,"start":0,"docs":[{"identifier":"bare","title":"Bare"}]}}"""
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))

        val item = feature().search(SearchRequest(query = "x"), page = 0).items.single()

        assertEquals("bare", item.torrentId)
        assertNull(item.sizeBytes)
        assertNull(item.category)
        assertTrue(item.metadata.isEmpty())
    }

    @Test
    fun `multiple docs preserve order and per-doc field presence`() = runBlocking {
        val json = """
            {"response":{"numFound":2,"start":0,"docs":[
              {"identifier":"a","title":"A","creator":"Alpha"},
              {"identifier":"b","title":"B","item_size":99,"year":"1999"}
            ]}}
        """.trimIndent()
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))

        val items = feature().search(SearchRequest(query = "x"), page = 0).items

        assertEquals(2, items.size)
        assertEquals("a", items[0].torrentId)
        assertEquals("Alpha", items[0].metadata["creator"])
        assertNull(items[0].sizeBytes)
        assertEquals("b", items[1].torrentId)
        assertEquals(99L, items[1].sizeBytes)
        assertEquals("1999", items[1].metadata["year"])
        assertNull(items[1].metadata["creator"])
    }

    @Test
    fun `numFound drives total pages independent of returned doc count`() = runBlocking {
        // 130 found, ceil(130/50) = 3, even though only one doc is on this page.
        val json = """{"response":{"numFound":130,"start":0,"docs":[{"identifier":"a","title":"A"}]}}"""
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))

        val result = feature().search(SearchRequest(query = "x"), page = 0)

        assertEquals(3, result.totalPages)
    }

    @Test
    fun `lenient json tolerates trailing whitespace and unquoted-safe payload`() = runBlocking {
        // isLenient is configured; ensure a payload with surrounding whitespace parses.
        val json = "   {\"response\":{\"numFound\":0,\"start\":0,\"docs\":[]}}   \n"
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))

        val result = feature().search(SearchRequest(query = "x"), page = 0)

        assertTrue(result.items.isEmpty())
        assertEquals(1, result.totalPages)
    }
}

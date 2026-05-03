package lava.tracker.archiveorg.feature

import kotlinx.coroutines.runBlocking
import lava.tracker.archiveorg.http.ArchiveOrgHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ArchiveOrgBrowseTest {

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
    fun `browse hits collection filter URL`() = runBlocking {
        val json = """
            {"response":{"numFound":55,"start":0,"docs":[
                {"identifier":"audio-1","title":"Audio Item","mediatype":"audio"}
            ]}}
        """.trimIndent()
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = ArchiveOrgBrowse(ArchiveOrgHttpClient(), baseUrl)

        val result = feature.browse(category = "audio", page = 0)

        val recordedPath = server.takeRequest().path
        assertTrue(recordedPath!!.contains("collection:audio"))
        assertEquals(1, result.items.size)
        assertEquals("audio-1", result.items[0].torrentId)
        assertEquals("audio", result.items[0].category)
    }

    @Test
    fun `browse defaults to movies when category is null`() = runBlocking {
        val json = """{"response":{"numFound":0,"start":0,"docs":[]}}"""
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = ArchiveOrgBrowse(ArchiveOrgHttpClient(), baseUrl)

        feature.browse(category = null, page = 0)

        val recordedPath = server.takeRequest().path
        assertTrue(recordedPath!!.contains("collection:movies"))
    }

    @Test
    fun `getForumTree returns static collections`() = runBlocking {
        val feature = ArchiveOrgBrowse(ArchiveOrgHttpClient(), "http://localhost")
        val tree = feature.getForumTree()

        assertEquals(5, tree.rootCategories.size)
        assertEquals("movies", tree.rootCategories[0].id)
        assertEquals("audio", tree.rootCategories[1].id)
        assertEquals("texts", tree.rootCategories[2].id)
        assertEquals("software", tree.rootCategories[3].id)
        assertEquals("image", tree.rootCategories[4].id)
    }
}

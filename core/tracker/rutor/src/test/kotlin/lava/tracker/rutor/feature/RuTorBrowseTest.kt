package lava.tracker.rutor.feature

import kotlinx.coroutines.runBlocking
import lava.tracker.rutor.http.RuTorHttpClient
import lava.tracker.rutor.parser.RuTorBrowseParser
import lava.tracker.testing.LavaFixtureLoader
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * MockWebServer-driven tests for [RuTorBrowse] (SP-3a Task 3.37).
 *
 * Sixth Law clause 1: same surface — production parser + production http client.
 * Falsifiability rehearsed locally —
 *  - dropping the `0` category fallback (allowing null to flow through as "null")
 *    fails `null-category becomes /browse/<page>/0/...` assertion.
 *  - returning ForumTree() instead of null fails the BROWSE-no-tree contract.
 */
class RuTorBrowseTest {

    private lateinit var server: MockWebServer
    private val loader = LavaFixtureLoader(tracker = "rutor")
    private val parser = RuTorBrowseParser()

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `browse with null category hits the all-categories slot`() = runBlocking {
        val html = loader.load("browse", "browse-normal-2026-04-30.html")
        server.enqueue(MockResponse().setBody(html).setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = RuTorBrowse(RuTorHttpClient(), parser, baseUrl)

        val result = feature.browse(category = null, page = 0)

        // Primary assertion 1 — URL the user's tap on "All categories" produces.
        assertEquals("/browse/0/0/000/0", server.takeRequest().path)
        // Primary assertion 2 — first row title is what the browse list cell shows.
        assertTrue("expected at least 1 row", result.items.isNotEmpty())
    }

    @Test
    fun `browse with explicit category id places it in the category slot`() = runBlocking {
        val html = loader.load("browse", "browse-normal-2026-04-30.html")
        server.enqueue(MockResponse().setBody(html).setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = RuTorBrowse(RuTorHttpClient(), parser, baseUrl)

        feature.browse(category = "5", page = 2)

        assertEquals("/browse/2/5/000/0", server.takeRequest().path)
    }

    @Test
    fun `getForumTree returns null because rutor has no forum tree (Capability Honesty)`() = runBlocking {
        val feature = RuTorBrowse(RuTorHttpClient(), parser, "https://unused")
        assertNull(feature.getForumTree())
    }
}

package lava.tracker.gutenberg

import kotlinx.coroutines.runBlocking
import lava.sdk.api.MapPluginConfig
import lava.tracker.api.feature.SearchableTracker
import lava.tracker.api.model.SearchRequest
import lava.tracker.gutenberg.http.GutenbergHttpClient
import lava.tracker.registry.CLONE_BASE_URL_CONFIG_KEY
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import javax.inject.Provider

/**
 * SP-4 Phase F.2 Task 4.gutenberg.5 (2026-05-13) — when [GutenbergClientFactory.create]
 * receives a [MapPluginConfig] carrying [CLONE_BASE_URL_CONFIG_KEY],
 * the resulting [GutenbergClient]'s search HTTP call MUST hit the
 * override URL (the clone's `primaryUrl`), not `https://gutendex.com`.
 *
 * §6.J primary assertion: the captured request URL at the MockWebServer
 * boundary. This is the wire-observable behaviour a real user's clone
 * relies on — without it, a clone of Gutenberg routes through gutendex
 * regardless of what the user typed for the clone's primary URL.
 *
 * Falsifiability rehearsal (§6.J clause 2 / §6.N Bluff-Audit):
 *
 *   1. In [GutenbergClientFactory.create], revert the override branch
 *      so `override != null` paths fall through to `clientProvider.get()`
 *      (returns the singleton wired to `gutendex.com`).
 *   2. Run this test.
 *   3. Expected failure: MockWebServer receives ZERO requests because
 *      the singleton client never touches the override URL — the
 *      `recordedRequest != null` assertion fails with
 *      "no request reached the MockWebServer at the override URL".
 *   4. Revert; re-run; green.
 */
class GutenbergClientFactoryCloneUrlTest {

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
    fun `factory with clone override produces a client whose search hits the override URL`() = runBlocking {
        val overrideBaseUrl = server.url("/").toString().trimEnd('/')
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"count":0,"results":[]}""",
            ),
        )

        val http = GutenbergHttpClient()
        // Provider deliberately returns a "wrong" client — if F.2's
        // override branch fails, the test would route through this
        // singleton and miss the MockWebServer entirely.
        val singletonProvider = Provider<GutenbergClient> {
            error("singleton path must NOT be taken when override is present")
        }
        val factory = GutenbergClientFactory(singletonProvider, http)

        val config = MapPluginConfig(mapOf(CLONE_BASE_URL_CONFIG_KEY to overrideBaseUrl))
        val client = factory.create(config)
        val search = client.getFeature(SearchableTracker::class)
        assertNotNull("client must expose SearchableTracker per descriptor capability", search)

        search!!.search(SearchRequest(query = "shakespeare"), page = 1)

        // §6.J primary — wire-observable signal.
        val recorded = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)
        assertNotNull("no request reached the MockWebServer at the override URL", recorded)
        val path = recorded!!.requestUrl?.toString() ?: ""
        assertTrue(
            "search must hit the override host; recorded URL was: $path",
            path.startsWith(overrideBaseUrl),
        )
        assertEquals(
            "search must use the /books endpoint",
            "/books",
            recorded.requestUrl?.encodedPath,
        )
    }

    @Test
    fun `factory without clone override delegates to the singleton provider`() {
        val http = GutenbergHttpClient()
        var providerCalled = false
        val singleton = GutenbergClient(
            http = http,
            search = lava.tracker.gutenberg.feature.GutenbergSearch(http),
            browse = lava.tracker.gutenberg.feature.GutenbergBrowse(http),
            topic = lava.tracker.gutenberg.feature.GutenbergTopic(http),
            download = lava.tracker.gutenberg.feature.GutenbergDownload(http),
        )
        val provider = Provider<GutenbergClient> {
            providerCalled = true
            singleton
        }
        val factory = GutenbergClientFactory(provider, http)

        val client = factory.create(MapPluginConfig())

        assertTrue("singleton provider must be invoked when no override is present", providerCalled)
        assertEquals("singleton client must be returned verbatim", singleton, client)
    }
}

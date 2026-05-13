package lava.tracker.rutracker

import kotlinx.coroutines.runBlocking
import lava.auth.api.TokenProvider
import lava.sdk.api.MapPluginConfig
import lava.tracker.api.feature.SearchableTracker
import lava.tracker.api.model.SearchRequest
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
 * SP-4 Phase F.2.6 (2026-05-13) — when [RuTrackerClientFactory.create]
 * receives a [MapPluginConfig] carrying [CLONE_BASE_URL_CONFIG_KEY],
 * the resulting [RuTrackerClient]'s search HTTP call MUST hit the
 * override host (the clone's `primaryUrl`), not `https://rutracker.org`.
 *
 * §6.J primary: the captured request URL at the MockWebServer
 * boundary. This is the wire-observable behaviour a real user's
 * RuTracker clone (e.g. `https://rutracker.eu`) relies on — without
 * F.2.6, every clone-targeted HTTP call routes through the source
 * URL and the clone's `primaryUrl` is effectively decorative.
 *
 * Falsifiability rehearsal (§6.J clause 2 / §6.N Bluff-Audit):
 *
 *   1. In [RuTrackerClientFactory.create], strip the override branch
 *      (always return `clientProvider.get()`). The singleton-error
 *      Provider in this test fires.
 *   2. Run this test.
 *   3. Expected failure: java.lang.IllegalStateException: singleton
 *      path must NOT be taken when override is present.
 *   4. Revert; re-run; green.
 */
class RuTrackerClientFactoryCloneUrlTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** Minimal TokenProvider stub — search doesn't validate the token shape locally. */
    private val stubTokenProvider = object : TokenProvider {
        override suspend fun getToken(): String = "stub-token"
        override suspend fun refreshToken(): Boolean = false
    }

    @Test
    fun `factory with clone override produces a client whose search hits the override URL`() = runBlocking {
        val overrideBaseUrl = server.url("/").toString().trimEnd('/')
        // RuTracker search hits `<base>/forum/tracker.php`. Enqueue ANY 200
        // response — the search use case will try to parse but the
        // user-observable assertion (URL captured) fires before parsing
        // is exercised in the success case, so even an empty body works.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """<html><body><table id="tor-tbl"></table></body></html>""",
            ),
        )

        val singletonProvider = Provider<RuTrackerClient> {
            error("singleton path must NOT be taken when override is present")
        }
        val factory = RuTrackerClientFactory(singletonProvider, stubTokenProvider)

        val config = MapPluginConfig(mapOf(CLONE_BASE_URL_CONFIG_KEY to overrideBaseUrl))
        val client = factory.create(config)
        val search = client.getFeature(SearchableTracker::class)
        assertNotNull("client must expose SearchableTracker per descriptor capability", search)

        // Run the search — body may fail to parse cleanly; we don't care.
        // The user-visible signal is the captured request URL at the
        // MockWebServer boundary.
        runCatching { search!!.search(SearchRequest(query = "matrix"), page = 0) }

        val recorded = server.takeRequest(3, java.util.concurrent.TimeUnit.SECONDS)
        assertNotNull("no request reached the MockWebServer at the override URL", recorded)
        val url = recorded!!.requestUrl?.toString() ?: ""
        assertTrue(
            "search must hit the override host; recorded URL was: $url",
            url.startsWith(overrideBaseUrl),
        )
        // RuTracker search hits /forum/tracker.php
        val path = recorded.requestUrl?.encodedPath ?: ""
        assertTrue(
            "search must use the /forum/tracker.php endpoint; recorded path was: $path",
            path.endsWith("/tracker.php"),
        )
    }

    @Test
    fun `factory without clone override delegates to the singleton provider`() {
        var providerCalled = false
        // Cannot construct a real RuTrackerClient here without a full Hilt
        // graph; the singleton path is asserted by signaling the provider
        // was reached. The clone-override test (above) is the user-visible
        // primary assertion; this is the §6.J secondary contract.
        val provider = Provider<RuTrackerClient> {
            providerCalled = true
            error("provider was called as expected; halt before constructing real client")
        }
        val factory = RuTrackerClientFactory(provider, stubTokenProvider)

        runCatching { factory.create(MapPluginConfig()) }

        assertEquals("singleton provider must be invoked when no override is present", true, providerCalled)
    }
}

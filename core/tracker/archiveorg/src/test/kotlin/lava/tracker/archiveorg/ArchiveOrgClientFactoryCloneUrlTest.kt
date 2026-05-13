package lava.tracker.archiveorg

import kotlinx.coroutines.runBlocking
import lava.sdk.api.MapPluginConfig
import lava.tracker.api.feature.SearchableTracker
import lava.tracker.api.model.SearchRequest
import lava.tracker.archiveorg.http.ArchiveOrgHttpClient
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
 * SP-4 Phase F.2 Task 4.archiveorg.5 (2026-05-13) — when
 * [ArchiveOrgClientFactory.create] receives a [MapPluginConfig]
 * carrying [CLONE_BASE_URL_CONFIG_KEY], the resulting
 * [ArchiveOrgClient]'s search HTTP call MUST hit the override URL
 * (the clone's `primaryUrl`), not `https://archive.org`.
 *
 * Falsifiability rehearsal (§6.J clause 2 / §6.N Bluff-Audit):
 *
 *   1. In [ArchiveOrgClientFactory.create], strip the override branch
 *      (always return `clientProvider.get()`).
 *   2. Run this test.
 *   3. Expected failure: the singleton-error path fires (
 *      `IllegalStateException: singleton path must NOT be taken`).
 *   4. Revert; re-run; green.
 */
class ArchiveOrgClientFactoryCloneUrlTest {

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
                """{"response":{"numFound":0,"start":0,"docs":[]}}""",
            ),
        )

        val http = ArchiveOrgHttpClient()
        val singletonProvider = Provider<ArchiveOrgClient> {
            error("singleton path must NOT be taken when override is present")
        }
        val factory = ArchiveOrgClientFactory(singletonProvider, http)

        val config = MapPluginConfig(mapOf(CLONE_BASE_URL_CONFIG_KEY to overrideBaseUrl))
        val client = factory.create(config)
        val search = client.getFeature(SearchableTracker::class)
        assertNotNull("client must expose SearchableTracker per descriptor capability", search)

        search!!.search(SearchRequest(query = "shakespeare"), page = 1)

        val recorded = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)
        assertNotNull("no request reached the MockWebServer at the override URL", recorded)
        val path = recorded!!.requestUrl?.toString() ?: ""
        assertTrue(
            "search must hit the override host; recorded URL was: $path",
            path.startsWith(overrideBaseUrl),
        )
        // Archive.org's search uses /advancedsearch.php
        assertEquals(
            "search must use the /advancedsearch.php endpoint",
            "/advancedsearch.php",
            recorded.requestUrl?.encodedPath,
        )
    }

    @Test
    fun `factory without clone override delegates to the singleton provider`() {
        val http = ArchiveOrgHttpClient()
        var providerCalled = false
        val singleton = ArchiveOrgClient(
            http = http,
            search = lava.tracker.archiveorg.feature.ArchiveOrgSearch(http),
            browse = lava.tracker.archiveorg.feature.ArchiveOrgBrowse(http),
            topic = lava.tracker.archiveorg.feature.ArchiveOrgTopic(http),
            download = lava.tracker.archiveorg.feature.ArchiveOrgDownload(http),
        )
        val provider = Provider<ArchiveOrgClient> {
            providerCalled = true
            singleton
        }
        val factory = ArchiveOrgClientFactory(provider, http)

        val client = factory.create(MapPluginConfig())

        assertTrue("singleton provider must be invoked when no override is present", providerCalled)
        assertEquals("singleton client must be returned verbatim", singleton, client)
    }
}

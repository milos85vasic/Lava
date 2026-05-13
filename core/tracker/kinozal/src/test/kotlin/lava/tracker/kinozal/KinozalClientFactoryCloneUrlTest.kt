package lava.tracker.kinozal

import kotlinx.coroutines.runBlocking
import lava.sdk.api.MapPluginConfig
import lava.tracker.api.feature.SearchableTracker
import lava.tracker.api.model.SearchRequest
import lava.tracker.kinozal.http.KinozalHttpClient
import lava.tracker.kinozal.parser.KinozalSearchParser
import lava.tracker.kinozal.parser.KinozalTopicParser
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
 * SP-4 Phase F.2 Task 4.kinozal.5 (2026-05-13). Mirrors the gutenberg
 * + archiveorg shape — only the URL endpoint differs.
 *
 * Falsifiability rehearsal (§6.J / §6.N): strip the override branch in
 * [KinozalClientFactory.create], run this test, expect the singleton-
 * error-Provider path to fire.
 */
class KinozalClientFactoryCloneUrlTest {

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
                """<html><body><table class="t_peer"></table></body></html>""",
            ),
        )

        val http = KinozalHttpClient()
        val searchParser = KinozalSearchParser()
        val topicParser = KinozalTopicParser()
        val singletonProvider = Provider<KinozalClient> {
            error("singleton path must NOT be taken when override is present")
        }
        val factory = KinozalClientFactory(singletonProvider, http, searchParser, topicParser)

        val config = MapPluginConfig(mapOf(CLONE_BASE_URL_CONFIG_KEY to overrideBaseUrl))
        val client = factory.create(config)
        val search = client.getFeature(SearchableTracker::class)
        assertNotNull("client must expose SearchableTracker per descriptor capability", search)

        runCatching { search!!.search(SearchRequest(query = "matrix"), page = 1) }

        val recorded = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)
        assertNotNull("no request reached the MockWebServer at the override URL", recorded)
        val path = recorded!!.requestUrl?.toString() ?: ""
        assertTrue(
            "search must hit the override host; recorded URL was: $path",
            path.startsWith(overrideBaseUrl),
        )
    }

    @Test
    fun `factory without clone override delegates to the singleton provider`() {
        val http = KinozalHttpClient()
        val searchParser = KinozalSearchParser()
        val topicParser = KinozalTopicParser()
        var providerCalled = false
        val singleton = KinozalClient(
            http = http,
            search = lava.tracker.kinozal.feature.KinozalSearch(http, searchParser),
            browse = lava.tracker.kinozal.feature.KinozalBrowse(http, searchParser),
            topic = lava.tracker.kinozal.feature.KinozalTopic(http, topicParser),
            auth = lava.tracker.kinozal.feature.KinozalAuth(http),
            download = lava.tracker.kinozal.feature.KinozalDownload(http),
        )
        val provider = Provider<KinozalClient> {
            providerCalled = true
            singleton
        }
        val factory = KinozalClientFactory(provider, http, searchParser, topicParser)

        val client = factory.create(MapPluginConfig())

        assertTrue("singleton provider must be invoked when no override is present", providerCalled)
        assertEquals("singleton client must be returned verbatim", singleton, client)
    }
}

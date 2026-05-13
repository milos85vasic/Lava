package lava.tracker.nnmclub

import kotlinx.coroutines.runBlocking
import lava.sdk.api.MapPluginConfig
import lava.tracker.api.feature.SearchableTracker
import lava.tracker.api.model.SearchRequest
import lava.tracker.nnmclub.http.NnmclubHttpClient
import lava.tracker.nnmclub.parser.NnmclubBrowseParser
import lava.tracker.nnmclub.parser.NnmclubLoginParser
import lava.tracker.nnmclub.parser.NnmclubSearchParser
import lava.tracker.nnmclub.parser.NnmclubTopicParser
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
 * SP-4 Phase F.2 Task 4.nnmclub.5 (2026-05-13). Same shape as gutenberg /
 * archiveorg / kinozal. Falsifiability rehearsal recorded in commit body.
 */
class NnmclubClientFactoryCloneUrlTest {

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
                """<html><body><table class="forumline"></table></body></html>""",
            ),
        )

        val http = NnmclubHttpClient()
        val searchParser = NnmclubSearchParser()
        val browseParser = NnmclubBrowseParser()
        val topicParser = NnmclubTopicParser()
        val loginParser = NnmclubLoginParser()
        val singletonProvider = Provider<NnmclubClient> {
            error("singleton path must NOT be taken when override is present")
        }
        val factory = NnmclubClientFactory(
            clientProvider = singletonProvider,
            http = http,
            searchParser = searchParser,
            browseParser = browseParser,
            topicParser = topicParser,
            loginParser = loginParser,
        )

        val config = MapPluginConfig(mapOf(CLONE_BASE_URL_CONFIG_KEY to overrideBaseUrl))
        val client = factory.create(config)
        val search = client.getFeature(SearchableTracker::class)
        assertNotNull("client must expose SearchableTracker per descriptor capability", search)

        runCatching { search!!.search(SearchRequest(query = "matrix"), page = 0) }

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
        val http = NnmclubHttpClient()
        val searchParser = NnmclubSearchParser()
        val browseParser = NnmclubBrowseParser()
        val topicParser = NnmclubTopicParser()
        val loginParser = NnmclubLoginParser()
        var providerCalled = false
        val singleton = NnmclubClient(
            http = http,
            search = lava.tracker.nnmclub.feature.NnmclubSearch(http, searchParser),
            browse = lava.tracker.nnmclub.feature.NnmclubBrowse(http, browseParser),
            topic = lava.tracker.nnmclub.feature.NnmclubTopic(http, topicParser),
            comments = lava.tracker.nnmclub.feature.NnmclubComments(http),
            auth = lava.tracker.nnmclub.feature.NnmclubAuth(http, loginParser),
            download = lava.tracker.nnmclub.feature.NnmclubDownload(http),
        )
        val provider = Provider<NnmclubClient> {
            providerCalled = true
            singleton
        }
        val factory = NnmclubClientFactory(
            clientProvider = provider,
            http = http,
            searchParser = searchParser,
            browseParser = browseParser,
            topicParser = topicParser,
            loginParser = loginParser,
        )

        val client = factory.create(MapPluginConfig())

        assertTrue("singleton provider must be invoked when no override is present", providerCalled)
        assertEquals("singleton client must be returned verbatim", singleton, client)
    }
}

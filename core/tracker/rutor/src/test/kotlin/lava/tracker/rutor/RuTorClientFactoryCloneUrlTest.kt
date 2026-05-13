package lava.tracker.rutor

import kotlinx.coroutines.runBlocking
import lava.sdk.api.MapPluginConfig
import lava.tracker.api.feature.SearchableTracker
import lava.tracker.api.model.SearchRequest
import lava.tracker.registry.CLONE_BASE_URL_CONFIG_KEY
import lava.tracker.rutor.http.RuTorHttpClient
import lava.tracker.rutor.parser.RuTorBrowseParser
import lava.tracker.rutor.parser.RuTorCommentsParser
import lava.tracker.rutor.parser.RuTorLoginParser
import lava.tracker.rutor.parser.RuTorSearchParser
import lava.tracker.rutor.parser.RuTorTopicParser
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
 * SP-4 Phase F.2 Task 4.rutor.5 (2026-05-13). Same shape as the other
 * five F.2 factory tests. Falsifiability rehearsal recorded in commit body.
 */
class RuTorClientFactoryCloneUrlTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun parsers(): RuTorParsers = RuTorParsers(
        search = RuTorSearchParser(),
        browse = RuTorBrowseParser(),
        topic = RuTorTopicParser(),
        comments = RuTorCommentsParser(),
        login = RuTorLoginParser(),
    )

    @Test
    fun `factory with clone override produces a client whose search hits the override URL`() = runBlocking {
        val overrideBaseUrl = server.url("/").toString().trimEnd('/')
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """<html><body><table id="index"></table></body></html>""",
            ),
        )

        val http = RuTorHttpClient()
        val p = parsers()
        val singletonProvider = Provider<RuTorClient> {
            error("singleton path must NOT be taken when override is present")
        }
        val factory = RuTorClientFactory(
            clientProvider = singletonProvider,
            http = http,
            searchParser = p.search,
            browseParser = p.browse,
            topicParser = p.topic,
            commentsParser = p.comments,
            loginParser = p.login,
        )

        val config = MapPluginConfig(mapOf(CLONE_BASE_URL_CONFIG_KEY to overrideBaseUrl))
        val client = factory.create(config)
        val search = client.getFeature(SearchableTracker::class)
        assertNotNull("client must expose SearchableTracker per descriptor capability", search)

        runCatching { search!!.search(SearchRequest(query = "matrix"), page = 0) }

        val recorded = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)
        assertNotNull("no request reached the MockWebServer at the override URL", recorded)
        val url = recorded!!.requestUrl?.toString() ?: ""
        assertTrue(
            "search must hit the override host; recorded URL was: $url",
            url.startsWith(overrideBaseUrl),
        )
    }

    @Test
    fun `factory without clone override delegates to the singleton provider`() {
        val http = RuTorHttpClient()
        val p = parsers()
        var providerCalled = false
        val singleton = RuTorClient(
            http = http,
            search = lava.tracker.rutor.feature.RuTorSearch(http, p.search),
            browse = lava.tracker.rutor.feature.RuTorBrowse(http, p.browse),
            topic = lava.tracker.rutor.feature.RuTorTopic(http, p.topic),
            comments = lava.tracker.rutor.feature.RuTorComments(http, p.comments),
            auth = lava.tracker.rutor.feature.RuTorAuth(http, p.login),
            download = lava.tracker.rutor.feature.RuTorDownload(http),
        )
        val provider = Provider<RuTorClient> {
            providerCalled = true
            singleton
        }
        val factory = RuTorClientFactory(
            clientProvider = provider,
            http = http,
            searchParser = p.search,
            browseParser = p.browse,
            topicParser = p.topic,
            commentsParser = p.comments,
            loginParser = p.login,
        )

        val client = factory.create(MapPluginConfig())

        assertTrue("singleton provider must be invoked when no override is present", providerCalled)
        assertEquals("singleton client must be returned verbatim", singleton, client)
    }

    private data class RuTorParsers(
        val search: RuTorSearchParser,
        val browse: RuTorBrowseParser,
        val topic: RuTorTopicParser,
        val comments: RuTorCommentsParser,
        val login: RuTorLoginParser,
    )
}

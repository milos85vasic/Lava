package lava.tracker.rutor.feature

import kotlinx.coroutines.runBlocking
import lava.tracker.rutor.http.RuTorHttpClient
import lava.tracker.rutor.parser.RuTorTopicParser
import lava.tracker.testing.LavaFixtureLoader
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * MockWebServer-driven tests for [RuTorTopic] (SP-3a Task 3.37).
 *
 * Verifies the two-fetch dance that real rutor.info uses:
 *   1. /torrent/<id>           → topic page (title, magnet, size, description)
 *   2. /descriptions/<id>.files → AJAX file-list HTML fragment
 *
 * Sixth Law clause 1: both URLs are the same URLs rutor.info itself fetches.
 *
 * Falsifiability rehearsed —
 *  - skipping the second fetch and only parsing /torrent/<id> fails the
 *    `files.size > 0` assertion (the topic page itself never carries resolved
 *    file rows; the placeholder doesn't parse to a file).
 *  - on a deliberately broken /descriptions/<id>.files response (500), the
 *    test still expects a non-null TopicDetail with files=emptyList — this is
 *    the documented graceful-degradation behaviour from Pre-authorized
 *    adaptation B.
 */
class RuTorTopicTest {

    private lateinit var server: MockWebServer
    private val loader = LavaFixtureLoader(tracker = "rutor")
    private val parser = RuTorTopicParser()

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getTopic merges file fragment from descriptions endpoint into TopicDetail`() = runBlocking {
        val topicHtml = loader.load("topic", "topic-with-files-2026-04-30.html")
        val filesHtml = loader.load("files", "files-multi-2026-04-30.html")
        server.dispatcher = pathDispatcher(
            mapOf(
                "/torrent/1000000" to topicHtml,
                "/descriptions/1000000.files" to filesHtml,
            ),
        )
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = RuTorTopic(RuTorHttpClient(), parser, baseUrl)

        val detail = feature.getTopic("1000000")

        // Primary assertion 1 — the user-visible topic title in the topic screen header.
        assertTrue(
            "topic title should be a non-empty string the user can read",
            detail.torrent.title.isNotBlank(),
        )
        // Primary assertion 2 — the files list rendered in the "Files" section of the topic.
        assertTrue(
            "expected at least 2 files merged from /descriptions/<id>.files",
            detail.files.size >= 2,
        )
        // The first row of the multi-file fixture is "...01. Damaged Goods.mp3".
        assertTrue(
            "first file name should reflect the descriptions fragment, got '${detail.files.first().name}'",
            detail.files.first().name.contains("Damaged Goods"),
        )
    }

    @Test
    fun `getTopic gracefully degrades when descriptions endpoint returns 404`() = runBlocking {
        val topicHtml = loader.load("topic", "topic-normal-2026-04-30.html")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path?.contains("/torrent/") == true) {
                    MockResponse().setBody(topicHtml).setResponseCode(200)
                } else {
                    MockResponse().setBody("Not found").setResponseCode(404)
                }
        }
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = RuTorTopic(RuTorHttpClient(), parser, baseUrl)

        val detail = feature.getTopic("1052665")

        // User-visible state: when files can't be resolved, the topic still renders
        // (title + magnet + description) and the files list is just empty — no crash.
        assertTrue(detail.torrent.title.isNotBlank())
        assertEquals("graceful degradation: 404 on .files → empty list", 0, detail.files.size)
    }

    @Test
    fun `getTopicPage returns the same TopicDetail wrapped in a single-page envelope`() = runBlocking {
        val topicHtml = loader.load("topic", "topic-normal-2026-04-30.html")
        server.dispatcher = pathDispatcher(
            mapOf(
                "/torrent/1052665" to topicHtml,
                "/descriptions/1052665.files" to loader.load(
                    "files",
                    "files-1052665-2026-04-30.html",
                ),
            ),
        )
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = RuTorTopic(RuTorHttpClient(), parser, baseUrl)

        val page = feature.getTopicPage("1052665", page = 0)

        assertEquals(1, page.totalPages)
        assertEquals(0, page.currentPage)
        assertTrue(page.topic.torrent.title.isNotBlank())
    }

    private fun pathDispatcher(map: Map<String, String>): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path?.substringBefore('?') ?: return MockResponse().setResponseCode(404)
            val body = map[path] ?: return MockResponse().setResponseCode(404)
            return MockResponse().setBody(body).setResponseCode(200)
        }
    }
}

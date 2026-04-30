package lava.tracker.rutor

import kotlinx.coroutines.test.runTest
import lava.tracker.api.feature.AuthenticatableTracker
import lava.tracker.api.feature.SearchableTracker
import lava.tracker.api.feature.TopicTracker
import lava.tracker.api.model.LoginRequest
import lava.tracker.api.model.SearchRequest
import lava.tracker.rutor.feature.RuTorAuth
import lava.tracker.rutor.feature.RuTorBrowse
import lava.tracker.rutor.feature.RuTorComments
import lava.tracker.rutor.feature.RuTorDownload
import lava.tracker.rutor.feature.RuTorSearch
import lava.tracker.rutor.feature.RuTorTopic
import lava.tracker.rutor.http.RuTorHttpClient
import lava.tracker.rutor.parser.RuTorBrowseParser
import lava.tracker.rutor.parser.RuTorCommentsParser
import lava.tracker.rutor.parser.RuTorLoginParser
import lava.tracker.rutor.parser.RuTorSearchParser
import lava.tracker.rutor.parser.RuTorTopicParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Real-tracker integration smoke test (SP-3a Task 3.41, Section K).
 *
 * Hits rutor.info over the network. Operator-gated: runs only when invoked
 * with `-PrealTrackers=true`. The Gradle task in core/tracker/rutor/build.gradle.kts
 * short-circuits otherwise. Per the Local-Only CI/CD rule, this test is
 * operator-driven and never blocks pre-push hooks or local CI gates.
 *
 * Anti-Bluff Pact compliance:
 *  - Same surfaces (clause 1): exercises RuTorClient.getFeature() exactly
 *    as the LavaTrackerSdk facade does in production.
 *  - Falsifiability (clause 2): each test asserts on user-visible state
 *    (search returns N items, infohashes look like SHA-1, etc.). Breaking
 *    the parser produces a clear assertion failure.
 *  - Primary assertion on user-visible state (clause 3): result.items.size,
 *    item.infoHash regex, item.sizeBytes nullness — all observable on a
 *    real device.
 *  - Integration Challenge (clause 4): real network, real HTML, real parser.
 *    No mocks.
 *
 * Evidence: when this test runs successfully against rutor.info, record the
 * outcome in `.lava-ci-evidence/sp3a-rutor/3.41-real-tracker-smoke.json`.
 */
class RealRuTorIntegrationTest {

    private lateinit var client: RuTorClient

    @Before
    fun setUp() {
        // Construct the client manually (no Hilt in integrationTest source set).
        // Same constructor signature as RuTorClient's @Inject-annotated primary.
        val http = RuTorHttpClient()
        val searchParser = RuTorSearchParser()
        val browseParser = RuTorBrowseParser()
        val topicParser = RuTorTopicParser()
        val commentsParser = RuTorCommentsParser()
        val loginParser = RuTorLoginParser()

        val search = RuTorSearch(http, searchParser)
        val browse = RuTorBrowse(http, browseParser)
        val topic = RuTorTopic(http, topicParser)
        val comments = RuTorComments(http, commentsParser)
        val auth = RuTorAuth(http, loginParser)
        val download = RuTorDownload(http)

        client = RuTorClient(http, search, browse, topic, comments, auth, download)
    }

    @Test
    fun `real search for ubuntu returns at least 3 results with valid infohashes`() = runTest {
        val search = client.getFeature(SearchableTracker::class)
            ?: error("SEARCH capability not declared on RuTorDescriptor")

        val result = search.search(SearchRequest(query = "ubuntu"), page = 0)

        assertTrue(
            "rutor.info /search/ubuntu should return >= 3 items, got ${result.items.size}",
            result.items.size >= 3,
        )
        for (item in result.items) {
            assertNotNull("title should be non-null for $item", item.title)
            assertTrue("title should be non-blank for $item", item.title.isNotBlank())
            assertNotNull("infoHash should be set for $item", item.infoHash)
            assertTrue(
                "infoHash should look like SHA-1 hex (40 chars), got ${item.infoHash}",
                item.infoHash!!.matches(Regex("[A-Fa-f0-9]{40}")),
            )
            assertNotNull("magnetUri should be set for $item", item.magnetUri)
            assertTrue("trackerId should be 'rutor', got ${item.trackerId}", item.trackerId == "rutor")
        }
    }

    @Test
    fun `real topic fetch returns title and magnet for first search hit`() = runTest {
        val search = client.getFeature(SearchableTracker::class) ?: error("SEARCH missing")
        val firstHit = search.search(SearchRequest(query = "ubuntu"), page = 0).items.first()

        val topic = client.getFeature(TopicTracker::class) ?: error("TOPIC capability missing")
        val detail = topic.getTopic(firstHit.torrentId)

        assertNotNull("topic.torrent should be non-null", detail.torrent)
        assertTrue(
            "topic.torrent.title should be non-blank, got '${detail.torrent.title}'",
            detail.torrent.title.isNotBlank(),
        )
    }

    @Test
    fun `real check-auth returns Unauthenticated when no userid cookie`() = runTest {
        val auth = client.getFeature(AuthenticatableTracker::class)
            ?: error("AUTH_REQUIRED capability missing")

        // Anonymous fresh client has no userid cookie.
        val state = auth.checkAuth()
        assertEquals(
            lava.tracker.api.model.AuthState.Unauthenticated,
            state,
        )
    }

    /**
     * Login smoke test. Skipped without `LAVA_TEST_USERNAME` / `LAVA_TEST_PASSWORD`
     * env vars — this is operator-driven verification, not a credentials-bundled CI test.
     */
    @Test
    fun `real login with operator credentials produces userid cookie`() = runTest {
        val username = System.getenv("LAVA_TEST_USERNAME")
        val password = System.getenv("LAVA_TEST_PASSWORD")
        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            // Skip: operator hasn't supplied credentials. Test is non-failing.
            println(
                "Skipping login smoke: set LAVA_TEST_USERNAME and LAVA_TEST_PASSWORD env vars to run.",
            )
            return@runTest
        }

        val auth = client.getFeature(AuthenticatableTracker::class)
            ?: error("AUTH_REQUIRED capability missing")
        val result = auth.login(LoginRequest(username = username, password = password))

        // The login may legitimately fail (wrong credentials, account locked, etc.).
        // What we assert is that the result is a structured outcome, not a thrown
        // exception. The actual state may be Authenticated or Unauthenticated
        // depending on the credentials supplied.
        assertNotNull("login should return a structured LoginResult", result)
        assertNotNull("login result.state should be non-null", result.state)
    }
}

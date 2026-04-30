package lava.tracker.rutor.feature

import kotlinx.coroutines.runBlocking
import lava.tracker.rutor.http.RuTorHttpClient
import lava.tracker.rutor.parser.RuTorCommentsParser
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * MockWebServer-driven tests for [RuTorComments] (SP-3a Task 3.37).
 *
 * Constraints (Pre-authorized adaptation A): rutor.info responds to anonymous
 * GETs of /comment/<id> with `302 Location: /users.php`. We verify both:
 *  - the URL the feature constructed (the same URL the user's "View comments"
 *    tap would trigger), and
 *  - that addComment is honest about anonymous failure (clause 6.E
 *    Capability Honesty: no fake-success bluff).
 *
 * Falsifiability rehearsed —
 *  - dropping the URL contract (e.g. `/comments/$id` instead of `/comment/$id`)
 *    fails the recorded-path assertion.
 *  - silently returning `false` from addComment instead of throwing fails the
 *    "should-have-thrown" assertion below.
 */
class RuTorCommentsTest {

    private lateinit var server: MockWebServer
    private val parser = RuTorCommentsParser()

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getComments fetches the canonical comment URL and returns parsed CommentsPage`() = runBlocking {
        // The login-form HTML is what rutor.info actually returns to anonymous
        // GETs of /comment/<id> after following the 302 to /users.php — the
        // parser is documented to return an empty CommentsPage in that case
        // (clause 6.E: no fabrication when no comments are visible).
        val loginFormHtml = """
            <html><head><title>rutor.info :: Авторизация</title></head>
            <body><form action="/login.php" method="post">
              <input name="nick"/><input name="password"/>
            </form></body></html>
        """.trimIndent()
        server.enqueue(MockResponse().setBody(loginFormHtml).setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = RuTorComments(RuTorHttpClient(), parser, baseUrl)

        val page = feature.getComments("1052665", page = 0)

        // Primary assertion 1 — the URL the user's "View comments" tap triggers.
        assertEquals("/comment/1052665", server.takeRequest().path)
        // Primary assertion 2 — the user-visible comment list is empty (there are
        // no comments; the page returned was the login form). Empty is honest.
        assertEquals(0, page.items.size)
    }

    @Test
    fun `getComments with page greater than zero appends ?page= query param`() = runBlocking {
        server.enqueue(MockResponse().setBody("<html></html>").setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = RuTorComments(RuTorHttpClient(), parser, baseUrl)

        feature.getComments("1052665", page = 3)

        assertEquals("/comment/1052665?page=3", server.takeRequest().path)
    }

    @Test
    fun `addComment throws because anonymous posting is not supported (Capability Honesty)`() = runBlocking {
        val feature = RuTorComments(RuTorHttpClient(), parser, "https://unused")

        try {
            feature.addComment("1052665", "would post if logged in")
            fail("expected IllegalStateException — anonymous posting must be honest, not bluff")
        } catch (e: IllegalStateException) {
            assertTrue(
                "exception message should reference the auth requirement; was: ${e.message}",
                (e.message ?: "").contains("authenticated", ignoreCase = true),
            )
        }
    }
}

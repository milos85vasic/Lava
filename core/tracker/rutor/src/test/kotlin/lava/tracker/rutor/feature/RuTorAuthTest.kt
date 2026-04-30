package lava.tracker.rutor.feature

import kotlinx.coroutines.runBlocking
import lava.tracker.api.model.AuthState
import lava.tracker.api.model.LoginRequest
import lava.tracker.rutor.http.RuTorHttpClient
import lava.tracker.rutor.parser.RuTorLoginParser
import lava.tracker.testing.LavaFixtureLoader
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * MockWebServer-driven tests for [RuTorAuth] (SP-3a Task 3.38).
 *
 * Sixth Law clause 1 (same surfaces): the test posts to the same /login.php
 * form rutor.info renders, with the same `nick` / `password` field names; the
 * production cookie jar inside [RuTorHttpClient] is the one that captures the
 * `userid` cookie under test.
 *
 * Sixth Law clause 3 (user-visible primary assertions): assertions are on
 *  - the recorded request body (the form fields the user "submitted"),
 *  - the AuthState returned (the user-visible "logged in" / "wrong password"
 *    outcome),
 *  - the sessionToken surfaced (the cookie value the host app caches).
 *
 * Falsifiability rehearsed —
 *  - changing the form field name from `nick` to `username` fails the
 *    request-body assertion.
 *  - skipping the cookie capture (`http.cookieValue(USERID_COOKIE)` → null)
 *    fails the sessionToken assertion.
 *  - returning AuthState.Authenticated regardless of parser fails the
 *    wrong-password test.
 */
class RuTorAuthTest {

    private lateinit var server: MockWebServer
    private val loader = LavaFixtureLoader(tracker = "rutor")
    private val parser = RuTorLoginParser()

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `successful login surfaces Authenticated and the userid cookie as sessionToken`() = runBlocking {
        val successHtml = loader.load("login", "success-target-home-2026-04-30.html")
        server.enqueue(
            MockResponse()
                .setBody(successHtml)
                .setResponseCode(200)
                .addHeader("Set-Cookie", "userid=42-deadbeef; Path=/; HttpOnly"),
        )
        val baseUrl = server.url("/").toString().trimEnd('/')
        val http = RuTorHttpClient()
        val feature = RuTorAuth(http, parser, baseUrl)

        val result = feature.login(LoginRequest(username = "milos", password = "secret"))

        // Primary assertion 1 — the form body the server actually saw on the wire.
        val recorded = server.takeRequest()
        assertEquals("/login.php", recorded.path)
        val bodyText = recorded.body.readUtf8()
        assertTrue("body should contain nick=milos, was: $bodyText", bodyText.contains("nick=milos"))
        assertTrue(
            "body should contain password=secret, was: $bodyText",
            bodyText.contains("password=secret"),
        )
        // Primary assertion 2 — the user-visible "you are logged in" signal.
        assertTrue("expected Authenticated, got ${result.state}", result.state is AuthState.Authenticated)
        // Primary assertion 3 — the cookie value the host app caches.
        assertEquals("42-deadbeef", result.sessionToken)
    }

    @Test
    fun `wrong-password login surfaces Unauthenticated and no sessionToken`() = runBlocking {
        val failureHtml = loader.load("login", "failure-wrong-password-2026-04-30.html")
        server.enqueue(MockResponse().setBody(failureHtml).setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = RuTorAuth(RuTorHttpClient(), parser, baseUrl)

        val result = feature.login(LoginRequest(username = "milos", password = "wrong"))

        assertTrue("expected Unauthenticated, got ${result.state}", result.state is AuthState.Unauthenticated)
        // No userid cookie was set, so sessionToken must be null.
        assertEquals(null, result.sessionToken)
    }

    @Test
    fun `checkAuth flips from Unauthenticated to Authenticated after a successful login round-trip`() = runBlocking {
        // Step 1: fresh client — no cookies yet → Unauthenticated.
        val http = RuTorHttpClient()
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = RuTorAuth(http, parser, baseUrl)
        assertTrue(
            "fresh client must report Unauthenticated",
            feature.checkAuth() is AuthState.Unauthenticated,
        )

        // Step 2: drive a successful login so the cookie jar captures userid.
        val successHtml = loader.load("login", "success-target-home-2026-04-30.html")
        server.enqueue(
            MockResponse()
                .setBody(successHtml)
                .setResponseCode(200)
                .addHeader("Set-Cookie", "userid=session-99; Path=/"),
        )
        feature.login(LoginRequest("milos", "password"))

        // Step 3: the user-visible "you are logged in" affordance — the same
        // call the host app makes on app start to decide whether to show the
        // login screen.
        assertTrue(
            "after login, checkAuth must reflect the userid cookie",
            feature.checkAuth() is AuthState.Authenticated,
        )
    }
}

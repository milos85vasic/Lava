package lava.tracker.nnmclub.feature

import kotlinx.coroutines.runBlocking
import lava.tracker.api.model.AuthState
import lava.tracker.api.model.LoginRequest
import lava.tracker.nnmclub.http.NnmclubHttpClient
import lava.tracker.nnmclub.parser.NnmclubLoginParser
import lava.tracker.testing.LavaFixtureLoader
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NnmclubAuthTest {

    private lateinit var server: MockWebServer
    private val loader = LavaFixtureLoader(tracker = "nnmclub")
    private val parser = NnmclubLoginParser()

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `successful login surfaces Authenticated and the phpbb cookie as sessionToken`() = runBlocking {
        val successHtml = loader.load("login", "login-success-2026-05-02.html")
        server.enqueue(
            MockResponse()
                .setBody(successHtml)
                .setResponseCode(200)
                .addHeader("Set-Cookie", "phpbb2mysql_4_data=session-abc; Path=/; HttpOnly"),
        )
        val baseUrl = server.url("/").toString().trimEnd('/')
        val http = NnmclubHttpClient()
        val feature = NnmclubAuth(http, parser, baseUrl)

        val result = feature.login(LoginRequest(username = "milos", password = "secret"))

        val recorded = server.takeRequest()
        assertEquals("/forum/login.php", recorded.path)
        val bodyText = recorded.body.readUtf8()
        assertTrue("body should contain username=milos", bodyText.contains("username=milos"))
        assertTrue("body should contain password=secret", bodyText.contains("password=secret"))
        assertTrue("expected Authenticated", result.state is AuthState.Authenticated)
        assertEquals("session-abc", result.sessionToken)
    }

    @Test
    fun `wrong-password login surfaces Unauthenticated and no sessionToken`() = runBlocking {
        val failureHtml = loader.load("login", "login-failure-2026-05-02.html")
        server.enqueue(MockResponse().setBody(failureHtml).setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = NnmclubAuth(NnmclubHttpClient(), parser, baseUrl)

        val result = feature.login(LoginRequest(username = "milos", password = "wrong"))

        assertTrue("expected Unauthenticated", result.state is AuthState.Unauthenticated)
        assertEquals(null, result.sessionToken)
    }

    @Test
    fun `checkAuth flips from Unauthenticated to Authenticated after login`() = runBlocking {
        val http = NnmclubHttpClient()
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = NnmclubAuth(http, parser, baseUrl)
        assertTrue(
            "fresh client must report Unauthenticated",
            feature.checkAuth() is AuthState.Unauthenticated,
        )

        val successHtml = loader.load("login", "login-success-2026-05-02.html")
        server.enqueue(
            MockResponse()
                .setBody(successHtml)
                .setResponseCode(200)
                .addHeader("Set-Cookie", "phpbb2mysql_4_data=session-99; Path=/"),
        )
        feature.login(LoginRequest("milos", "password"))

        assertTrue(
            "after login, checkAuth must reflect the cookie",
            feature.checkAuth() is AuthState.Authenticated,
        )
    }
}

package lava.tracker.kinozal.feature

import kotlinx.coroutines.runBlocking
import lava.tracker.api.model.LoginRequest
import lava.tracker.kinozal.http.KinozalHttpClient
import lava.tracker.testing.LavaFixtureLoader
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KinozalAuthTest {
    private lateinit var server: MockWebServer
    private val loader = LavaFixtureLoader(tracker = "kinozal")

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `successful login surfaces Authenticated and the uid cookie as sessionToken`() = runBlocking {
        val html = loader.load("login", "success-2026-05-02.html")
        server.enqueue(
            MockResponse()
                .setBody(html)
                .setResponseCode(200)
                .addHeader("Set-Cookie", "uid=12345; path=/")
                .addHeader("Set-Cookie", "pass=secret; path=/"),
        )
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = KinozalAuth(KinozalHttpClient(), baseUrl)

        val result = feature.login(LoginRequest(username = "user", password = "pass"))

        assertEquals(lava.tracker.api.model.AuthState.Authenticated, result.state)
        assertEquals("12345", result.sessionToken)
    }

    @Test
    fun `wrong-password login surfaces Unauthenticated and no sessionToken`() = runBlocking {
        val html = loader.load("login", "failure-2026-05-02.html")
        server.enqueue(MockResponse().setBody(html).setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = KinozalAuth(KinozalHttpClient(), baseUrl)

        val result = feature.login(LoginRequest(username = "user", password = "wrong"))

        assertEquals(lava.tracker.api.model.AuthState.Unauthenticated, result.state)
        assertTrue("sessionToken must be null", result.sessionToken == null)
    }
}

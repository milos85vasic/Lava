package lava.tracker.kinozal.http

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KinozalHttpClientTest {
    private lateinit var server: MockWebServer
    private val http = KinozalHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `get returns the response body produced by the server`() = runBlocking {
        server.enqueue(MockResponse().setBody("hello").setResponseCode(200))
        val url = server.url("/").toString()

        val response = http.get(url)
        val body = response.use { http.bodyString(it) }

        assertEquals("hello", body)
    }

    @Test
    fun `cookies set by the server are sent on the next request`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).addHeader("Set-Cookie", "uid=abc; Path=/"))
        server.enqueue(MockResponse().setResponseCode(200))

        val base = server.url("/").toString()
        http.get(base)
        http.get(base)

        server.takeRequest() // first request — no cookie
        val second = server.takeRequest()
        assertTrue("Cookie should be forwarded", second.getHeader("Cookie")?.contains("uid=abc") == true)
    }
}

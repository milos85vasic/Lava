package lava.domain.usecase

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class ProbeMirrorUseCaseTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `200 is reachable`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        val u = ProbeMirrorUseCase(client)
        assertEquals(ProbeResult.Reachable, u.invoke(server.url("/").toString()))
    }

    @Test
    fun `5xx is unhealthy`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        val u = ProbeMirrorUseCase(client)
        assertEquals(ProbeResult.Unhealthy(503), u.invoke(server.url("/").toString()))
    }

    @Test
    fun `unreachable returns Unreachable`() = runBlocking {
        val unreachable = "http://127.255.255.254:9"
        val u = ProbeMirrorUseCase(client)
        val r = u.invoke(unreachable)
        assertEquals(ProbeResult.Unreachable::class, r::class)
    }
}

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

    /**
     * §6.O regression for Crashlytics issue `39469d3bc00aabf76a86d5d15f2e7f2b`
     * (FATAL `okhttp3.HttpUrl$Builder.parse` — "Expected URL scheme 'http' or
     * 'https' but no scheme was found for djdnjd…", 1.2.21 / Galaxy S23 Ultra).
     *
     * A user typed a schemeless string ("djdnjd") in the AddMirror field; it
     * reached `ProbeMirrorUseCase.invoke`, where `Request.Builder.url(...)`
     * threw `IllegalArgumentException`. The prior catch caught only
     * `IOException`, so the throw escaped to the main looper as a FATAL crash.
     * The fix added the `catch (e: IllegalArgumentException)` branch that
     * converts the malformed URL into a graceful [ProbeResult.Unreachable].
     *
     * This test exercises the REAL production use-case with a REAL OkHttpClient
     * (no SUT mock) and asserts the user-visible result: a schemeless URL must
     * yield Unreachable, NEVER throw. The closure log admitted this regression
     * test was "owed in a follow-up commit" — this is that follow-up.
     *
     * FALSIFIABILITY REHEARSAL (Sixth Law clause 2):
     *   Mutation: remove the `catch (e: IllegalArgumentException)` branch from
     *             ProbeMirrorUseCase (leaving only `catch (e: IOException)`).
     *   Observed-Failure: this test fails — the IllegalArgumentException
     *             escapes `invoke` and `runBlocking` rethrows it, so the test
     *             errors with `java.lang.IllegalArgumentException: Expected URL
     *             scheme "http" or "https" but no scheme was found for djdnjd...`
     *             instead of returning Unreachable.
     *   Reverted: yes.
     */
    @Test
    fun `schemeless URL returns Unreachable instead of crashing (Crashlytics 39469d3b)`() = runBlocking {
        val schemeless = "djdnjd"
        val u = ProbeMirrorUseCase(client)
        val r = u.invoke(schemeless)
        assertEquals(
            "A schemeless user-typed URL must yield ProbeResult.Unreachable, " +
                "never an uncaught IllegalArgumentException (FATAL crash 39469d3b).",
            ProbeResult.Unreachable::class,
            r::class,
        )
    }
}

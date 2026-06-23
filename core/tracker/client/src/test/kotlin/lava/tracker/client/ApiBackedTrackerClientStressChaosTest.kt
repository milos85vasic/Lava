package lava.tracker.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import lava.tracker.api.RemoteTrackerDescriptor
import lava.tracker.api.feature.SearchableTracker
import lava.tracker.api.model.SearchRequest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * §11.4.85 STRESS + CHAOS coverage for [ApiBackedTrackerClient].
 *
 * The existing [ApiBackedTrackerClientTest] covers the happy single-shot path
 * (one search, one download, one login) and the auth-gate 401 discriminator.
 * It does NOT cover:
 *   - SUSTAINED load (N≥100 sequential searches; latency distribution).
 *   - CONCURRENT load (N≥10 simultaneous searches sharing ONE OkHttpClient +
 *     ONE MockWebServer): a real user with multiple providers selected fans out
 *     parallel searches via the SDK; a thread-safety bug in the client would
 *     corrupt or cross-wire results.
 *   - The CHAOS error paths in [getString]: a flaky lava-api-go returning HTTP
 *     5xx mid-stream, dropping the connection mid-body, or returning an empty
 *     body. The production code maps these to `error("API request failed …")` /
 *     `error("…empty body…")`; if those branches threw an UNcategorised crash
 *     (or swallowed the failure into a fake-success), a real user would see a
 *     wrong result instead of the categorised "Something went wrong".
 *
 * Anti-Bluff posture (§6.J / Seventh Law clause 4):
 *  - The SUT ([ApiBackedTrackerClient]) is a REAL instance — never mocked.
 *  - The ONLY faked boundary is the network socket ([MockWebServer]).
 *  - Primary assertions are on user-observable outcomes: parsed result content
 *    per request, exact thrown-failure category on a broken server, captured
 *    latency on the wire. "Mock was called" is never the primary signal.
 *
 * FALSIFIABILITY REHEARSAL (Sixth Law clause 2 / §6.AB clause 3) — recorded in
 * the commit Bluff-Audit stamp:
 *  - Mutation A: delete the `if (!resp.isSuccessful) error(...)` guard in
 *    [ApiBackedTrackerClient.getString] (return `resp.body?.string() ?: ""`).
 *    Then a 503 body is decoded as a search result → [chaos_http5xx_*] FAILS
 *    (it expects an IllegalStateException carrying the HTTP code; instead either
 *    a SerializationException or a silent empty result surfaces).
 *  - Mutation B: make the concurrent dispatch ignore the query (return the same
 *    fixed body for every request) → [stress_concurrentSearches_*] FAILS its
 *    per-request title assertion ("each concurrent search returns ITS query").
 */
class ApiBackedTrackerClientStressChaosTest {

    private lateinit var server: MockWebServer

    // ONE shared client across all calls in a test — mirrors production where the
    // OkHttpClient is a DI singleton (see ApiBackedTrackerClient KDoc). Generous
    // timeouts so the connection-reset chaos surfaces as a *read* failure, not a
    // spurious timeout that would mask the SocketPolicy under test.
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun descriptor() = RemoteTrackerDescriptor.from(
        trackerId = "rutracker",
        displayName = "RuTracker.org",
        capabilities = listOf("SEARCH", "TORRENT_DOWNLOAD"),
        authType = "NONE",
        baseUrls = listOf("https://rutracker.org"),
        encoding = "UTF-8",
        supportsAnonymous = true,
    )

    private fun searchable(): SearchableTracker =
        ApiBackedTrackerClient(
            descriptor = descriptor(),
            apiBaseUrl = server.url("/").toString().trimEnd('/'),
            httpClient = httpClient,
            authFieldName = "Lava-Auth",
        ).getFeature(SearchableTracker::class)!!

    /** Wire body whose single result's title echoes [marker], so per-call coherence is checkable. */
    private fun bodyFor(marker: String): String =
        """
        {"provider":"rutracker","page":0,"totalPages":1,
         "results":[{"id":"$marker","title":"result-for-$marker","sizeBytes":1,
                     "seeders":1,"leechers":0,"magnetLink":"magnet:?xt=urn:btih:$marker",
                     "downloadUrl":"https://x/$marker.torrent","infoHash":"$marker","category":"X"}]}
        """.trimIndent()

    // ---- STRESS: sustained sequential load + latency distribution ------------

    @Test
    fun stress_sequentialSearches_allSucceed_withCapturedLatency() = runBlocking {
        val n = 120
        // Dispatcher keyed on the request's `query` param: every call gets a body
        // whose title echoes ITS query, so a cross-wired result is detectable.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val q = request.requestUrl?.queryParameter("query") ?: "none"
                return MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(bodyFor(q))
            }
        }

        val sut = searchable()
        val latenciesMs = LongArray(n)
        for (i in 0 until n) {
            val q = "q$i"
            val t0 = System.nanoTime()
            val result = sut.search(SearchRequest(query = q), page = 0)
            latenciesMs[i] = (System.nanoTime() - t0) / 1_000_000
            // PRIMARY — the user got the result for THIS query, not a stale/other one.
            assertEquals("call #$i must return its own query's result", 1, result.items.size)
            assertEquals("result-for-$q", result.items.single().title)
        }

        latenciesMs.sort()
        val p50 = latenciesMs[(n * 50) / 100]
        val p95 = latenciesMs[(n * 95) / 100]
        // Captured-evidence: latency distribution printed for the §11.4.85 record.
        println("[STRESS-SEQ] n=$n p50=${p50}ms p95=${p95}ms max=${latenciesMs[n - 1]}ms")
        // No deadlock / runaway: 120 in-process MockWebServer round-trips finishing
        // under 5s at p95 is a generous, machine-independent sanity bound.
        assertTrue("p95 latency ${p95}ms unexpectedly high — possible connection leak/deadlock", p95 < 5_000)
    }

    @Test
    fun stress_concurrentSearches_eachGetsItsOwnResult_noCrossWiring() = runBlocking {
        val n = 16 // ≥10 required; 16 exercises the OkHttp connection pool under contention
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val q = request.requestUrl?.queryParameter("query") ?: "none"
                return MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(bodyFor(q))
            }
        }

        val sut = searchable()
        val observed = ConcurrentHashMap<String, String>()

        // Fan out N concurrent searches on Dispatchers.IO (real blocking HTTP on
        // worker threads), each with a distinct query. A thread-safety defect in
        // the client (shared mutable request state, etc.) would cross-wire titles.
        withContext(Dispatchers.IO) {
            (0 until n).map { i ->
                async {
                    val q = "c$i"
                    val r = sut.search(SearchRequest(query = q), page = 0)
                    observed[q] = r.items.single().title
                }
            }.awaitAll()
        }

        // PRIMARY — every concurrent caller received ITS OWN query's result.
        assertEquals("all $n concurrent searches must complete", n, observed.size)
        for (i in 0 until n) {
            assertEquals("concurrent search c$i cross-wired", "result-for-c$i", observed["c$i"])
        }
    }

    // ---- CHAOS: the getString HTTP-error / dropped-connection / empty paths --

    @Test
    fun chaos_http5xx_mapsToCategorisedIllegalStateException_notRawCrash() = runBlocking {
        // A flaky lava-api-go returns 503 mid-stream (with a JSON-ish error body
        // that, if NOT guarded, would be mis-decoded into a fake-empty result).
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"upstream unavailable"}"""),
        )

        try {
            searchable().search(SearchRequest(query = "boom"), page = 0)
            fail("503 from the API MUST surface as a categorised failure, not a silent/empty result")
        } catch (e: IllegalStateException) {
            // PRIMARY — the failure carries the HTTP status the user-facing layer
            // maps to "Something went wrong"; it is NOT a SerializationException
            // (mis-decode) nor a swallowed empty result.
            assertTrue(
                "failure message must name the HTTP code (was: ${e.message})",
                e.message?.contains("503") == true,
            )
        }
    }

    @Test
    fun chaos_connectionResetMidBody_failsGracefully_noUncategorisedCrash() = runBlocking {
        // Server accepts the request then drops the socket at the start of the
        // response body — the canonical "connection reset" a mobile network hits.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        try {
            searchable().search(SearchRequest(query = "reset"), page = 0)
            fail("a mid-body connection reset MUST surface as an exception, not a fake result")
        } catch (e: Exception) {
            // PRIMARY — a connection reset is an IOException family failure, NOT
            // an NPE / IndexOutOfBounds / other crash that would indicate the
            // client tried to parse a half-read or null body. (IllegalState is
            // also acceptable: the empty-body guard `?: error(...)` may fire if a
            // 0-length body is delivered before the reset.)
            assertTrue(
                "reset must be IO/IllegalState, not an arbitrary crash (was ${e::class.java.name}: ${e.message})",
                e is java.io.IOException || e is IllegalStateException,
            )
        }
    }

    @Test
    fun chaos_emptyBodyOn200_mapsToCategorisedFailure_notMisparse() = runBlocking {
        // 200 OK but a zero-length body — kotlinx-serialization on "" would throw
        // an opaque SerializationException; the client/decoder MUST surface a
        // failure the user-facing layer can categorise, not a fake-success.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(""),
        )

        try {
            val r = searchable().search(SearchRequest(query = "empty"), page = 0)
            fail("empty 200 body MUST NOT decode to a fake result (got items=${r.items.size})")
        } catch (e: Exception) {
            // PRIMARY — an empty/garbage body fails loudly. Acceptable categories:
            // serialization failure (decode of "") or the empty-body guard.
            assertTrue(
                "empty body must fail loudly (was ${e::class.java.name})",
                e is IllegalStateException || e is kotlinx.serialization.SerializationException,
            )
        }
    }

    @Test
    fun chaos_intermittent5xxAmongstSuccesses_isolatesFailures_doesNotPoisonPool() = runBlocking {
        // Mixed stream: every 4th request 503s, the rest succeed. Verifies a
        // transient server error does NOT corrupt the shared OkHttpClient's
        // connection pool such that subsequent SUCCESS requests also fail.
        val counter = java.util.concurrent.atomic.AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val idx = counter.getAndIncrement()
                val q = request.requestUrl?.queryParameter("query") ?: "none"
                return if (idx % 4 == 3) {
                    MockResponse().setResponseCode(503).setBody("""{"error":"flaky"}""")
                } else {
                    MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(bodyFor(q))
                }
            }
        }

        val sut = searchable()
        var successes = 0
        var failures = 0
        for (i in 0 until 40) {
            try {
                val r = sut.search(SearchRequest(query = "m$i"), page = 0)
                // Each success carries the right payload — proves the pool stayed healthy.
                assertEquals("result-for-m$i", r.items.single().title)
                successes++
            } catch (e: IllegalStateException) {
                failures++
            }
        }
        // PRIMARY — failures are exactly the injected 503s (10 of 40), successes
        // the rest (30). A poisoned pool would inflate `failures`.
        println("[CHAOS-MIX] over 40 requests: successes=$successes failures=$failures (expected 30/10)")
        assertEquals("injected 503s must be the ONLY failures", 10, failures)
        assertEquals("all non-503 requests must succeed (pool not poisoned)", 30, successes)
    }
}

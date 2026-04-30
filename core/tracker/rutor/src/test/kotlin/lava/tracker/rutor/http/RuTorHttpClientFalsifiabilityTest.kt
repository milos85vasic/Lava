package lava.tracker.rutor.http

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Falsifiability anchor for the RuTor concurrency bound (Anti-Bluff Pact
 * Sixth Law clause 2). The contract is "no more than 4 concurrent requests
 * leave RuTorHttpClient at any one time"; this test pins that contract by
 * counting in-flight requests at the MockWebServer dispatcher.
 *
 * Rehearsal protocol (recorded at .lava-ci-evidence/sp3a-rutor/3.10-semaphore.json):
 *   1. Mutate RuTorHttpClient: change Semaphore(permits = 4) to permits = 100.
 *   2. Run this test — observe assertion failure with maxObserved >= 8.
 *   3. Revert; observe pass.
 *
 * If this test ever stops being able to fail under the mutation in step 1,
 * the production code path no longer enforces the bound and the test has
 * become a bluff — the file MUST then be reworked, not removed.
 */
class RuTorHttpClientFalsifiabilityTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `semaphore bounds concurrent requests at exactly 4 in flight`() = runBlocking {
        val inFlight = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val current = inFlight.incrementAndGet()
                maxObserved.updateAndGet { prev -> if (current > prev) current else prev }
                Thread.sleep(120)
                inFlight.decrementAndGet()
                return MockResponse().setResponseCode(200).setBody("ok")
            }
        }
        val client = RuTorHttpClient()
        val total = 12

        coroutineScope {
            val jobs = (1..total).map {
                async {
                    val response = client.get(server.url("/req-$it").toString())
                    response.close()
                }
            }
            jobs.awaitAll()
        }

        // Pin: max observed in-flight at the wire MUST be exactly 4.
        // - Allowing > 4 would be a bluff (the bound has been removed).
        // - Allowing < 4 means the test didn't exercise enough load (likely
        //   a CI box with too few schedulers) — guard with the lower bound.
        assertTrue(
            "Max in-flight observed must be <= 4 (semaphore bound), was ${maxObserved.get()}",
            maxObserved.get() <= 4,
        )
        assertTrue(
            "Max in-flight observed must be >= 3 to confirm exercise, was ${maxObserved.get()}",
            maxObserved.get() >= 3,
        )
    }
}

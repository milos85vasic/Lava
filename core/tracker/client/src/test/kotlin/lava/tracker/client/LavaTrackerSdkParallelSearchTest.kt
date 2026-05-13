package lava.tracker.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import lava.sdk.api.MirrorUrl
import lava.sdk.api.PluginConfig
import lava.sdk.api.Protocol
import lava.tracker.api.AuthType
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.api.TrackerFeature
import lava.tracker.api.feature.SearchableTracker
import lava.tracker.api.model.SearchRequest
import lava.tracker.api.model.SearchResult
import lava.tracker.api.model.TorrentItem
import lava.tracker.registry.DefaultTrackerRegistry
import lava.tracker.registry.TrackerClientFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KClass

/**
 * SP-4 Phase D parallel search SDK tests.
 *
 * Anti-Bluff (§6.J / §6.L): every assertion is on a user-observable
 * outcome of `multiSearch` / `streamMultiSearch` — the deduplicated
 * UnifiedSearchResult contents, the per-provider ProviderSearchStatus
 * state, the MultiSearchEvent sequence emitted to a Flow collector.
 *
 * Falsifiability rehearsals recorded in commit body. Mutations targeted:
 *   - parallel-fan-out (Test 1): remove the `async { }` wrapper so
 *     `providerIds.map { runOneProvider(...) }` runs sequentially.
 *     Test 1 fails at the `maxConcurrent == 3` assertion (line 111)
 *     because only one provider is suspended on the barrier at any
 *     given moment. Important subtlety discovered during rehearsal:
 *     `async { }` starts coroutines EAGERLY by default, so a mutation
 *     to `.map { it.await() }` (instead of `.awaitAll()`) does NOT
 *     break parallelism — all `async` blocks are already running
 *     before the first `await` is called. The true sequential
 *     mutation removes the `async` wrapper entirely.
 *   - cancellation (Test 2): remove the `coroutineScope { ... }` or
 *     `channelFlow { ... }` structured wrapper so children are
 *     unstructured. The suspending provider's `delay(Long.MAX_VALUE)`
 *     never sees CancellationException; `sawCancellation` stays false;
 *     Test 2 fails at its `assertTrue` line.
 *   - failure isolation (Test 4): switch from `coroutineScope` to
 *     `supervisorScope` removal — actually, the current `coroutineScope`
 *     + `async`'s exception-on-await semantics IS what isolates
 *     failures here because we catch in `runOneProvider`. The mutation
 *     that breaks Test 4 is removing the try/catch inside
 *     `runOneProvider` so a throwing provider propagates the exception
 *     to `awaitAll()`, which cancels siblings.
 */
class LavaTrackerSdkParallelSearchTest {

    private fun descriptor(id: String): TrackerDescriptor = object : TrackerDescriptor {
        override val trackerId: String = id
        override val displayName: String = "Tracker $id"
        override val baseUrls: List<MirrorUrl> = listOf(
            MirrorUrl(url = "https://$id.test", isPrimary = true, priority = 0, protocol = Protocol.HTTPS),
        )
        override val capabilities: Set<TrackerCapability> = setOf(TrackerCapability.SEARCH)
        override val authType: AuthType = AuthType.NONE
        override val encoding: String = "UTF-8"
        override val expectedHealthMarker: String = "ok"
    }

    /**
     * Test fake whose `search` is a real `suspend fun` — accepts a
     * lambda body so each test can inject delays, deferreds, or
     * fixed responses. `FakeTrackerClient` in :core:tracker:testing
     * uses a non-suspending lambda which can't simulate parallel
     * latency under coroutines-test virtual time.
     */
    private class SuspendingFakeClient(
        override val descriptor: TrackerDescriptor,
        private val onSearch: suspend (SearchRequest, Int) -> SearchResult,
    ) : TrackerClient {
        override suspend fun healthCheck(): Boolean = true
        override fun close() {}

        @Suppress("UNCHECKED_CAST")
        override fun <T : TrackerFeature> getFeature(featureClass: KClass<T>): T? = when (featureClass) {
            SearchableTracker::class -> {
                val sut = object : SearchableTracker {
                    override suspend fun search(request: SearchRequest, page: Int): SearchResult =
                        onSearch(request, page)
                }
                sut as T
            }
            else -> null
        }
    }

    private fun factoryFor(client: TrackerClient): TrackerClientFactory = object : TrackerClientFactory {
        override val descriptor: TrackerDescriptor = client.descriptor
        override fun create(config: PluginConfig): TrackerClient = client
    }

    private fun item(trackerId: String, id: String) = TorrentItem(
        trackerId = trackerId,
        torrentId = id,
        title = "$trackerId-$id",
    )

    // ---------------------------------------------------------------------
    // Test 1 — parallel fan-out: max-concurrent reaches N providers.
    //
    // Falsifiability: if multiSearch was sequential, only 1 provider would
    // be running at any given time. The maxConcurrent counter would never
    // exceed 1. The assertion that maxConcurrent == 3 falls.
    // ---------------------------------------------------------------------
    @Test
    fun `multiSearch fans out to N providers concurrently`() = runTest {
        var inFlight = 0
        var maxConcurrent = 0
        val barrier = CompletableDeferred<Unit>()

        val providers = listOf("p1", "p2", "p3").map { id ->
            SuspendingFakeClient(descriptor(id)) { _, _ ->
                inFlight++
                if (inFlight > maxConcurrent) maxConcurrent = inFlight
                barrier.await()
                inFlight--
                SearchResult(items = listOf(item(id, "1")), totalPages = 1, currentPage = 0)
            }
        }
        val registry = DefaultTrackerRegistry().apply {
            providers.forEach { register(factoryFor(it)) }
        }
        val sdk = LavaTrackerSdk(registry)

        // Launch multiSearch in a child coroutine so we can release the barrier from the test.
        val resultJob = launch {
            val result = sdk.multiSearch(
                request = SearchRequest(query = "ubuntu"),
                providerIds = listOf("p1", "p2", "p3"),
            )
            // User-visible outcome: 3 items across the 3 providers, all SUCCESS.
            assertEquals(3, result.items.size)
            assertEquals(3, result.providerStatuses.count { it.state == ProviderSearchState.SUCCESS })
        }

        // Wait for all three to enter the suspending block.
        advanceUntilIdle()
        // §6.J primary assertion: maxConcurrent == providerIds.size proves parallel fan-out.
        assertEquals("expected 3-way concurrency; got $maxConcurrent", 3, maxConcurrent)

        // Release the barrier so each provider completes.
        barrier.complete(Unit)
        resultJob.join()
    }

    // ---------------------------------------------------------------------
    // Test 2 — cancellation: cancelling the collector cancels in-flight
    // provider coroutines via structured concurrency.
    //
    // Falsifiability: if streamMultiSearch's child launches were unstructured,
    // the provider's search() would NOT see CancellationException when the
    // collector cancels. The `sawCancellation` flag would stay false.
    // ---------------------------------------------------------------------
    @Test
    fun `streamMultiSearch cancellation propagates to in-flight provider calls`() = runTest {
        val started = CompletableDeferred<Unit>()
        var sawCancellation = false

        val provider = SuspendingFakeClient(descriptor("slow")) { _, _ ->
            started.complete(Unit)
            try {
                delay(Long.MAX_VALUE)
                error("delay should have been cancelled")
            } catch (e: CancellationException) {
                sawCancellation = true
                throw e
            }
        }
        val registry = DefaultTrackerRegistry().apply { register(factoryFor(provider)) }
        val sdk = LavaTrackerSdk(registry)

        val collector = launch {
            sdk.streamMultiSearch(
                request = SearchRequest(query = "anything"),
                providerIds = listOf("slow"),
            ).toList()
        }

        // Wait until the provider has actually started.
        started.await()
        runCurrent()

        // Cancel the collector mid-flight.
        collector.cancelAndJoin()

        // §6.J primary assertion: the provider's suspending block observed
        // structured cancellation. This is a user-observable contract —
        // when the user navigates away, no provider keeps running.
        assertTrue("expected provider coroutine to see CancellationException", sawCancellation)
    }

    // ---------------------------------------------------------------------
    // Test 3 — event order: events arrive in correct sequence per-provider.
    //
    // For each provider: ProviderStart (first) then exactly one of
    // {ProviderResults, ProviderFailure, ProviderUnsupported} (terminal).
    // The sequence is the user-visible contract — the UI relies on
    // ProviderStart preceding the terminal event to render the
    // SEARCHING → DONE/ERROR transition correctly.
    //
    // Falsifiability: if the SDK reordered events (e.g., a buggy
    // refactor that sent ProviderResults before ProviderStart), the
    // per-provider order assertion fails.
    // ---------------------------------------------------------------------
    @Test
    fun `streamMultiSearch emits ProviderStart before the terminal event for every provider`() = runTest {
        val providers = listOf("a", "b", "c").map { id ->
            SuspendingFakeClient(descriptor(id)) { _, _ ->
                SearchResult(items = listOf(item(id, "1")), totalPages = 1, currentPage = 0)
            }
        }
        val registry = DefaultTrackerRegistry().apply {
            providers.forEach { register(factoryFor(it)) }
        }
        val sdk = LavaTrackerSdk(registry)

        val events: List<MultiSearchEvent> = sdk.streamMultiSearch(
            request = SearchRequest(query = "x"),
            providerIds = listOf("a", "b", "c"),
        ).toList()

        // Group by provider id and assert each provider's local sequence is
        // [ProviderStart, ProviderResults] OR [ProviderStart, ProviderFailure]
        // OR [ProviderStart, ProviderUnsupported]. AllProvidersDone is the
        // global terminal event.
        val byProvider = events
            .filter { it !is MultiSearchEvent.AllProvidersDone }
            .groupBy { it.providerId }

        listOf("a", "b", "c").forEach { id ->
            val seq = byProvider[id] ?: error("provider $id never reported any event")
            assertEquals("provider $id should produce exactly 2 events (Start + terminal); got: $seq", 2, seq.size)
            assertTrue(
                "provider $id should start with ProviderStart; got: ${seq[0]}",
                seq[0] is MultiSearchEvent.ProviderStart,
            )
            assertTrue(
                "provider $id should end with a terminal event; got: ${seq[1]}",
                seq[1] is MultiSearchEvent.ProviderResults ||
                    seq[1] is MultiSearchEvent.ProviderFailure ||
                    seq[1] is MultiSearchEvent.ProviderUnsupported,
            )
        }

        // The flow MUST end with AllProvidersDone (no cancellation here).
        val terminal = events.last()
        assertTrue("expected AllProvidersDone as the last event; got: $terminal", terminal is MultiSearchEvent.AllProvidersDone)
        terminal as MultiSearchEvent.AllProvidersDone
        // User-visible state in the AllProvidersDone snapshot.
        assertEquals(3, terminal.unified.items.size)
        assertEquals(
            3,
            terminal.unified.providerStatuses.count { it.state == ProviderSearchState.SUCCESS },
        )
    }

    // ---------------------------------------------------------------------
    // Test 4 — failure isolation: one provider's failure does NOT cancel
    // siblings. Every other provider still produces a terminal event.
    //
    // Falsifiability: if multiSearch used `awaitAll()` over `async`-with-
    // unhandled-exception, one provider throwing would cancel the whole
    // scope and the surviving providers would not appear in
    // providerStatuses. The assertion that p2 + p3 status entries exist
    // would fail.
    // ---------------------------------------------------------------------
    @Test
    fun `multiSearch isolates per-provider failure from siblings`() = runTest {
        val p1 = SuspendingFakeClient(descriptor("p1")) { _, _ -> throw IllegalStateException("p1 boom") }
        val p2 = SuspendingFakeClient(descriptor("p2")) { _, _ ->
            SearchResult(items = listOf(item("p2", "x")), totalPages = 1, currentPage = 0)
        }
        val p3 = SuspendingFakeClient(descriptor("p3")) { _, _ ->
            SearchResult(items = listOf(item("p3", "y")), totalPages = 1, currentPage = 0)
        }
        val registry = DefaultTrackerRegistry().apply {
            register(factoryFor(p1))
            register(factoryFor(p2))
            register(factoryFor(p3))
        }
        val sdk = LavaTrackerSdk(registry)

        val result = sdk.multiSearch(
            request = SearchRequest(query = "x"),
            providerIds = listOf("p1", "p2", "p3"),
        )

        val byProvider = result.providerStatuses.associateBy { it.providerId }
        assertEquals(ProviderSearchState.FAILURE, byProvider["p1"]?.state)
        assertEquals("p1 boom", byProvider["p1"]?.errorMessage)
        assertEquals(ProviderSearchState.SUCCESS, byProvider["p2"]?.state)
        assertEquals(ProviderSearchState.SUCCESS, byProvider["p3"]?.state)
        // User-visible: the SUCCESS items from p2 + p3 surface in the
        // unified results even though p1 failed.
        assertEquals(2, result.items.size)
    }
}

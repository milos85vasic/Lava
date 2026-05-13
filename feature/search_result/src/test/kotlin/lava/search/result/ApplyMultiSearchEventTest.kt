package lava.search.result

import lava.models.search.Filter
import lava.tracker.api.model.TorrentItem
import lava.tracker.client.MultiSearchEvent
import lava.tracker.client.ProviderSearchStatus
import lava.tracker.client.UnifiedSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SP-4 Phase D consumer-VM coverage (added 2026-05-13 in the
 * post-Phase-G.1 audit). Tests the pure state transformation
 * `applyMultiSearchEvent(state, event): SearchPageState` extracted
 * from `SearchResultViewModel.handleMultiSearchEvent` so the consumer
 * branch — which Phase D shipped untested — has assertions on
 * user-observable state.
 *
 * Anti-Bluff posture (§6.J): assertions are on the rendered Compose
 * UI's source-of-truth — the `SearchResultContent.Streaming`
 * `items` + `activeProviders` lists. The Compose UI keys per-provider
 * status badges on `ProviderStreamStatus.providerId / displayName /
 * status / resultCount`. A bug in the reducer would surface as a
 * wrong badge or missing items in the result list.
 *
 * Falsifiability rehearsal (§6.J / §6.N): each test's KDoc names a
 * deliberate mutation to `applyMultiSearchEvent` that the test
 * catches. Rehearsals executed pre-commit per the project's
 * Bluff-Audit stamp protocol.
 */
class ApplyMultiSearchEventTest {

    private val initialStreaming = SearchResultContent.Streaming(
        items = emptyList(),
        activeProviders = listOf(
            ProviderStreamStatus(providerId = "p1", displayName = "p1", status = StreamStatus.SEARCHING),
            ProviderStreamStatus(providerId = "p2", displayName = "p2", status = StreamStatus.SEARCHING),
        ),
    )
    private val initial = SearchPageState(
        filter = Filter(query = "ubuntu"),
        searchContent = initialStreaming,
    )

    /**
     * Falsifiability: replace `it.copy(displayName = event.displayName)`
     * with `it.copy(displayName = it.displayName)` in the ProviderStart
     * branch — the badge label keeps the bare provider-id "p1" instead
     * of the friendly name "RuTracker.org".
     */
    @Test
    fun `ProviderStart stamps displayName into the matching provider row + providerDisplayNames map`() {
        val event = MultiSearchEvent.ProviderStart(providerId = "p1", displayName = "RuTracker.org")

        val next = applyMultiSearchEvent(initial, event)

        assertEquals(
            "providerDisplayNames map must include the event's pair",
            mapOf("p1" to "RuTracker.org"),
            next.providerDisplayNames,
        )
        val streaming = next.searchContent as SearchResultContent.Streaming
        val p1 = streaming.activeProviders.first { it.providerId == "p1" }
        // §6.J primary — the Compose UI's per-provider badge reads from displayName.
        assertEquals("RuTracker.org", p1.displayName)
        // p2 unchanged.
        assertEquals("p2", streaming.activeProviders.first { it.providerId == "p2" }.displayName)
    }

    /**
     * Falsifiability: drop the `items = current.items + newItems` line —
     * items returned by p1 never make it into the list rendered for the
     * user. The first assertion fails (`expected 2 items, was 0`).
     */
    @Test
    fun `ProviderResults appends mapped items + flips the provider row to DONE`() {
        val event = MultiSearchEvent.ProviderResults(
            providerId = "p1",
            items = listOf(
                TorrentItem(trackerId = "ignored", torrentId = "t1", title = "Ubuntu ISO"),
                TorrentItem(trackerId = "ignored", torrentId = "t2", title = "Debian ISO"),
            ),
        )

        val next = applyMultiSearchEvent(initial, event)

        val streaming = next.searchContent as SearchResultContent.Streaming
        // §6.J primary — the search-result list's user-visible items.
        assertEquals(2, streaming.items.size)
        assertEquals("Ubuntu ISO", streaming.items[0].topic.title)
        // The mapped items carry the per-PROVIDER id (not the source's
        // trackerId field on the TorrentItem). Phase F.1 + SearchResultScreen
        // both key on this for the per-provider grouping.
        assertEquals("p1", streaming.items[0].providerId)
        assertEquals("p1", streaming.items[1].providerId)

        val p1 = streaming.activeProviders.first { it.providerId == "p1" }
        assertEquals(StreamStatus.DONE, p1.status)
        assertEquals(2, p1.resultCount)
    }

    /**
     * Falsifiability: replace `StreamStatus.ERROR` with `StreamStatus.DONE`
     * — the user sees a green checkmark on a failed provider.
     */
    @Test
    fun `ProviderFailure flips the row to ERROR without touching other providers`() {
        val event = MultiSearchEvent.ProviderFailure(providerId = "p1", reason = "boom")

        val next = applyMultiSearchEvent(initial, event)

        val streaming = next.searchContent as SearchResultContent.Streaming
        // §6.J primary — the error chip on the per-provider badge.
        assertEquals(StreamStatus.ERROR, streaming.activeProviders.first { it.providerId == "p1" }.status)
        // p2 untouched.
        assertEquals(StreamStatus.SEARCHING, streaming.activeProviders.first { it.providerId == "p2" }.status)
        // No items appended.
        assertTrue(streaming.items.isEmpty())
    }

    /**
     * Falsifiability: change the `resultCount = 0` line to
     * `resultCount = -1` — the count surfaces a nonsensical negative in
     * the UI badge.
     */
    @Test
    fun `ProviderUnsupported flips the row to DONE with resultCount=0`() {
        val event = MultiSearchEvent.ProviderUnsupported(providerId = "p2")

        val next = applyMultiSearchEvent(initial, event)

        val p2 = (next.searchContent as SearchResultContent.Streaming)
            .activeProviders.first { it.providerId == "p2" }
        assertEquals(StreamStatus.DONE, p2.status)
        assertEquals(0, p2.resultCount)
    }

    /**
     * The terminal event MUST be a no-op at the reducer level — the
     * caller's handleStreamEnd() does the Streaming → Content/Empty
     * downgrade after the flow completes. If `AllProvidersDone` were
     * to mutate state here, the UI would flicker between two
     * representations of "done".
     *
     * Falsifiability: change the `is MultiSearchEvent.AllProvidersDone
     * -> state` branch to `state.copy(searchContent = ...)` — the
     * assertSame check fails.
     */
    @Test
    fun `AllProvidersDone is a no-op at the reducer level`() {
        val event = MultiSearchEvent.AllProvidersDone(
            unified = UnifiedSearchResult(
                query = "ubuntu",
                page = 0,
                items = emptyList(),
                totalPages = 1,
                providerStatuses = listOf<ProviderSearchStatus>(),
            ),
        )

        val next = applyMultiSearchEvent(initial, event)

        assertSame("AllProvidersDone must not mutate state at the reducer level", initial, next)
    }

    /**
     * Defensive — if the user navigated away and the Streaming content
     * was already downgraded to Content/Empty/Initial, late events
     * arriving from the SDK MUST be silently ignored. Otherwise a
     * delayed ProviderResults could overwrite the user's now-visible
     * result list.
     */
    @Test
    fun `events are no-op when searchContent is not Streaming`() {
        val nonStreaming = initial.copy(searchContent = SearchResultContent.Initial)

        val next = applyMultiSearchEvent(
            nonStreaming,
            MultiSearchEvent.ProviderResults(
                providerId = "p1",
                items = listOf(TorrentItem(trackerId = "x", torrentId = "y", title = "z")),
            ),
        )

        assertSame(nonStreaming, next)
    }
}

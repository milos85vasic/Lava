package lava.search.result

import lava.models.search.Filter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LVA-069 (2026-06-09). Coverage for `applySseEvent(state, type, data)`
 * — the raw-JSON parser for the Go-API multi-search SSE stream
 * (`provider_start` / `results` / `provider_done` / `provider_error`)
 * extracted from `SearchResultViewModel.handleSseEvent`. Before this
 * cycle the Go-API SSE path shipped UNTESTED: only the client-direct
 * typed path (`applyMultiSearchEvent`) had assertions. The raw-JSON
 * parse is exactly where the LVA-057-class bugs live on the client side
 * — a malformed event dropped silently, a `display_name`/`result_count`
 * field mapped to the wrong key, a `provider_error` event not surfacing
 * as a per-provider error badge.
 *
 * Anti-Bluff posture (§6.J): assertions are on the rendered Compose UI's
 * source-of-truth — `SearchResultContent.Streaming.items` +
 * `activeProviders` (the per-provider status badges the user sees). The
 * raw JSON strings mirror the byte shape `lava-api-go` actually streams
 * (see `core/.../SseClientTest` fixtures + the Go SSE handler).
 *
 * Falsifiability rehearsal (§6.J / §6.N): each test's KDoc names the
 * deliberate mutation to `applySseEvent` that the test catches; the
 * file-level mutation is recorded in the commit's Bluff-Audit stamp.
 */
class ApplySseEventTest {

    private val initialStreaming = SearchResultContent.Streaming(
        items = emptyList(),
        activeProviders = listOf(
            ProviderStreamStatus(providerId = "rutracker", displayName = "rutracker", status = StreamStatus.SEARCHING),
            ProviderStreamStatus(providerId = "rutor", displayName = "rutor", status = StreamStatus.SEARCHING),
        ),
    )
    private val initial = SearchPageState(
        filter = Filter(query = "ubuntu"),
        searchContent = initialStreaming,
    )

    /**
     * Falsifiability: change the `display_name` JSON key lookup to
     * `json["display"]` (wrong key) — the friendly badge label silently
     * falls back to the bare "rutracker" id and the displayName assertion
     * (`expected RuTracker.org`) fails.
     */
    @Test
    fun `provider_start stamps display_name into the matching provider badge`() {
        val next = applySseEvent(
            initial,
            type = "provider_start",
            data = """{"provider_id":"rutracker","display_name":"RuTracker.org"}""",
        )

        assertEquals(
            "providerDisplayNames map must carry the parsed pair",
            mapOf("rutracker" to "RuTracker.org"),
            next.providerDisplayNames,
        )
        val streaming = next.searchContent as SearchResultContent.Streaming
        // §6.J primary — the per-provider badge the user reads.
        assertEquals("RuTracker.org", streaming.activeProviders.first { it.providerId == "rutracker" }.displayName)
        // The untouched provider keeps its bare id.
        assertEquals("rutor", streaming.activeProviders.first { it.providerId == "rutor" }.displayName)
    }

    /**
     * provider_start without a display_name MUST fall back to the bare
     * provider_id, NOT crash or leave the field empty.
     *
     * Falsifiability: remove the `?: pid` fallback (make dname require the
     * key) — the parse returns state unchanged and the displayName stays
     * "rutracker"... which is what the fallback already does, so instead
     * the meaningful mutation is to change the fallback to `?: ""`: the
     * badge renders blank and this assertion (`expected rutracker`) fails.
     */
    @Test
    fun `provider_start falls back to provider_id when display_name absent`() {
        val next = applySseEvent(
            initial,
            type = "provider_start",
            data = """{"provider_id":"rutracker"}""",
        )

        val streaming = next.searchContent as SearchResultContent.Streaming
        assertEquals("rutracker", streaming.activeProviders.first { it.providerId == "rutracker" }.displayName)
    }

    /**
     * Falsifiability: drop the `items = current.items + newItems` line —
     * results parsed from the stream never reach the rendered list and
     * the `2 items` assertion fails (`expected 2 was 0`).
     */
    @Test
    fun `results appends mapped items and flips the row to RECEIVING`() {
        val next = applySseEvent(
            initial,
            type = "results",
            data = """
                {"provider_id":"rutracker","items":[
                  {"id":"t1","title":"Ubuntu ISO"},
                  {"id":"t2","title":"Debian ISO"}
                ],"page":1,"total_pages":1}
            """.trimIndent(),
        )

        val streaming = next.searchContent as SearchResultContent.Streaming
        // §6.J primary — the user-visible result list.
        assertEquals(2, streaming.items.size)
        assertEquals("Ubuntu ISO", streaming.items[0].topic.title)
        assertEquals("t1", streaming.items[0].topic.id)
        // The mapped items carry the per-PROVIDER id (the SearchResultScreen
        // groups + the topic-download branch key on this — LVA-052).
        assertEquals("rutracker", streaming.items[0].providerId)
        assertEquals("rutracker", streaming.items[1].providerId)

        val row = streaming.activeProviders.first { it.providerId == "rutracker" }
        assertEquals(StreamStatus.RECEIVING, row.status)
        assertEquals(2, row.resultCount)
        // The other provider's row is untouched.
        assertEquals(StreamStatus.SEARCHING, streaming.activeProviders.first { it.providerId == "rutor" }.status)
    }

    /**
     * An item missing its `id` MUST be dropped silently (it cannot be
     * downloaded without an id) WITHOUT dropping the sibling items that
     * DO have ids.
     *
     * Falsifiability: change the per-item `?: return@mapNotNull null` to
     * `?: ""` — the id-less item would be appended with a blank id and
     * the size assertion (`expected 1`) fails (`was 2`).
     */
    @Test
    fun `results drops items missing an id but keeps valid siblings`() {
        val next = applySseEvent(
            initial,
            type = "results",
            data = """
                {"provider_id":"rutracker","items":[
                  {"title":"No-id item"},
                  {"id":"t9","title":"Valid item"}
                ]}
            """.trimIndent(),
        )

        val streaming = next.searchContent as SearchResultContent.Streaming
        assertEquals(1, streaming.items.size)
        assertEquals("t9", streaming.items[0].topic.id)
        assertEquals("Valid item", streaming.items[0].topic.title)
    }

    /**
     * Falsifiability: change `?: 0` on result_count to `?: -1`, OR map the
     * count from the wrong key — the DONE badge surfaces the wrong number;
     * the `resultCount == 42` assertion fails.
     */
    @Test
    fun `provider_done flips the row to DONE with server-reported result_count`() {
        val next = applySseEvent(
            initial,
            type = "provider_done",
            data = """{"provider_id":"rutracker","result_count":42}""",
        )

        val row = (next.searchContent as SearchResultContent.Streaming)
            .activeProviders.first { it.providerId == "rutracker" }
        assertEquals(StreamStatus.DONE, row.status)
        assertEquals(42, row.resultCount)
    }

    /**
     * provider_error is the LVA-057-class case on the client side: a
     * single provider failing MUST surface as that provider's ERROR badge
     * WITHOUT failing the whole stream or touching the other provider.
     *
     * Falsifiability: change `StreamStatus.ERROR` to `StreamStatus.DONE`
     * in the provider_error branch — the user sees a green checkmark on a
     * failed provider; the `ERROR` assertion fails.
     */
    @Test
    fun `provider_error surfaces the failed provider as an ERROR badge only`() {
        val next = applySseEvent(
            initial,
            type = "provider_error",
            data = """{"provider_id":"rutracker","error":"upstream 503"}""",
        )

        val streaming = next.searchContent as SearchResultContent.Streaming
        // §6.J primary — the error chip on the per-provider badge.
        assertEquals(StreamStatus.ERROR, streaming.activeProviders.first { it.providerId == "rutracker" }.status)
        // The healthy provider is NOT collateral damage.
        assertEquals(StreamStatus.SEARCHING, streaming.activeProviders.first { it.providerId == "rutor" }.status)
        // No items appended on an error.
        assertTrue(streaming.items.isEmpty())
    }

    /**
     * A malformed event missing its `provider_id` MUST be a no-op — it is
     * NEVER allowed to corrupt another provider's row or crash the parse.
     * This is the silent-drop case the LVA-057 class is about.
     *
     * Falsifiability: remove the `?: return state` guard on the
     * provider_done provider_id lookup — a null pid would attempt to match
     * no row (harmless) but a stricter mutation that defaults pid to ""
     * would still no-op here; the load-bearing guard is the parse-failure
     * path below.
     */
    @Test
    fun `event missing provider_id is a no-op`() {
        val next = applySseEvent(
            initial,
            type = "provider_done",
            data = """{"result_count":7}""",
        )
        assertSame("event without provider_id must leave state unchanged", initial, next)
    }

    /**
     * A non-JSON / truncated `data` payload (the stream got cut mid-event)
     * MUST NOT crash the parse — it returns state unchanged.
     *
     * Falsifiability: remove the `runCatching { ... }.getOrNull() ?: return state`
     * wrapper around `Json.parseToJsonElement` — feeding this garbage
     * throws `SerializationException` and the test fails with that
     * exception instead of passing.
     */
    @Test
    fun `malformed JSON data payload is a no-op and does not crash`() {
        val next = applySseEvent(
            initial,
            type = "results",
            data = """{"provider_id":"rutracker","items":[{"id":"t1",""", // truncated
        )
        assertSame("malformed JSON must leave state unchanged, not throw", initial, next)
    }

    /**
     * An unknown event type the server might add in a future version MUST
     * be ignored (forward-compat), not throw or wipe state.
     */
    @Test
    fun `unknown event type is a no-op`() {
        val next = applySseEvent(
            initial,
            type = "some_future_event",
            data = """{"provider_id":"rutracker"}""",
        )
        assertSame(initial, next)
    }

    /**
     * Defensive — once the user navigated away and Streaming was already
     * downgraded to Content/Empty/Initial, late SSE events MUST be ignored
     * so a delayed `results` cannot overwrite the now-visible list.
     */
    @Test
    fun `events are no-op when searchContent is not Streaming`() {
        val nonStreaming = initial.copy(searchContent = SearchResultContent.Initial)

        val next = applySseEvent(
            nonStreaming,
            type = "results",
            data = """{"provider_id":"rutracker","items":[{"id":"x","title":"y"}]}""",
        )

        assertSame(nonStreaming, next)
    }
}

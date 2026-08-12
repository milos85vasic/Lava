package lava.search.result

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import lava.common.analytics.AnalyticsTracker
import lava.domain.model.PagingAction
import lava.domain.model.PagingData
import lava.domain.usecase.AddSearchHistoryUseCase
import lava.domain.usecase.EnrichFilterUseCase
import lava.domain.usecase.ObserveAuthStateUseCase
import lava.domain.usecase.ObserveSearchPagingDataUseCase
import lava.domain.usecase.ObserveSettingsUseCase
import lava.domain.usecase.StartupProvidersGate
import lava.domain.usecase.ToggleFavoriteUseCase
import lava.models.auth.AuthState
import lava.models.search.Filter
import lava.models.topic.TopicModel
import lava.models.topic.Torrent
import lava.sdk.api.MirrorUrl
import lava.sdk.api.PluginConfig
import lava.sdk.api.Protocol
import lava.testing.logger.TestLoggerFactory
import lava.testing.repository.TestSettingsRepository
import lava.testing.rule.MainDispatcherRule
import lava.tracker.api.AuthType
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.api.TrackerFeature
import lava.tracker.api.feature.SearchableTracker
import lava.tracker.api.model.SearchRequest
import lava.tracker.api.model.SearchResult
import lava.tracker.api.model.TorrentItem
import lava.tracker.client.LavaTrackerSdk
import lava.tracker.registry.DefaultTrackerRegistry
import lava.tracker.registry.TrackerClientFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.orbitmvi.orbit.test.test
import kotlin.reflect.KClass

/**
 * LVA-086 (2026-06-25 QA video issue #5): "the search results screen shows NO
 * empty-state UI and NO loading indicator while a search is in progress or
 * when it returns zero results — from the user's perspective this looks like
 * the app has hung." QA video frames 0060-0110 show a pure black body for
 * ~25s (the [SearchResultViewModel.SEARCH_TIMEOUT_MS] client-side deadline)
 * with no spinner, no skeleton, no "Searching…" text, before the Error state
 * finally appears.
 *
 * ## Root cause (found, not assumed)
 *
 * The state MODEL already distinguished [SearchResultContent.Initial],
 * [SearchResultContent.Streaming], [SearchResultContent.Empty],
 * [SearchResultContent.Content], and [SearchResultContent.Error] — that part
 * was never broken. The gap was in the RENDER layer: [SearchResultScreen]'s
 * `when (state.searchContent)` branch for `Streaming` rendered ONLY the
 * incremental item list; it had NO case covering "Streaming, zero items so
 * far, at least one provider still SEARCHING" — the exact window a fresh
 * multi-provider search spends its first (up to) 25 seconds in. A prior
 * commit (`e7b6a652`, 2026-06-25) added a `loadingItem()` call for that
 * window directly inline in the Composable, but shipped with NO test proving
 * the render predicate is correct — the render logic was DUPLICATED, not
 * unit-testable, and could silently regress.
 *
 * This commit extracts the render predicate to
 * [lava.search.result.streamingFilteredItems] +
 * [lava.search.result.showsStreamingLoadingIndicator] in `SearchPageState.kt`
 * so [SearchResultScreen] and this test both consume the SAME production
 * logic — a JVM test proves the exact code the Composable renders from, not a
 * hand-copied duplicate (Sixth Law clause 1: no shortcut bypassing real
 * wiring).
 *
 * ## Why this is a real gap (not covered elsewhere)
 *
 * - [SearchResultViewModelStreamingTest] only asserts the TERMINAL state
 *   (`Content` or `Empty`) after `handleStreamEnd()` runs — it never
 *   inspects the state WHILE a provider is still `SEARCHING`, so it could
 *   not have caught a missing/incorrect loading-indicator predicate.
 * - [SearchResultViewModelCancelTimeoutTest] inspects the mid-flight state
 *   only to assert cancellation-on-back and the timeout→Error transition —
 *   it never asserts on the loading-indicator predicate itself.
 * - [ApplyMultiSearchEventTest] tests only the pure per-event reducer; it
 *   never touches [SearchResultViewModel.onCreate] or the render-predicate
 *   extension properties.
 *
 * ## Real-stack wiring (Second/Third Law)
 *
 * SUT is the REAL [SearchResultViewModel] driven by a REAL [LavaTrackerSdk]
 * over a REAL [DefaultTrackerRegistry]. Only the outermost network boundary
 * is faked: a [SlowSearchClient] whose `search()` suspends on a
 * [CompletableDeferred] until the test calls `release()` — simulating an
 * in-flight (not-yet-responded) backend call. No UseCase and no SDK method
 * is mocked.
 *
 * ## Test classification
 * CHALLENGE — primary assertion on the exact user-visible render predicate
 * ([lava.search.result.showsStreamingLoadingIndicator]) the Composable
 * consumes, plus the underlying [SearchPageState.searchContent] state.
 *
 * ## Bluff-Audit
 * See commit body for the mutation + observed-failure record.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchResultViewModelLoadingEmptyStateTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun descriptor(id: String): TrackerDescriptor = object : TrackerDescriptor {
        override val trackerId: String = id
        override val displayName: String = "Tracker ${id.uppercase()}"
        override val baseUrls: List<MirrorUrl> = listOf(
            MirrorUrl(url = "https://$id.test", isPrimary = true, priority = 0, protocol = Protocol.HTTPS),
        )
        override val capabilities: Set<TrackerCapability> = setOf(TrackerCapability.SEARCH)
        override val authType: AuthType = AuthType.NONE
        override val encoding: String = "UTF-8"
        override val expectedHealthMarker: String = "ok"
    }

    /**
     * A tracker client whose `search()` suspends on a [CompletableDeferred]
     * until the test calls [release] — simulating a backend call that is
     * genuinely in flight (neither succeeded nor failed yet). Dispatcher-
     * agnostic (works under both `UnconfinedTestDispatcher` and
     * `StandardTestDispatcher`) because the suspension is not time-based.
     */
    private class SlowSearchClient(
        override val descriptor: TrackerDescriptor,
        private val items: List<TorrentItem> = emptyList(),
    ) : TrackerClient {
        private val gate = CompletableDeferred<Unit>()

        fun release() {
            gate.complete(Unit)
        }

        override suspend fun healthCheck(): Boolean = true
        override fun close() {}

        @Suppress("UNCHECKED_CAST")
        override fun <T : TrackerFeature> getFeature(featureClass: KClass<T>): T? = when (featureClass) {
            SearchableTracker::class -> object : SearchableTracker {
                override suspend fun search(request: SearchRequest, page: Int): SearchResult {
                    gate.await()
                    return SearchResult(items = items, totalPages = 1, currentPage = page)
                }
            } as T
            else -> null
        }
    }

    /** Fake tracker client whose `search()` returns fixed in-memory results immediately. */
    private class FixedResultClient(
        override val descriptor: TrackerDescriptor,
        private val items: List<TorrentItem>,
    ) : TrackerClient {
        override suspend fun healthCheck(): Boolean = true
        override fun close() {}

        @Suppress("UNCHECKED_CAST")
        override fun <T : TrackerFeature> getFeature(featureClass: KClass<T>): T? = when (featureClass) {
            SearchableTracker::class -> object : SearchableTracker {
                override suspend fun search(request: SearchRequest, page: Int): SearchResult =
                    SearchResult(items = items, totalPages = 1, currentPage = page)
            } as T
            else -> null
        }
    }

    private fun factoryFor(client: TrackerClient): TrackerClientFactory = object : TrackerClientFactory {
        override val descriptor: TrackerDescriptor = client.descriptor
        override fun create(config: PluginConfig): TrackerClient = client
    }

    private fun item(provider: String, id: String, title: String) =
        TorrentItem(trackerId = provider, torrentId = id, title = title)

    private val providerIdsKey = "pids"
    private val queryKey = "nm"

    private class FakeObserveSearchPagingDataUseCase : ObserveSearchPagingDataUseCase {
        override fun invoke(
            filterFlow: Flow<Filter>,
            actionsFlow: Flow<PagingAction>,
            scope: CoroutineScope,
        ): Flow<PagingData<List<TopicModel<Torrent>>>> = flowOf()
    }

    private class FakeAddSearchHistoryUseCase : AddSearchHistoryUseCase {
        override suspend fun invoke(filter: Filter) {}
    }

    private class FakeEnrichFilterUseCase : EnrichFilterUseCase {
        override suspend fun invoke(filter: Filter): Filter = filter
    }

    private class FakeToggleFavoriteUseCase : ToggleFavoriteUseCase {
        override suspend fun invoke(id: String, providerId: String?) {}
    }

    private class FakeObserveAuthStateUseCase : ObserveAuthStateUseCase {
        val state = MutableStateFlow<AuthState>(AuthState.Unauthorized)
        override fun invoke(): Flow<AuthState> = state
    }

    private val noopAnalytics = object : AnalyticsTracker {
        override fun event(name: String, params: Map<String, String>) {}
        override fun setUserId(userId: String?) {}
        override fun setProperty(key: String, value: String?) {}
        override fun recordNonFatal(throwable: Throwable, context: Map<String, String>) {}
        override fun recordWarning(message: String, context: Map<String, String>) {}
        override fun log(message: String) {}
    }

    private fun createViewModel(
        providerIds: List<String>,
        clients: List<TrackerClient>,
    ): SearchResultViewModel {
        val registry = DefaultTrackerRegistry()
        clients.forEach { registry.register(factoryFor(it)) }
        val savedState = SavedStateHandle(
            mapOf(
                queryKey to "prince",
                providerIdsKey to providerIds.joinToString(","),
            ),
        )
        return SearchResultViewModel(
            savedStateHandle = savedState,
            loggerFactory = TestLoggerFactory(),
            observeSearchPagingDataUseCase = FakeObserveSearchPagingDataUseCase(),
            addSearchHistoryUseCase = FakeAddSearchHistoryUseCase(),
            enrichFilterUseCase = FakeEnrichFilterUseCase(),
            toggleFavoriteUseCase = FakeToggleFavoriteUseCase(),
            observeAuthStateUseCase = FakeObserveAuthStateUseCase(),
            observeSettingsUseCase = ObserveSettingsUseCase(TestSettingsRepository()),
            analytics = noopAnalytics,
            sdk = LavaTrackerSdk(registry = registry),
            providersReadyGate = StartupProvidersGate().apply { markReady() },
        )
    }

    // CHALLENGE
    //
    // §6.J primary — while a fresh multi-provider search is genuinely in
    // flight (no provider has responded yet), the state MUST already be in
    // the shape SearchResultScreen renders a loading spinner from:
    // Streaming + zero (filtered) items + at least one provider SEARCHING.
    // Before the fix this window rendered NOTHING (a blank body) — the exact
    // "looks like the app has hung" defect the operator's QA video caught.
    @Test
    fun streaming_search_shows_loading_indicator_while_provider_in_flight() =
        runTest(mainDispatcherRule.testDispatcher) {
            val slow = SlowSearchClient(descriptor("p1"))
            val vm = createViewModel(providerIds = listOf("p1"), clients = listOf(slow))

            vm.test(this) {
                runOnCreate()

                // §6.J primary — mid-flight state, captured BEFORE the fake
                // backend responds. This is exactly the state the render
                // predicate must classify as "show the spinner".
                val midFlight = vm.container.stateFlow.value
                assertTrue(
                    "a fresh multi-provider search must be in the Streaming state while a " +
                        "provider is still in flight, was ${midFlight.searchContent::class.simpleName}",
                    midFlight.searchContent is SearchResultContent.Streaming,
                )
                assertTrue(
                    "no items have arrived yet — streamingFilteredItems must be empty",
                    midFlight.streamingFilteredItems.isEmpty(),
                )
                assertTrue(
                    "LVA-086: the render predicate the screen consumes MUST report true while a " +
                        "provider is still SEARCHING and zero items have arrived — this is the exact " +
                        "condition that rendered a blank body for ~25s before the fix",
                    midFlight.showsStreamingLoadingIndicator,
                )

                slow.release()
                cancelAndIgnoreRemainingItems()
            }

            // Sanity — the search does complete once released (proves the
            // fake itself is not permanently stuck and the mid-flight
            // assertion above was measuring a real transient state, not a
            // dead end).
            val finalState = vm.container.stateFlow.value
            assertTrue(
                "after release the stream must reach a terminal state (Empty, no items returned), " +
                    "was ${finalState.searchContent::class.simpleName}",
                finalState.searchContent is SearchResultContent.Empty,
            )
            assertFalse(
                "a terminal Empty state must NOT report the streaming-loading predicate",
                finalState.showsStreamingLoadingIndicator,
            )
        }

    // CHALLENGE
    //
    // §6.J primary — once every provider reaches a terminal state with zero
    // results, the loading-eligible window MUST end and the screen MUST
    // render the dedicated Empty ("Nothing found") placeholder — not stay
    // stuck showing a spinner forever. Proves the Loading state is not a
    // trap; it always resolves.
    @Test
    fun streaming_search_downgrades_from_loading_to_empty_when_no_results_arrive() =
        runTest(mainDispatcherRule.testDispatcher) {
            val p1 = FixedResultClient(descriptor("p1"), emptyList())
            val p2 = FixedResultClient(descriptor("p2"), emptyList())
            val vm = createViewModel(providerIds = listOf("p1", "p2"), clients = listOf(p1, p2))

            vm.test(this) {
                runOnCreate()
                cancelAndIgnoreRemainingItems()
            }

            val finalState = vm.container.stateFlow.value
            assertEquals(
                "zero results across every provider must render Empty, not Content or a stuck Streaming",
                SearchResultContent.Empty,
                finalState.searchContent,
            )
            assertFalse(
                "Empty is a terminal state — the loading predicate must be false, otherwise the " +
                    "screen would render BOTH the empty-state illustration AND a spinner",
                finalState.showsStreamingLoadingIndicator,
            )
        }

    // CHALLENGE
    //
    // §6.AB discrimination — once the FIRST provider's items have arrived,
    // the screen already has real content to show the user; the spinner
    // must NOT keep rendering underneath it just because a second provider
    // is still in flight. Proves showsStreamingLoadingIndicator does not
    // over-fire (a naive "any provider SEARCHING" predicate without the
    // items-empty guard would wrongly keep the spinner visible here).
    @Test
    fun streaming_search_hides_loading_indicator_once_first_provider_items_arrive() =
        runTest(mainDispatcherRule.testDispatcher) {
            val fast = FixedResultClient(descriptor("fast"), listOf(item("fast", "t1", "Prince Album")))
            val slow = SlowSearchClient(descriptor("slow"))
            val vm = createViewModel(providerIds = listOf("fast", "slow"), clients = listOf(fast, slow))

            vm.test(this) {
                runOnCreate()

                val midFlight = vm.container.stateFlow.value
                assertTrue(
                    "the fast provider's item must already be visible while the slow provider " +
                        "is still SEARCHING, was ${midFlight.streamingFilteredItems.map { it.topic.title }}",
                    midFlight.streamingFilteredItems.isNotEmpty(),
                )
                assertFalse(
                    "once real items are visible the loading spinner must not also render " +
                        "underneath them",
                    midFlight.showsStreamingLoadingIndicator,
                )

                slow.release()
                cancelAndIgnoreRemainingItems()
            }
        }
}

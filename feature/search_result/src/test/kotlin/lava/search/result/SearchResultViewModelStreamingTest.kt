package lava.search.result

import androidx.lifecycle.SavedStateHandle
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.orbitmvi.orbit.test.test
import kotlin.reflect.KClass

/**
 * Integration Challenge for the multi-provider STREAMING search flow in
 * [SearchResultViewModel] — the user-felt path when a search carries an
 * explicit set of `providerIds` (the SP-4 Phase D / 2026-06-14 search
 * fix). This is the path a real user takes after selecting one or more
 * providers in the search-input chip bar.
 *
 * ## Why this is a real gap (not covered elsewhere)
 *
 * - [ApplyMultiSearchEventTest] tests ONLY the pure file-scope reducer
 *   `applyMultiSearchEvent(state, event)` — it never constructs the
 *   ViewModel and never exercises the `onCreate` dispatch or the
 *   `handleStreamEnd()` Streaming→Content/Empty downgrade.
 * - [SearchResultViewModelFallbackTest] constructs the ViewModel with an
 *   EMPTY-registry SDK and `providerIds == null`, so it ONLY drives the
 *   legacy `observePagingData()` path. The streaming branch
 *   (`observeStreamMultiSearch`) is never entered.
 *
 * So the VM-level orchestration of the multi-provider search — onCreate
 * routing on a non-null `providerIds`, the initial `Streaming` state with
 * one `SEARCHING` row per provider, the real fan-out through
 * `LavaTrackerSdk.streamMultiSearch`, and the terminal
 * `handleStreamEnd()` downgrade — had no VM test until this one.
 *
 * ## Real-stack wiring (Second/Third Law)
 *
 * The SUT is the REAL [SearchResultViewModel] driven by a REAL
 * [LavaTrackerSdk] over a REAL [DefaultTrackerRegistry]. Only the
 * outermost network boundary is faked: each provider's `search()`
 * returns an in-memory [SearchResult] instead of hitting the wire. The
 * SDK's fan-out, per-provider event emission, and the VM's reducer +
 * stream-end downgrade are all production code. No UseCase and no SDK
 * method is mocked; the four paging-graph UseCases use named real fakes
 * (the streaming path does not consult them, asserted via fake counters).
 *
 * ## Test classification
 * CHALLENGE — primary assertion on the user-visible rendered content
 * branch ([SearchResultContent.Content] vs [SearchResultContent.Empty])
 * and the items list the search-result screen renders.
 *
 * ## Bluff-Audit
 * See commit body for the mutation + observed-failure record.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchResultViewModelStreamingTest {

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

    /** Fake tracker client whose `search` returns fixed in-memory results. */
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

    // The `pids` query-param SearchResultNavigation serializes providerIds
    // into; SavedStateHandle.filter deserializes it back (comma-joined).
    private val providerIdsKey = "pids"
    private val queryKey = "nm"

    // --- named real fakes for the legacy paging-graph (NOT exercised by
    //     the streaming path; their counters prove that fact). ----------

    private class FakeObserveSearchPagingDataUseCase : ObserveSearchPagingDataUseCase {
        var invocations = 0
            private set
        override fun invoke(
            filterFlow: Flow<Filter>,
            actionsFlow: Flow<PagingAction>,
            scope: CoroutineScope,
        ): Flow<PagingData<List<TopicModel<Torrent>>>> {
            invocations += 1
            return flowOf()
        }
    }

    private class FakeAddSearchHistoryUseCase : AddSearchHistoryUseCase {
        val recordedFilters = mutableListOf<Filter>()
        override suspend fun invoke(filter: Filter) {
            recordedFilters += filter
        }
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

    private lateinit var pagingFake: FakeObserveSearchPagingDataUseCase
    private lateinit var addHistoryFake: FakeAddSearchHistoryUseCase

    /**
     * Captures §6.AC telemetry so the per-provider-failure recording path can
     * be asserted. NOT a mock of the SUT (the SUT is the real ViewModel+SDK);
     * [AnalyticsTracker] is the outermost telemetry boundary — the legitimate
     * fakeable seam below the SUT.
     */
    private class RecordingAnalytics : AnalyticsTracker {
        data class NonFatal(val throwable: Throwable, val context: Map<String, String>)
        data class Warning(val message: String, val context: Map<String, String>)

        val nonFatals = mutableListOf<NonFatal>()
        val warnings = mutableListOf<Warning>()

        override fun event(name: String, params: Map<String, String>) {}
        override fun setUserId(userId: String?) {}
        override fun setProperty(key: String, value: String?) {}
        override fun recordNonFatal(throwable: Throwable, context: Map<String, String>) {
            nonFatals += NonFatal(throwable, context)
        }
        override fun recordWarning(message: String, context: Map<String, String>) {
            warnings += Warning(message, context)
        }
        override fun log(message: String) {}
    }

    /**
     * A tracker client whose `search()` THROWS exactly like the real
     * [lava.tracker.client.ApiBackedTrackerClient.getString] does on a non-2xx
     * response — `error("API request failed: HTTP <code> for <url>")`. This is
     * the production failure surface that produced the operator-reported
     * "Something went wrong" release search bug (2026-06-22).
     */
    private class FailingClient(
        override val descriptor: TrackerDescriptor,
        private val message: String,
    ) : TrackerClient {
        override suspend fun healthCheck(): Boolean = true
        override fun close() {}

        @Suppress("UNCHECKED_CAST")
        override fun <T : TrackerFeature> getFeature(featureClass: KClass<T>): T? = when (featureClass) {
            SearchableTracker::class -> object : SearchableTracker {
                override suspend fun search(request: SearchRequest, page: Int): SearchResult =
                    error(message)
            } as T
            else -> null
        }
    }

    private fun createViewModel(
        providerIds: List<String>,
        clients: List<TrackerClient>,
        analytics: AnalyticsTracker = RecordingAnalytics(),
    ): SearchResultViewModel {
        pagingFake = FakeObserveSearchPagingDataUseCase()
        addHistoryFake = FakeAddSearchHistoryUseCase()
        val registry = DefaultTrackerRegistry()
        clients.forEach { registry.register(factoryFor(it)) }
        val savedState = SavedStateHandle(
            mapOf(
                queryKey to "ubuntu",
                providerIdsKey to providerIds.joinToString(","),
            ),
        )
        return SearchResultViewModel(
            savedStateHandle = savedState,
            loggerFactory = TestLoggerFactory(),
            observeSearchPagingDataUseCase = pagingFake,
            addSearchHistoryUseCase = addHistoryFake,
            enrichFilterUseCase = FakeEnrichFilterUseCase(),
            toggleFavoriteUseCase = FakeToggleFavoriteUseCase(),
            observeAuthStateUseCase = FakeObserveAuthStateUseCase(),
            observeSettingsUseCase = ObserveSettingsUseCase(TestSettingsRepository()),
            analytics = analytics,
            sdk = LavaTrackerSdk(registry = registry),
        )
    }

    // CHALLENGE
    @Test
    fun streaming_search_with_results_downgrades_to_Content_carrying_every_providers_items() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = createViewModel(
                providerIds = listOf("p1", "p2"),
                clients = listOf(
                    FixedResultClient(descriptor("p1"), listOf(item("p1", "t1", "Ubuntu ISO"))),
                    FixedResultClient(descriptor("p2"), listOf(item("p2", "t2", "Debian ISO"))),
                ),
            )

            vm.test(this) {
                runOnCreate()
                cancelAndIgnoreRemainingItems()
            }

            val content = vm.container.stateFlow.value.searchContent
            // §6.J primary — after the stream completes, handleStreamEnd MUST
            // downgrade Streaming -> Content (the result list the user sees).
            assertTrue(
                "search with results must render Content, was ${content::class.simpleName}",
                content is SearchResultContent.Content,
            )
            val titles = (content as SearchResultContent.Content).torrents.map { it.topic.title }.sorted()
            assertEquals(listOf("Debian ISO", "Ubuntu ISO"), titles)
            // The streaming path must NOT touch the legacy single-tracker
            // paging path (the bug the 2026-06-14 fix closed: GoApi searches
            // falling through to observePagingData and 404ing).
            assertEquals(
                "streaming search must not invoke the legacy paging use-case",
                0,
                pagingFake.invocations,
            )
        }

    // CHALLENGE
    @Test
    fun streaming_search_with_no_results_downgrades_to_Empty_not_Content() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = createViewModel(
                providerIds = listOf("p1", "p2"),
                clients = listOf(
                    FixedResultClient(descriptor("p1"), emptyList()),
                    FixedResultClient(descriptor("p2"), emptyList()),
                ),
            )

            vm.test(this) {
                runOnCreate()
                cancelAndIgnoreRemainingItems()
            }

            val content = vm.container.stateFlow.value.searchContent
            // §6.J primary — zero items across all providers MUST render the
            // Empty ("Nothing found") state, never an empty Content list.
            assertEquals(SearchResultContent.Empty, content)
        }

    // CHALLENGE + §6.AC — regression for the 2026-06-22 operator-reported
    // release search failure. The api-app search surfaces a non-2xx response
    // as ApiBackedTrackerClient.getString's `error("API request failed: HTTP
    // <code> for <url>")`, which the SDK turns into a ProviderFailure. The VM's
    // collector previously dropped that cause (`-> Unit`), so the user saw
    // "Something went wrong" with NO captured reason — undiagnosable on a
    // release build (no Chucker, cause logged nowhere). This asserts the cause
    // now reaches telemetry with the failing provider + feature context.
    //
    // Falsifiability (Sixth Law clause 2): revert the production
    // `is ProviderFailure -> recordProviderFailure(event)` arm back to `-> Unit`
    // and this test fails — `analytics.nonFatals` is empty, so
    // `assertEquals(1, analytics.nonFatals.size)` throws
    // "a per-provider search failure must be recorded exactly once
    //  expected:<1> but was:<0>".
    @Test
    fun streaming_search_provider_failure_records_http_cause_to_telemetry() =
        runTest(mainDispatcherRule.testDispatcher) {
            val analytics = RecordingAnalytics()
            val httpFailure = "API request failed: HTTP 401 for https://p1.test/v1/p1/search"
            val vm = createViewModel(
                providerIds = listOf("p1"),
                clients = listOf(FailingClient(descriptor("p1"), httpFailure)),
                analytics = analytics,
            )

            vm.test(this) {
                runOnCreate()
                cancelAndIgnoreRemainingItems()
            }

            // §6.AC load-bearing — the failing provider's cause (the REAL HTTP
            // status string from the api client) must reach the non-fatal feed
            // exactly once, with the provider + feature context, so an operator
            // can pin the failure mode remotely instead of guessing.
            assertEquals(
                "a per-provider search failure must be recorded exactly once",
                1,
                analytics.nonFatals.size,
            )
            val recorded = analytics.nonFatals.single()
            assertTrue(
                "recorded cause must carry the HTTP status from getString, was: ${recorded.throwable.message}",
                recorded.throwable.message?.contains("HTTP 401") == true,
            )
            assertEquals("p1", recorded.context[AnalyticsTracker.Params.PROVIDER])
            assertEquals("search", recorded.context[AnalyticsTracker.Params.FEATURE])

            // User-visible consequence — the only provider failed, so the user
            // gets nothing: Empty, never a misleading Content with results.
            assertEquals(
                SearchResultContent.Empty,
                vm.container.stateFlow.value.searchContent,
            )
        }
}

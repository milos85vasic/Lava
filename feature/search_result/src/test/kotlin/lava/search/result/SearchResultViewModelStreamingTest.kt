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
import lava.tracker.api.RemoteTrackerDescriptor
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.api.TrackerFeature
import lava.tracker.api.feature.SearchableTracker
import lava.tracker.api.model.SearchRequest
import lava.tracker.api.model.SearchResult
import lava.tracker.api.model.SortField
import lava.tracker.api.model.SortOrder
import lava.tracker.api.model.TimePeriod
import lava.tracker.api.model.TorrentItem
import lava.tracker.client.ApiHttpException
import lava.tracker.client.LavaTrackerSdk
import lava.tracker.registry.DefaultTrackerRegistry
import lava.tracker.registry.TrackerClientFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    /**
     * Same shape as [descriptor] but with an EXPLICIT [displayName], distinct
     * from the raw [id], so LVA-085 tests can assert the resolved chip label
     * is the human-readable name — not merely "some non-raw string" — and so
     * a mutation that falls back to the raw id is unambiguously distinguishable.
     */
    private fun descriptorNamed(id: String, name: String): TrackerDescriptor = object : TrackerDescriptor {
        override val trackerId: String = id
        override val displayName: String = name
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

    /**
     * Capturing tracker client whose `search()` records the [SearchRequest] and
     * [page] so a test can assert what the ViewModel sent to the SDK.
     *
     * This is the Bug 1 discrimination test's key difference from
     * [FixedResultClient]: FixedResultClient throws away the request, so asserting
     * the VM propagated the user's sort/order/period choice is impossible.
     * CapturingClient enables the primary assertion — "the SearchRequest the
     * ViewModel constructed carries the non-default filter params the user selected".
     *
     * Anti-Bluff (§6.J): this is NOT a mock of the SUT (the SUT is the real
     * ViewModel + real LavaTrackerSdk + real DefaultTrackerRegistry). CapturingClient
     * only replaces the outermost network boundary, exactly as MockWebServer does
     * in ApiBackedTrackerClientTest. The VM + SDK + registry are all real production
     * code.
     */
    private class CapturingClient(
        override val descriptor: TrackerDescriptor,
    ) : TrackerClient {
        var lastRequest: SearchRequest? = null
        var lastPage: Int? = null

        override suspend fun healthCheck(): Boolean = true
        override fun close() {}

        @Suppress("UNCHECKED_CAST")
        override fun <T : TrackerFeature> getFeature(featureClass: KClass<T>): T? = when (featureClass) {
            SearchableTracker::class -> object : SearchableTracker {
                override suspend fun search(request: SearchRequest, page: Int): SearchResult {
                    lastRequest = request
                    lastPage = page
                    return SearchResult(items = emptyList(), totalPages = 1, currentPage = page)
                }
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
    private val sortKey = "o"
    private val orderKey = "s"
    private val periodKey = "tm"

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
     * A tracker client whose `search()` throws [ApiHttpException] exactly as the
     * real [lava.tracker.client.ApiBackedTrackerClient.getString] now does on a
     * non-2xx response. Using the typed exception lets the test assert on the
     * NEW structured keys ([AnalyticsTracker.Params.HTTP_STATUS], etc.) that
     * [SearchResultViewModel.recordProviderFailure] adds via the
     * `is ApiHttpException` branch (§6.AC enrichment).
     *
     * §6.J falsifiability: if [SearchResultViewModel.recordProviderFailure] drops
     * the `is ApiHttpException` branch, the four new context-key assertions below
     * fail ("expected 'http_status' not present in context").
     */
    private class FailingClient(
        override val descriptor: TrackerDescriptor,
        private val statusCode: Int = 401,
        private val requestUrl: String = "https://p1.test/v1/p1/search",
    ) : TrackerClient {
        override suspend fun healthCheck(): Boolean = true
        override fun close() {}

        @Suppress("UNCHECKED_CAST")
        override fun <T : TrackerFeature> getFeature(featureClass: KClass<T>): T? = when (featureClass) {
            SearchableTracker::class -> object : SearchableTracker {
                override suspend fun search(request: SearchRequest, page: Int): SearchResult =
                    throw ApiHttpException(
                        statusCode = statusCode,
                        requestUrl = requestUrl,
                        httpMethod = "GET",
                        responseSnippet = null,
                        message = "API request failed: HTTP $statusCode for $requestUrl",
                    )
            } as T
            else -> null
        }
    }

    private fun createViewModel(
        providerIds: List<String>,
        clients: List<TrackerClient>,
        analytics: AnalyticsTracker = RecordingAnalytics(),
        sortParam: String? = null,
        orderParam: String? = null,
        periodParam: String? = null,
        // LVA-093: defaults to already-ready so every EXISTING test in this
        // file (none of which target the cold-start race) keeps its prior
        // unblocked-immediately behavior. The two LVA-093 tests below pass an
        // explicit NOT-ready gate.
        providersReadyGate: StartupProvidersGate = StartupProvidersGate().apply { markReady() },
    ): SearchResultViewModel {
        pagingFake = FakeObserveSearchPagingDataUseCase()
        addHistoryFake = FakeAddSearchHistoryUseCase()
        val registry = DefaultTrackerRegistry()
        clients.forEach { registry.register(factoryFor(it)) }
        val entries = mutableMapOf<String, Any?>(
            queryKey to "ubuntu",
            providerIdsKey to providerIds.joinToString(","),
        )
        sortParam?.let { entries[sortKey] = it }
        orderParam?.let { entries[orderKey] = it }
        periodParam?.let { entries[periodKey] = it }
        val savedState = SavedStateHandle(entries)
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
            providersReadyGate = providersReadyGate,
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
    // as ApiBackedTrackerClient.getString's ApiHttpException, which the SDK turns
    // into a ProviderFailure. This test asserts the typed cause reaches Crashlytics
    // with the NEW structured HTTP diagnostic keys (§6.AC enrichment) so an
    // operator can triage the HTTP layer remotely without credentials.
    //
    // Falsifiability (Sixth Law clause 2):
    //  Mutation 1 — revert `is ProviderFailure -> recordProviderFailure(event)` to
    //               `-> Unit`  → `analytics.nonFatals.size == 0`, fails at
    //               "expected:<1> but was:<0>".
    //  Mutation 2 — remove `is ApiHttpException` branch in recordProviderFailure
    //               → http_status / request_url / http_method / base_url_host
    //               keys absent from context, fails at the four assertEquals below.
    //  Mutation 3 — flip FailingClient to throw bare `error()` instead of
    //               ApiHttpException → same as Mutation 2 (branch not entered).
    //  All three mutations revert cleanly; test passes green on unmodified code.
    //
    // Bluff-Audit:
    //   Mutation: removed `is ApiHttpException` branch from recordProviderFailure,
    //     fell back to baseContext only.
    //   Observed-Failure: assertEquals("401", recorded.context["http_status"])
    //     → AssertionError: expected:<401> but was:<null>
    //   Reverted: yes
    @Test
    fun streaming_search_provider_failure_records_http_cause_to_telemetry() =
        runTest(mainDispatcherRule.testDispatcher) {
            val analytics = RecordingAnalytics()
            val vm = createViewModel(
                providerIds = listOf("p1"),
                clients = listOf(FailingClient(descriptor("p1"), statusCode = 401)),
                analytics = analytics,
            )

            vm.test(this) {
                runOnCreate()
                cancelAndIgnoreRemainingItems()
            }

            // §6.AC load-bearing — the failing provider's ApiHttpException must
            // reach the non-fatal feed exactly once, enriched with HTTP diagnostic
            // context, so an operator can triage the failure remotely.
            assertEquals(
                "a per-provider search failure must be recorded exactly once",
                1,
                analytics.nonFatals.size,
            )
            val recorded = analytics.nonFatals.single()

            // Base context — provider + feature always present (prior assertions).
            assertTrue(
                "recorded cause must carry the HTTP status in its message, was: ${recorded.throwable.message}",
                recorded.throwable.message?.contains("HTTP 401") == true,
            )
            assertEquals("p1", recorded.context[AnalyticsTracker.Params.PROVIDER])
            assertEquals("search", recorded.context[AnalyticsTracker.Params.FEATURE])

            // NEW §6.AC structured HTTP context — the four keys that make the
            // Crashlytics non-fatal record actionable (not just "something failed").
            assertEquals(
                "http_status must carry the numeric HTTP code from ApiHttpException",
                "401",
                recorded.context[AnalyticsTracker.Params.HTTP_STATUS],
            )
            assertEquals(
                "request_url must carry the query-stripped URL from ApiHttpException",
                "https://p1.test/v1/p1/search",
                recorded.context[AnalyticsTracker.Params.REQUEST_URL],
            )
            assertEquals(
                "http_method must carry the HTTP verb from ApiHttpException",
                "GET",
                recorded.context[AnalyticsTracker.Params.HTTP_METHOD],
            )
            assertEquals(
                "base_url_host must carry the backend host extracted from requestUrl",
                "p1.test",
                recorded.context[AnalyticsTracker.Params.BASE_URL_HOST],
            )

            // User-visible consequence — the only provider failed, so the user
            // sees the Error state WITH a working Retry affordance, NOT a
            // misleading "Nothing found" Empty.
            val finalContent = vm.container.stateFlow.value.searchContent
            assertTrue(
                "a fully-failed stream must render Error (Retry affordance), was $finalContent",
                finalContent is SearchResultContent.Error,
            )
        }

    // CHALLENGE — Bug 1 regression: filter sort/order/period MUST propagate
    // through the ViewModel into the SDK's SearchRequest. Before the fix, the
    // URL builder always sent SortField.DATE / SortOrder.DESCENDING regardless
    // of the user's selection, making sort/order toggles and period filters
    // silently inoperative.
    //
    // Primary assertion: the SearchRequest the real ViewModel constructs (via
    // SavedStateHandle.filter → observeStreamMultiSearch → mapping extensions)
    // carries the non-default sort/order/period the SavedStateHandle stores.
    // This is the user-visible state at the SDK contract boundary.
    //
    // FALSIFIABILITY (§6.J / Sixth Law clause 2):
    //  - Remove the `sort = filter.sort.toSortField()` assignment in
    //    observeStreamMultiSearch → CapturingClient.lastRequest!!.sort is
    //    SortField.DATE (the default), not SortField.TITLE → test fails:
    //    "expected:<TITLE> but was:<DATE>".
    //  - Revert the mutation → test passes.
    //  - Same protocol for `sortOrder = filter.order.toSortOrder()` and
    //    `period = filter.period.toTimePeriod()` individually.
    @Test
    fun streaming_search_propagatesSortOrderPeriod_fromSavedState_toSdkRequest() =
        runTest(mainDispatcherRule.testDispatcher) {
            val capturing = CapturingClient(descriptor("p1"))
            val vm = createViewModel(
                providerIds = listOf("p1"),
                clients = listOf(capturing),
                // SavedStateHandle.filter deserializes the nav-param codes:
                // SortKey "o" → Sort.TITLE (code "2")
                // OrderKey "s" → Order.ASCENDING (code "1")
                // PeriodKey "tm" → Period.LAST_WEEK (code "7")
                sortParam = "2",
                orderParam = "1",
                periodParam = "7",
            )

            vm.test(this) {
                runOnCreate()
                cancelAndIgnoreRemainingItems()
            }

            val request = capturing.lastRequest
                ?: error("CapturingClient.search was never called — streaming path did not execute")

            // §6.J primary — every non-default filter field the user selected
            // through the UI MUST match in the SDK contract.
            assertEquals(
                "Sort.TITLE from nav params must propagate to SearchRequest.sort",
                SortField.TITLE,
                request.sort,
            )
            assertEquals(
                "Order.ASCENDING from nav params must propagate to SearchRequest.sortOrder",
                SortOrder.ASCENDING,
                request.sortOrder,
            )
            assertEquals(
                "Period.LAST_WEEK from nav params must propagate to SearchRequest.period",
                TimePeriod.LAST_WEEK,
                request.period,
            )
            // The default fields must still pass through unchanged.
            assertEquals("ubuntu", request.query)
        }

    // CHALLENGE — LVA-085 regression (2026-06-25 QA video #4, frames
    // 0060/0125/0130: results filter chips rendered raw provider ids like
    // "archiveorg"/"torrentdownloads"/"kinozal"/"yts" instead of their
    // display names). `SearchResultScreen.ProviderFilterChipBar` renders
    // from `state.providerDisplayNames[pid] ?: pid` the INSTANT
    // `filterProviderChipIds` (sourced from `filter.providerIds`) is
    // non-empty — i.e. on the FIRST composed frame of the `Streaming`
    // state. Before the fix, `providerDisplayNames` started as `emptyMap()`
    // and was populated ONLY as async `MultiSearchEvent.ProviderStart`
    // events streamed in (one per provider), so that first frame always
    // fell back to the raw id. This test asserts the display name is
    // ALREADY resolved on the very first `Streaming` state emission —
    // i.e. before ANY `ProviderStart` event could possibly have been
    // applied — which is only true if the resolution happens eagerly
    // (this fix) rather than reactively (the pre-fix behavior).
    //
    // FALSIFIABILITY (§6.J / Sixth Law clause 2 — PERFORMED):
    //  Mutation: reverted the seed `reduce` in
    //    `SearchResultViewModel.observeStreamMultiSearch` back to the
    //    pre-fix shape (`providerDisplayNames` left untouched,
    //    `ProviderStreamStatus.displayName = pid`).
    //  Observed-Failure: `AssertionError: the results filter chip's display
    //    name MUST be resolved on the very first Streaming state, before
    //    any ProviderStart event — NOT fall back to the raw provider id
    //    expected:<Archive.org> but was:<null>`
    //  Reverted: yes — re-applied the fix, test passes again.
    @Test
    fun streaming_search_seeds_providerDisplayNames_on_first_Streaming_state_before_any_ProviderStart_event() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = createViewModel(
                providerIds = listOf("archiveorg"),
                clients = listOf(
                    FixedResultClient(
                        descriptor = descriptorNamed("archiveorg", "Archive.org"),
                        items = listOf(item("archiveorg", "t1", "Free Book")),
                    ),
                ),
            )

            vm.test(this) {
                runOnCreate()

                // Pump state emissions until the first Streaming state (the
                // one produced by observeStreamMultiSearch's seed `reduce`,
                // strictly BEFORE any per-provider ProviderStart/ProviderResults
                // event has a chance to be applied) — this is exactly the
                // state a real user's first composed frame reads from.
                var seedState: SearchPageState? = null
                var guard = 0
                while (seedState == null && guard < 20) {
                    val next = awaitState()
                    if (next.searchContent is SearchResultContent.Streaming) {
                        seedState = next
                    }
                    guard += 1
                }
                val streamingSeed = seedState
                    ?: error("Streaming state was never emitted — streaming path did not execute")

                // §6.J primary — user-visible state: the results filter chip's
                // resolved label on the VERY FIRST frame the user sees.
                assertEquals(
                    "the results filter chip's display name MUST be resolved on the " +
                        "very first Streaming state, before any ProviderStart event " +
                        "— NOT fall back to the raw provider id",
                    "Archive.org",
                    streamingSeed.providerDisplayNames["archiveorg"],
                )

                cancelAndIgnoreRemainingItems()
            }
        }

    // =============== LVA-085 x LVA-093 COMPOSITION (2026-08-13) ===============
    //
    // Reproduces the operator's "nothing has been fixed" report. LVA-085 and
    // LVA-093 each shipped with a passing, isolated unit test — but neither
    // test exercised the ACTUAL cold-start composition: LVA-085's display-name
    // resolution (`sdk.listAvailableTrackers()`) originally ran BEFORE
    // LVA-093's readiness wait (`providersReadyGate.awaitReady()`), so on a
    // genuine cold start — before `RepopulateProvidersOnStartupUseCase` has
    // installed the dynamic providers via `TrackerRegistry.populateFrom` —
    // the display-name read silently saw the stale bundled registry and fell
    // back to the raw provider id, reintroducing exactly the bug LVA-085
    // claimed to fix. Invisible to both fixes' own tests because each test's
    // default fixture pre-marks the gate ready before exercising its own
    // concern in isolation. This test drives both concerns TOGETHER: the gate
    // starts NOT ready, and the registry's authoritative display name only
    // becomes available at the SAME moment the gate opens — mirroring
    // `RepopulateProvidersOnStartupUseCase`'s real sequence
    // (`trackerRegistry.populateFrom(descriptors)` then, in its `finally`
    // block, `providersReadyGate.markReady()`).
    //
    // ## FALSIFIABILITY REHEARSAL (§6.J clause 2 / Sixth Law clause 2 — PERFORMED)
    //
    //   Mutation: moved `providersReadyGate.awaitReady()` in
    //     SearchResultViewModel.observeStreamMultiSearch back to AFTER the
    //     `resolvedDisplayNames` computation + seed `reduce` (the pre-fix
    //     ordering).
    //   Observed-Failure: `streaming search resolves the correct display name
    //     only after the cold-start registry catches up` FAILED —
    //     `AssertionError: the results filter chip MUST show the registry's
    //     resolved display name once it becomes available — NOT the stale
    //     bundled fallback expected:<Archive.org> but was:<archiveorg>` — the
    //     raw id leaked through, proving the composition race is back.
    //   Reverted: yes — restoring the `awaitReady()`-before-resolution
    //     ordering makes this test pass again.
    @Test
    fun `streaming search resolves the correct display name only after the cold-start registry catches up`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Bundled fallback: "archiveorg" is registered but its display name is
            // UNRESOLVED (the same shape `resolvedDisplayNames[pid] ?: pid` falls
            // back to) — the state the registry is in BEFORE the cold-start
            // dynamic-provider repopulation completes.
            val capturing = CapturingClient(descriptorNamed("archiveorg", "archiveorg"))
            val registry = DefaultTrackerRegistry()
            registry.register(factoryFor(capturing))
            // Any dynamic (API-backed) client populateFrom would install resolves
            // back to the SAME capturing instance, so the eventual search stays
            // observable through populateFrom's client swap.
            registry.setApiClientFactory { capturing }

            val gate = StartupProvidersGate() // deliberately NOT marked ready
            pagingFake = FakeObserveSearchPagingDataUseCase()
            addHistoryFake = FakeAddSearchHistoryUseCase()
            val savedState = SavedStateHandle(
                mutableMapOf<String, Any?>(
                    queryKey to "ubuntu",
                    providerIdsKey to "archiveorg",
                ),
            )
            val vm = SearchResultViewModel(
                savedStateHandle = savedState,
                loggerFactory = TestLoggerFactory(),
                observeSearchPagingDataUseCase = pagingFake,
                addSearchHistoryUseCase = addHistoryFake,
                enrichFilterUseCase = FakeEnrichFilterUseCase(),
                toggleFavoriteUseCase = FakeToggleFavoriteUseCase(),
                observeAuthStateUseCase = FakeObserveAuthStateUseCase(),
                observeSettingsUseCase = ObserveSettingsUseCase(TestSettingsRepository()),
                analytics = RecordingAnalytics(),
                sdk = LavaTrackerSdk(registry = registry),
                providersReadyGate = gate,
            )

            vm.test(this) {
                runOnCreate()

                // §6.J primary #1 — the search MUST NOT have resolved anything yet;
                // the gate is not ready.
                assertNull(
                    "the tracker client MUST NOT be touched before the cold-start " +
                        "readiness gate opens",
                    capturing.lastRequest,
                )

                // Simulate RepopulateProvidersOnStartupUseCase's real sequence:
                // install the dynamic descriptor (the authoritative display name)
                // THEN mark the gate ready.
                registry.populateFrom(
                    listOf(
                        RemoteTrackerDescriptor(
                            trackerId = "archiveorg",
                            displayName = "Archive.org",
                            baseUrls = listOf(
                                MirrorUrl(
                                    url = "https://archiveorg.test",
                                    isPrimary = true,
                                    priority = 0,
                                    protocol = Protocol.HTTPS,
                                ),
                            ),
                            capabilities = setOf(TrackerCapability.SEARCH),
                            authType = AuthType.NONE,
                            encoding = "UTF-8",
                        ),
                    ),
                )
                gate.markReady()
                mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

                var streamingState: SearchPageState? = null
                var guard = 0
                while (streamingState == null && guard < 20) {
                    val next = awaitState()
                    if (next.searchContent is SearchResultContent.Streaming) {
                        streamingState = next
                    }
                    guard += 1
                }
                val state = streamingState
                    ?: error("Streaming state was never emitted after the gate opened")

                // §6.J primary #2 — user-visible state: the chip label MUST reflect
                // the registry's resolved display name, not the stale bundled
                // fallback.
                assertEquals(
                    "the results filter chip MUST show the registry's resolved " +
                        "display name once it becomes available — NOT the stale " +
                        "bundled fallback",
                    "Archive.org",
                    state.providerDisplayNames["archiveorg"],
                )

                cancelAndIgnoreRemainingItems()
            }

            // §6.J secondary — the search itself still genuinely executed against
            // the (same, populateFrom-resolved) client once the gate opened.
            assertEquals("ubuntu", capturing.lastRequest?.query)
        }

    // ======================= LVA-093 (cold-start race) =======================
    //
    // Reproduces the operator's report: a search fired immediately after app
    // launch, before the cold-start dynamic-provider repopulation coroutine
    // has finished, must NOT silently resolve against whatever tracker client
    // happens to be registered at that instant. [SearchResultViewModel
    // .observeStreamMultiSearch] now awaits [StartupProvidersGate.awaitReady]
    // BEFORE calling `sdk.streamMultiSearch(...)` — these tests prove that
    // wiring against the REAL ViewModel + REAL SDK + REAL registry, using the
    // same [CapturingClient] technique the sort/order/period test above uses
    // to observe exactly when `search()` is (or is not yet) invoked.
    //
    // ## FALSIFIABILITY REHEARSAL (§6.J clause 2 / Sixth Law clause 2)
    //
    //   Mutation: in SearchResultViewModel.observeStreamMultiSearch, delete
    //     the `providersReadyGate.awaitReady()` line.
    //   Observed-Failure:
    //     `streaming search does not resolve the tracker client before the
    //     readiness gate opens` FAILS at
    //     `assertNull("the tracker client MUST NOT be touched before the
    //     readiness gate opens", capturing.lastRequest)` →
    //     AssertionError: expected null but was SearchRequest(query=ubuntu...)
    //     — the client is called immediately, proving the race is back.
    //   Reverted: yes — restoring the `awaitReady()` call makes both tests
    //     below pass again.

    // CHALLENGE — the load-bearing anti-bluff proof: while the gate is NOT
    // ready, the tracker client is genuinely untouched; once markReady() is
    // called, the SAME already-running intent proceeds and calls it.
    @Test
    fun `streaming search does not resolve the tracker client before the readiness gate opens`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val capturing = CapturingClient(descriptor("p1"))
            val gate = StartupProvidersGate() // deliberately NOT marked ready
            val vm = createViewModel(
                providerIds = listOf("p1"),
                clients = listOf(capturing),
                providersReadyGate = gate,
            )

            vm.test(this) {
                runOnCreate()

                // §6.J primary #1 — with UnconfinedTestDispatcher the intent
                // already ran eagerly up to the awaitReady() suspension point,
                // so by this line the tracker client MUST NOT have been
                // touched yet — proving the race is closed, not just delayed.
                assertNull(
                    "the tracker client MUST NOT be resolved/called before " +
                        "the cold-start readiness gate opens — was ${capturing.lastRequest}",
                    capturing.lastRequest,
                )

                // The cold-start repopulation concludes — let the now-unblocked
                // coroutine run to completion (TestCoroutineScheduler.advanceUntilIdle
                // is the stable, dispatcher-agnostic way to drain queued
                // continuations; the same technique CancelTimeoutTest uses via
                // advanceTimeBy for its own timeout scenario).
                gate.markReady()
                mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()
                cancelAndIgnoreRemainingItems()
            }

            // §6.J primary #2 — AFTER the gate opens, the SAME search proceeds
            // and the client is genuinely invoked with the user's real query.
            val request = capturing.lastRequest
                ?: error("CapturingClient.search was never called after markReady() — the search never resumed")
            assertEquals("ubuntu", request.query)
        }

    // CHALLENGE — the graceful-degradation guarantee: if the gate is NEVER
    // marked ready (e.g. an unexpected bug elsewhere left it stuck), the
    // search still proceeds once its bounded timeout elapses rather than
    // hanging the user's UI forever.
    @Test
    fun `streaming search proceeds anyway once the readiness gate's bounded wait times out`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val capturing = CapturingClient(descriptor("p1"))
            val gate = StartupProvidersGate() // never marked ready in this test
            val vm = createViewModel(
                providerIds = listOf("p1"),
                clients = listOf(capturing),
                providersReadyGate = gate,
            )

            vm.test(this) {
                runOnCreate()
                assertNull(
                    "the client MUST NOT be touched before the gate's timeout elapses",
                    capturing.lastRequest,
                )

                // Advance virtual time past StartupProvidersGate.DEFAULT_AWAIT_TIMEOUT_MS
                // (5_000ms) — the gate is still NOT ready, but the bounded wait
                // must give up rather than hang the search forever.
                mainDispatcherRule.testDispatcher.scheduler.advanceTimeBy(5_100L)
                cancelAndIgnoreRemainingItems()
            }

            // §6.J primary — the search proceeded despite the gate never
            // opening, proving the timeout fallback genuinely unblocks the UI.
            assertEquals(
                "the search MUST proceed once the bounded wait times out, even " +
                    "though the gate was never marked ready",
                "ubuntu",
                capturing.lastRequest?.query,
            )
            assertFalse(
                "the gate itself remains honestly not-ready — the timeout is a " +
                    "documented degradation, not a fake success",
                gate.ready.value,
            )
        }
}

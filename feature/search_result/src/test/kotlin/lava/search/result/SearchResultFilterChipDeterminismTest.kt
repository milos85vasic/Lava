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
import org.junit.Rule
import org.junit.Test
import org.orbitmvi.orbit.test.test
import kotlin.reflect.KClass

/**
 * Issue #3 (2026-06-25 QA video — LVA-079). Regression test for the
 * search-results filter chip set: it must show EXACTLY the requested /
 * configured provider set (the same source of truth the search-input chip
 * bar uses), be DETERMINISTICALLY ORDERED, and be INDEPENDENT of which
 * providers happened to respond first.
 *
 * The QA video showed the results chip set (a) disagreeing with the input
 * chip set and (b) CHANGING between two identical "die hard" queries — the
 * symptom of a response-derived, race-dependent chip set. The 1076 release
 * fixed the search-INPUT chips (resolved from `ProviderConfigRepository`,
 * `distinct().sorted()`); this test pins the RESULTS-side guarantee and the
 * input/results agreement contract.
 *
 * ## Real-stack wiring (Second / Third Law)
 * The SUT is the REAL [SearchResultViewModel] driven by the REAL
 * [LavaTrackerSdk] over a REAL [DefaultTrackerRegistry]. Only the outermost
 * network boundary is faked (each provider's `search()` returns an in-memory
 * [SearchResult]). No UseCase and no SDK method is mocked.
 *
 * ## Test classification
 * CHALLENGE — primary assertion on [SearchPageState.filterProviderChipIds],
 * the exact list the rendered `ProviderFilterChipBar` iterates to build the
 * user-visible chips.
 *
 * ## Falsifiability (Sixth Law clause 2)
 *  - Mutation A — drop `.distinct().sorted()` from
 *    `SearchPageState.filterProviderChipIds` (raw `filter.providerIds`
 *    passthrough): the unsorted requested set ["rutor","archiveorg",
 *    "rutracker"] is rendered as-is → fails at the `expected` assertion
 *    (`expected:<[archiveorg, rutracker, rutor]> but was:<[rutor,
 *    archiveorg, rutracker]>`).
 *  - Mutation B — derive the chips from response state (e.g.
 *    `providerDisplayNames.keys` or `searchContent.activeProviders`): only
 *    responders (or responders in arrival order) appear → fails the
 *    full-set / determinism assertions.
 *
 * ## Bluff-Audit
 * See commit body for the executed mutation + observed-failure record.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchResultFilterChipDeterminismTest {

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

    private class NoopAnalytics : AnalyticsTracker {
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
                queryKey to "die hard",
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
            analytics = NoopAnalytics(),
            sdk = LavaTrackerSdk(registry = registry),
            // LVA-093: this suite tests filter-chip determinism, not the
            // cold-start race — pre-mark ready so search proceeds immediately.
            providersReadyGate = StartupProvidersGate().apply { markReady() },
        )
    }

    // CHALLENGE
    @Test
    fun results_filter_chip_set_equals_requested_set_sorted_and_independent_of_response_order() =
        runTest(mainDispatcherRule.testDispatcher) {
            // The requested/configured provider set in a deliberately NON-sorted
            // order (as a deep link, restored back-stack arg, or any non-input
            // caller could supply). The chip source must be the SAME set, sorted.
            val requested = listOf("rutor", "archiveorg", "rutracker")
            val expected = listOf("archiveorg", "rutor", "rutracker")

            // Run A: registration order rutor→archiveorg→rutracker; only
            // archiveorg returns results (the other two respond empty). A
            // response-derived chip set would either drop the empty responders
            // or order by arrival — this proves it does neither.
            val vmA = createViewModel(
                providerIds = requested,
                clients = listOf(
                    FixedResultClient(descriptor("rutor"), emptyList()),
                    FixedResultClient(descriptor("archiveorg"), listOf(item("archiveorg", "t1", "Die Hard"))),
                    FixedResultClient(descriptor("rutracker"), emptyList()),
                ),
            )
            vmA.test(this) {
                runOnCreate()
                cancelAndIgnoreRemainingItems()
            }
            val chipsA = vmA.container.stateFlow.value.filterProviderChipIds

            // Run B: DIFFERENT registration order rutracker→rutor→archiveorg and
            // a DIFFERENT subset of responders — the exact "two identical queries,
            // different chip set" symptom from the QA video.
            val vmB = createViewModel(
                providerIds = requested,
                clients = listOf(
                    FixedResultClient(descriptor("rutracker"), listOf(item("rutracker", "t2", "Die Hard 2"))),
                    FixedResultClient(descriptor("rutor"), listOf(item("rutor", "t3", "Die Hard 3"))),
                    FixedResultClient(descriptor("archiveorg"), emptyList()),
                ),
            )
            vmB.test(this) {
                runOnCreate()
                cancelAndIgnoreRemainingItems()
            }
            val chipsB = vmB.container.stateFlow.value.filterProviderChipIds

            // (a) chip set == requested/configured set, deterministically sorted.
            assertEquals(
                "results chip set must equal the full requested provider set, sorted",
                expected,
                chipsA,
            )
            assertEquals(
                "results chip set must equal the full requested provider set, sorted",
                expected,
                chipsB,
            )
            // (b) deterministic run-to-run despite different response order/subset.
            assertEquals(
                "results chip set must be identical across two identical queries " +
                    "regardless of which providers responded first",
                chipsA,
                chipsB,
            )
        }
}

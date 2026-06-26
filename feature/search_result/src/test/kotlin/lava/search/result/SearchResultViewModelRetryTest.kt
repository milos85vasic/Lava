package lava.search.result

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
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
 * Integration Challenge for the operator-reported broken-search RECOVERY
 * flow in [SearchResultViewModel] — the user-felt path:
 *
 *   multi-provider search fails ("Something went wrong")
 *     → [SearchResultContent.Error] rendered with a Retry button
 *     → user taps Retry ([SearchResultAction.RetryClick])
 *     → the same stream is re-subscribed
 *     → the second attempt succeeds → [SearchResultContent.Content].
 *
 * This is the recovery path a real user takes when the first search round
 * fails transiently (the backend was briefly unreachable / returned an
 * error) and they retry. It is the user-visible counterpart to the §6.Z
 * forensic discipline: a release search that fails must offer a working
 * Retry, and the Retry must actually re-run the search.
 *
 * ## Why this is a real gap (not covered elsewhere)
 *
 * - [ApplyMultiSearchEventTest] tests ONLY the pure file-scope reducer
 *   for per-provider EVENTS — it never models a whole-stream failure and
 *   never touches [SearchResultViewModel.onRetryClick].
 * - [SearchResultViewModelStreamingTest] covers ONLY the success→Content
 *   and empty→Empty downgrades of a stream that completes normally. It
 *   never makes the stream FAIL and never exercises Retry.
 * - [SearchResultViewModelFallbackTest] drives ONLY the legacy paging
 *   path + the cross-tracker-fallback modal; its registry-only SDK never
 *   enters [observeStreamMultiSearch], so Retry is never reached.
 *
 * The whole-stream-failure → [SearchResultContent.Error] → RetryClick →
 * re-subscribe → success branch of [onRetryClick] (the
 * `state.searchContent is SearchResultContent.Error` dispatch that
 * re-invokes `observeStreamMultiSearch`) had ZERO test coverage until this
 * one — and, as the gap audit found, NO production code path even produced
 * [SearchResultContent.Error] for the streaming case, so that retry branch
 * was unreachable dead code. The accompanying production fix wraps the
 * stream collection so a whole-stream throw surfaces as [Error]; this
 * Challenge is the regression guard for both halves (the Error surfacing
 * AND the Retry re-subscription).
 *
 * ## Real-stack wiring (Second/Third Law)
 *
 * The SUT is the REAL [SearchResultViewModel] driven by a REAL
 * [LavaTrackerSdk] over a REAL [DefaultTrackerRegistry]. Only the
 * outermost network boundary is faked: a [FlakyClient] whose `search()`
 * THROWS on the first call (the transient backend failure) and returns
 * results on the second call (the successful retry). The SDK's fan-out,
 * the VM's reducer, `handleStreamEnd()`, and `onRetryClick()`'s dispatch
 * table are all production code. No UseCase and no SDK method is mocked.
 *
 * ## Test classification
 * CHALLENGE — primary assertions on the user-visible rendered content
 * branch ([SearchResultContent.Error] after the failure, then
 * [SearchResultContent.Content] with the retried items after Retry).
 *
 * ## Bluff-Audit
 * See commit body for the mutation + observed-failure record.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchResultViewModelRetryTest {

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
     * Fake tracker client whose first `search()` THROWS (transient backend
     * failure → whole-stream failure once it is the only provider), then
     * returns [items] on every subsequent call (the successful retry). The
     * throw/recover toggle is the only boundary fake; everything above it
     * is production code.
     */
    private class FlakyClient(
        override val descriptor: TrackerDescriptor,
        private val items: List<TorrentItem>,
    ) : TrackerClient {
        var searchAttempts = 0
            private set

        override suspend fun healthCheck(): Boolean = true
        override fun close() {}

        @Suppress("UNCHECKED_CAST")
        override fun <T : TrackerFeature> getFeature(featureClass: KClass<T>): T? = when (featureClass) {
            SearchableTracker::class -> object : SearchableTracker {
                override suspend fun search(request: SearchRequest, page: Int): SearchResult {
                    searchAttempts += 1
                    if (searchAttempts == 1) {
                        throw java.io.IOException("transient backend failure (attempt 1)")
                    }
                    return SearchResult(items = items, totalPages = 1, currentPage = page)
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

    private val providerIdsKey = "pids"
    private val queryKey = "nm"

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

    private lateinit var pagingFake: FakeObserveSearchPagingDataUseCase

    private fun createViewModel(
        providerIds: List<String>,
        clients: List<TrackerClient>,
    ): SearchResultViewModel {
        pagingFake = FakeObserveSearchPagingDataUseCase()
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
            addSearchHistoryUseCase = FakeAddSearchHistoryUseCase(),
            enrichFilterUseCase = FakeEnrichFilterUseCase(),
            toggleFavoriteUseCase = FakeToggleFavoriteUseCase(),
            observeAuthStateUseCase = FakeObserveAuthStateUseCase(),
            observeSettingsUseCase = ObserveSettingsUseCase(TestSettingsRepository()),
            analytics = object : lava.common.analytics.AnalyticsTracker {
                override fun event(name: String, params: Map<String, String>) {}
                override fun setUserId(userId: String?) {}
                override fun setProperty(key: String, value: String?) {}
                override fun recordNonFatal(throwable: Throwable, context: Map<String, String>) {}
                override fun recordWarning(message: String, context: Map<String, String>) {}
                override fun log(message: String) {}
            },
            sdk = LavaTrackerSdk(registry = registry),
        )
    }

    // CHALLENGE
    @Test
    fun streaming_search_that_fails_renders_Error_then_Retry_recovers_to_Content() =
        runTest(mainDispatcherRule.testDispatcher) {
            val flaky = FlakyClient(
                descriptor("p1"),
                listOf(item("p1", "t1", "Ubuntu ISO")),
            )
            val vm = createViewModel(providerIds = listOf("p1"), clients = listOf(flaky))

            vm.test(this) {
                runOnCreate()

                // §6.J primary #1 — the first stream attempt FAILED, so the
                // user MUST see the Error placeholder (with its Retry
                // button), NOT a misleading "Nothing found" (Empty) and NOT
                // a hung Streaming spinner.
                val afterFailure = vm.container.stateFlow.value.searchContent
                assertTrue(
                    "a failed stream must render Error (Retry affordance), was ${afterFailure::class.simpleName}",
                    afterFailure is SearchResultContent.Error,
                )
                assertEquals("the first attempt must have run exactly once", 1, flaky.searchAttempts)

                // User taps Retry — the SAME production handler the screen's
                // Retry button fires.
                vm.perform(SearchResultAction.RetryClick)
                cancelAndIgnoreRemainingItems()
            }

            // §6.J primary #2 — Retry re-subscribed the stream, the second
            // attempt succeeded, and the user now sees their result.
            val afterRetry = vm.container.stateFlow.value.searchContent
            assertTrue(
                "after a successful retry the user must see Content, was ${afterRetry::class.simpleName}",
                afterRetry is SearchResultContent.Content,
            )
            val titles = (afterRetry as SearchResultContent.Content).torrents.map { it.topic.title }
            assertEquals(listOf("Ubuntu ISO"), titles)
            assertTrue("retry must have triggered a second search attempt", flaky.searchAttempts >= 2)
            // The streaming retry must NOT fall through to the legacy paging path.
            assertEquals(
                "streaming retry must re-subscribe the stream, not invoke the legacy paging use-case",
                0,
                pagingFake.invocations,
            )
        }

    private class AlwaysEmptyClient(override val descriptor: TrackerDescriptor) : TrackerClient {
        override suspend fun healthCheck(): Boolean = true
        override fun close() {}

        @Suppress("UNCHECKED_CAST")
        override fun <T : TrackerFeature> getFeature(featureClass: KClass<T>): T? = when (featureClass) {
            SearchableTracker::class -> object : SearchableTracker {
                override suspend fun search(request: SearchRequest, page: Int): SearchResult =
                    SearchResult(items = emptyList(), totalPages = 1, currentPage = page)
            } as T
            else -> null
        }
    }

    private class AlwaysErrorClient(override val descriptor: TrackerDescriptor) : TrackerClient {
        override suspend fun healthCheck(): Boolean = true
        override fun close() {}

        @Suppress("UNCHECKED_CAST")
        override fun <T : TrackerFeature> getFeature(featureClass: KClass<T>): T? = when (featureClass) {
            SearchableTracker::class -> object : SearchableTracker {
                override suspend fun search(request: SearchRequest, page: Int): SearchResult =
                    throw java.io.IOException("provider down (Cloudflare 522)")
            } as T
            else -> null
        }
    }

    // CHALLENGE — reproduce-first for the 2026-06-26 operator-video issue #1
    // ("Something went wrong" on every search). One provider SUCCEEDS with 0
    // results (yts: "1080p" is a quality term, not a movie title), another
    // ERRORS (torrentdownloads: Cloudflare 522, site down). The user MUST see
    // "No results" (Empty) — a provider that searched and found nothing is a
    // valid empty result — NOT a full-screen "search failed" (Error) that hides
    // everything. Pre-fix handleStreamEnd used `anyProviderFailed`, so any single
    // provider error + empty items wrongly rendered Error. Engine evidence:
    // .lava-ci-evidence/1076-repro/ (torrentdownloads 522 + yts 0-result).
    @Test
    fun streaming_partial_failure_one_empty_one_error_renders_Empty_not_Error() =
        runTest(mainDispatcherRule.testDispatcher) {
            val ok = AlwaysEmptyClient(descriptor("ytsok"))
            val err = AlwaysErrorClient(descriptor("tderr"))
            val vm = createViewModel(providerIds = listOf("ytsok", "tderr"), clients = listOf(ok, err))

            vm.test(this) {
                runOnCreate()
                val end = vm.container.stateFlow.value.searchContent
                assertTrue(
                    "partial failure (one provider returned 0 results, one errored) must render " +
                        "Empty (No results), not full-screen Error; was ${end::class.simpleName}",
                    end is SearchResultContent.Empty,
                )
                cancelAndIgnoreRemainingItems()
            }
        }
}

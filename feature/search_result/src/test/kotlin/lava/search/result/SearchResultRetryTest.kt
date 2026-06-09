package lava.search.result

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import lava.domain.model.LoadStates
import lava.domain.model.Pagination
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
import lava.testing.logger.TestLoggerFactory
import lava.testing.repository.TestSettingsRepository
import lava.testing.rule.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.orbitmvi.orbit.test.test

/**
 * LVA-069 (2026-06-09). Coverage for `SearchResultViewModel.onRetryClick`
 * — the dispatch-by-state branch introduced by the sweep-#2 closure
 * (2026-05-17, 1.2.29-1049): when `searchContent` is
 * [SearchResultContent.Error] the retry re-subscribes the search by
 * re-running the onCreate dispatch table; otherwise it pushes a
 * [PagingAction] onto the existing Paging3 stream (`pagingActions.retry()`).
 * Before this cycle NEITHER branch of `onRetryClick` was tested.
 *
 * Anti-Bluff posture (§6.J): the REAL ViewModel is driven through the REAL
 * `perform(SearchResultAction.RetryClick)` action handler via the
 * orbit-test harness (no SUT mock, no synthetic intent shortcut). The
 * Error state is reached via the production [SearchResultViewModel.applySseError]
 * reducer — the SAME method `observeSseSearch` calls on a live `SseEvent.Error`
 * (NOT a test-only backdoor).
 *
 * The user is wired Authorized so the paging use-case IS invoked (it is
 * skipped for an Unauthorized user, which short-circuits to the login
 * prompt before the paging subscription). The paging fake emits an empty
 * [PagingData] so the content reduces to [SearchResultContent.Empty] — the
 * user-visible "no results" state that re-appears after a successful
 * re-subscription, REPLACING the Error banner.
 *
 * Discriminating signals:
 *  - §6.J primary (user-visible): the rendered content branch (Empty vs
 *    Error) — proving the Error banner clears (or stays) on retry.
 *  - §6.J secondary: the paging use-case invocation count — proving the
 *    dispatch table re-ran (re-subscribe = +1) vs. did not (Paging3 retry
 *    pushes onto the existing stream = no new subscription).
 *
 * Falsifiability rehearsal (§6.J): see per-test KDoc + the commit
 * Bluff-Audit stamp.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchResultRetryTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /**
     * Real, named fake for [ObserveSearchPagingDataUseCase]. Emits ONE
     * empty [PagingData] per subscription so the VM reduces to
     * [SearchResultContent.Empty], and records every invocation so the
     * test can assert whether the dispatch table was re-run.
     */
    private class FakeObserveSearchPagingDataUseCase : ObserveSearchPagingDataUseCase {
        var invocations = 0
            private set

        override fun invoke(
            filterFlow: Flow<Filter>,
            actionsFlow: Flow<PagingAction>,
            scope: CoroutineScope,
        ): Flow<PagingData<List<TopicModel<Torrent>>>> {
            invocations += 1
            return flowOf(
                PagingData(
                    data = emptyList(),
                    loadStates = LoadStates.Idle,
                    pagination = Pagination(),
                ),
            )
        }
    }

    private class FakeAddSearchHistoryUseCase : AddSearchHistoryUseCase {
        override suspend fun invoke(filter: Filter) = Unit
    }

    private class FakeEnrichFilterUseCase : EnrichFilterUseCase {
        override suspend fun invoke(filter: Filter): Filter = filter
    }

    private class FakeToggleFavoriteUseCase : ToggleFavoriteUseCase {
        override suspend fun invoke(id: String, providerId: String?) = Unit
    }

    private class FakeObserveAuthStateUseCase(
        initial: AuthState,
    ) : ObserveAuthStateUseCase {
        val state = MutableStateFlow(initial)
        override fun invoke(): Flow<AuthState> = state
    }

    private val noopAnalytics = object : lava.common.analytics.AnalyticsTracker {
        override fun event(name: String, params: Map<String, String>) {}
        override fun setUserId(userId: String?) {}
        override fun setProperty(key: String, value: String?) {}
        override fun recordNonFatal(throwable: Throwable, context: Map<String, String>) {}
        override fun recordWarning(message: String, context: Map<String, String>) {}
        override fun log(message: String) {}
    }

    private lateinit var pagingFake: FakeObserveSearchPagingDataUseCase

    private fun createViewModel(): SearchResultViewModel {
        pagingFake = FakeObserveSearchPagingDataUseCase()
        return SearchResultViewModel(
            savedStateHandle = SavedStateHandle(
                mapOf("filter" to Filter(query = "ubuntu", providerIds = null)),
            ),
            loggerFactory = TestLoggerFactory(),
            observeSearchPagingDataUseCase = pagingFake,
            addSearchHistoryUseCase = FakeAddSearchHistoryUseCase(),
            enrichFilterUseCase = FakeEnrichFilterUseCase(),
            toggleFavoriteUseCase = FakeToggleFavoriteUseCase(),
            // Authorized so the paging subscription actually fires (the
            // Unauthorized branch short-circuits before it).
            observeAuthStateUseCase = FakeObserveAuthStateUseCase(
                AuthState.Authorized(name = "tester", avatarUrl = null),
            ),
            observeSettingsUseCase = ObserveSettingsUseCase(TestSettingsRepository()),
            analytics = noopAnalytics,
            sdk = lava.tracker.client.LavaTrackerSdk(
                registry = lava.tracker.registry.DefaultTrackerRegistry(),
            ),
        )
    }

    /**
     * Non-Error branch: with `providerIds == null` + default endpoint the
     * VM is on the paging path; onCreate renders [SearchResultContent.Empty]
     * (the empty PagingData). Tapping Retry MUST push onto the existing
     * Paging3 stream (`pagingActions.retry()`), NOT re-run the dispatch
     * table — so the paging use-case stays at ONE subscription and the
     * content stays Empty.
     *
     * Falsifiability: invert the `if (state.searchContent is Error)` guard
     * to `if (state.searchContent !is Error)` — RetryClick on this Empty
     * (non-Error) state would re-run observePagingData(), driving the
     * invocation count to 2; the `assertEquals(1, invocations)` fails
     * (`expected 1 was 2`).
     */
    @Test
    fun retry_in_non_error_state_does_not_re_dispatch() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = createViewModel()
        vm.test(this) {
            runOnCreate()
            vm.perform(SearchResultAction.RetryClick)
            cancelAndIgnoreRemainingItems()
        }

        val s = vm.container.stateFlow.value
        // §6.J primary — content unchanged by a non-Error retry.
        assertTrue(
            "non-Error retry must keep the Empty content, was ${s.searchContent}",
            s.searchContent is SearchResultContent.Empty,
        )
        // §6.J secondary — paging use-case was NOT re-subscribed.
        assertEquals(
            "non-Error retry must not re-run the dispatch table",
            1,
            pagingFake.invocations,
        )
    }

    /**
     * Error branch: when `searchContent` is [SearchResultContent.Error]
     * (the value the production SSE-failure reduce writes via
     * [SearchResultViewModel.applySseError]), tapping Retry MUST re-run the
     * onCreate dispatch table. With `providerIds == null` the re-dispatch
     * routes to `observePagingData()`, REPLACING the Error banner with the
     * Empty (no-results) content and re-invoking the paging use-case a
     * SECOND time.
     *
     * Falsifiability: delete the `if (state.searchContent is Error) { ...
     * return@intent }` block in onRetryClick — RetryClick would fall through
     * to `pagingActions.retry()`, a no-op while in Error, so the Error banner
     * would NEVER clear: the `Empty` assertion fails (`was Error`) AND the
     * invocation-count assertion fails (`expected 2 was 1`).
     */
    @Test
    fun retry_in_error_state_re_dispatches_and_clears_error() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = createViewModel()
        vm.test(this) {
            runOnCreate()
            // Reach the production Error state through the SAME method the
            // real SSE-failure branch runs (`observeSseSearch` calls
            // applySseError on an SseEvent.Error). Production wiring, not a
            // test-only backdoor.
            vm.applySseError("Search stream failed", "ubuntu")
            vm.perform(SearchResultAction.RetryClick)
            cancelAndIgnoreRemainingItems()
        }

        val s = vm.container.stateFlow.value
        // §6.J primary — the Error banner is REPLACED by the re-subscribed
        // content (Empty) the user sees after retrying.
        assertTrue(
            "Error retry must re-dispatch + clear Error, was ${s.searchContent}",
            s.searchContent is SearchResultContent.Empty,
        )
        // §6.J secondary — the paging use-case was re-subscribed (2nd time):
        // onCreate (1) + the Error-retry re-dispatch (2).
        assertEquals(
            "Error retry must re-run the dispatch table (re-subscribe)",
            2,
            pagingFake.invocations,
        )
    }
}

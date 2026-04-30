package lava.search.result

import androidx.lifecycle.SavedStateHandle
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import lava.domain.usecase.AddSearchHistoryUseCase
import lava.domain.usecase.EnrichFilterUseCase
import lava.domain.usecase.ObserveAuthStateUseCase
import lava.domain.usecase.ObserveSearchPagingDataUseCase
import lava.domain.usecase.ToggleFavoriteUseCase
import lava.models.auth.AuthState
import lava.models.search.Filter
import lava.models.search.Order
import lava.models.search.Sort
import lava.testing.logger.TestLoggerFactory
import lava.testing.rule.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.orbitmvi.orbit.test.test

/**
 * Tests the SP-3a Phase 4 (Task 4.18) cross-tracker fallback state-machine
 * slice added to [SearchResultViewModel]: the proposeFallback intent +
 * FallbackAccept / FallbackDismiss action handling.
 *
 * ## Phase 4 paging-graph PARTIAL (2026-04-30)
 *
 * The 2026-04-30 follow-up audited this test against the Seventh Law
 * Forbidden Test Patterns and found that 4 of the 5 use-case mocks
 * (`ObserveSearchPagingDataUseCase`, `AddSearchHistoryUseCase`,
 * `EnrichFilterUseCase`, `ToggleFavoriteUseCase`) cannot be replaced with
 * real implementations without building a multi-module fake graph
 * (Repository fakes for `SearchService`, `BookmarksRepository`,
 * `FavoritesRepository`, `VisitedRepository`, `BackgroundService`,
 * `Dispatchers`, `LoggerFactory` plus the SDK→`SwitchingNetworkApi`→legacy
 * `NetworkApi` chain — all of which the test's actual scope, the fallback
 * state-slot mechanics, never traverses).
 *
 * Production paging IS routed through `LavaTrackerSdk.search()` already:
 * `ObserveSearchPagingDataUseCase` -> `SearchService` ->
 * `SwitchingNetworkApi.getSearchPage` (Section G of SP-3a) ->
 * `LavaTrackerSdk.search()` on the direct rutracker path; LAN endpoints
 * (`Endpoint.GoApi`, `Endpoint.Mirror` on a LAN host) fall through to the
 * legacy path. The transitive routing means the fallback state-slot
 * mechanics under test here ARE backed by the SDK in production — the
 * test simply doesn't exercise that depth because none of the three
 * actions (proposeFallback / FallbackAccept / FallbackDismiss) reach the
 * paging path. Per the `feature/CLAUDE.md` Owed-rendered-UI-Challenges
 * gap, the fully-real-stack assertion of paging behaviour is properly
 * the job of a Challenge Test under `app/src/androidTest/.../C<N>_*.kt`,
 * NOT this VM-CONTRACT test.
 *
 * Concrete partial-resolution applied here:
 *  - `ObserveAuthStateUseCase` mock replaced with a real lambda
 *    implementation. The interface `() -> Flow<AuthState>` is trivial to
 *    instantiate without mockk; this removes the most-tested code path
 *    (observePagingData branches on auth state every emission) from the
 *    "mocked internal logic" set.
 *  - The remaining 4 use cases (paging, history, enrich, toggle-fav) stay
 *    as relaxed mocks because they are FINAL Kotlin classes (no
 *    `open` / no all-open compiler plugin in this build). Subclassing them
 *    requires either an upstream refactor that promotes them to interfaces
 *    (open question for SP-3a-bridge) or an `@MockK` bytecode dance —
 *    neither is the right shape for a follow-up commit, and neither would
 *    increase the test's actual coverage of the fallback state-slot logic.
 *  - The remaining mocks are returns-empty-flow / coAnswers-firstArg and
 *    do not impersonate real business logic — they are null-object
 *    boundaries for code paths the test does not assert on.
 *
 * ## Test classification
 *
 * VM-CONTRACT — primary assertions on ViewModel state (the surface the
 * screen reads + sideEffect emission). The rendered-UI Challenge gate is
 * owed per `feature/CLAUDE.md` (no `src/androidTest/` Compose UI infra
 * in this branch yet).
 *
 * ## Bluff-Audit
 *
 * - Test type: VM-CONTRACT — primary assertions on ViewModel state.
 *   Rendered-UI Challenge is owed (Phase 5 + future Compose UI infra).
 * - Falsifiability rehearsal (Sixth Law clause 2): removed the
 *   `state.copy(crossTrackerFallback = ...)` line from
 *   SearchResultViewModel.proposeFallback. Test
 *   `proposeFallback_sets_crossTrackerFallback_state_slot` failed with
 *   "expected: not null but was: null". Reverted before commit.
 * - Forbidden patterns audited 2026-04-30:
 *   - SUT mocking: ABSENT (the ViewModel is real; no `mockk<SearchResultViewModel>`).
 *   - Count-only verify: ABSENT (assertions are on returned state /
 *     emitted side effect, not `verify { mock invoked N times }`).
 *   - @Ignore: ABSENT (no skipped tests).
 *   - Build-but-don't-invoke: ABSENT (every test calls `vm.proposeFallback`
 *     or `vm.perform(...)` and asserts on the resulting state).
 *   - BUILD-SUCCESSFUL-only: ABSENT (assertions on user-visible state).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchResultViewModelFallbackTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /**
     * Real lambda implementation of [ObserveAuthStateUseCase]. The interface
     * is `() -> Flow<AuthState>`, so this is genuinely a real, non-mock
     * implementation — no mockk involved. Returning a single-emission flow
     * of [AuthState.Unauthorized] is the simplest honest behaviour that
     * lets `observePagingData` reach the Unauthorized branch (which the
     * fallback-state tests don't assert on but which is exercised by
     * `runOnCreate()`).
     */
    private val realObserveAuthState: ObserveAuthStateUseCase =
        object : ObserveAuthStateUseCase {
            override fun invoke(): Flow<AuthState> = flowOf(AuthState.Unauthorized)
        }

    private fun createViewModel(): SearchResultViewModel {
        // Permitted-boundary fakes for the paging / history / enrich /
        // toggle-fav use cases (all FINAL Kotlin classes — no
        // `open` modifier, no all-open compiler plugin in this build).
        // These are null-object boundaries: the SUT actions under test
        // (proposeFallback / FallbackAccept / FallbackDismiss) never reach
        // any of them. Documented in the class KDoc above.
        val saved = SavedStateHandle()
        val observePaging = mockk<ObserveSearchPagingDataUseCase>(relaxed = true) {
            every { this@mockk.invoke(any(), any(), any()) } returns flowOf()
        }
        val addHistory = mockk<AddSearchHistoryUseCase>(relaxed = true)
        val enrich = mockk<EnrichFilterUseCase>(relaxed = true)
        coEvery { enrich.invoke(any()) } answers { firstArg() }
        val toggleFav = mockk<ToggleFavoriteUseCase>(relaxed = true)
        return SearchResultViewModel(
            savedStateHandle = saved,
            loggerFactory = TestLoggerFactory(),
            observeSearchPagingDataUseCase = observePaging,
            addSearchHistoryUseCase = addHistory,
            enrichFilterUseCase = enrich,
            toggleFavoriteUseCase = toggleFav,
            // REAL implementation — see realObserveAuthState above.
            observeAuthStateUseCase = realObserveAuthState,
        )
    }

    // VM-CONTRACT
    @Test
    fun proposeFallback_sets_crossTrackerFallback_state_slot() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = createViewModel()
        vm.test(this) {
            runOnCreate()
            vm.proposeFallback(failedTrackerId = "rutracker", proposedTrackerId = "rutor")
            // Drain any reduces from onCreate's observePagingData/observeFilter then
            // the proposal reduce.
            cancelAndIgnoreRemainingItems()
        }
        val s = vm.container.stateFlow.value
        assertNotNull("crossTrackerFallback MUST be populated after proposeFallback", s.crossTrackerFallback)
        assertEquals("rutracker", s.crossTrackerFallback?.failedTrackerId)
        assertEquals("rutor", s.crossTrackerFallback?.proposedTrackerId)
    }

    // VM-CONTRACT
    @Test
    fun FallbackAccept_clears_crossTrackerFallback_slot() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = createViewModel()
        vm.test(this) {
            runOnCreate()
            vm.proposeFallback(failedTrackerId = "rutracker", proposedTrackerId = "rutor")
            vm.perform(SearchResultAction.FallbackAccept)
            cancelAndIgnoreRemainingItems()
        }
        assertNull(vm.container.stateFlow.value.crossTrackerFallback)
    }

    // VM-CONTRACT
    @Test
    fun FallbackDismiss_clears_slot_and_emits_ShowFallbackDismissedError() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = createViewModel()
        var capturedDismissError: SearchResultSideEffect.ShowFallbackDismissedError? = null
        vm.test(this) {
            runOnCreate()
            vm.proposeFallback(failedTrackerId = "rutracker", proposedTrackerId = "rutor")
            vm.perform(SearchResultAction.FallbackDismiss)
            // Drain queued side-effects.
            try {
                while (true) {
                    val item = awaitItem()
                    if (item is org.orbitmvi.orbit.test.Item.SideEffectItem) {
                        val effect = item.value
                        if (effect is SearchResultSideEffect.ShowFallbackDismissedError) {
                            capturedDismissError = effect
                            break
                        }
                    }
                }
            } catch (_: Throwable) {
                // turbine cancellation
            }
            cancelAndIgnoreRemainingItems()
        }
        assertNull(vm.container.stateFlow.value.crossTrackerFallback)
        assertNotNull("ShowFallbackDismissedError side effect MUST be emitted on dismiss", capturedDismissError)
        assertEquals("rutracker", capturedDismissError?.failedTracker)
    }

    // Helper imports kept inline so the test is self-contained.
    @Suppress("unused")
    private val _filter = Filter(query = "ubuntu", sort = Sort.SEEDS, order = Order.DESCENDING)
}

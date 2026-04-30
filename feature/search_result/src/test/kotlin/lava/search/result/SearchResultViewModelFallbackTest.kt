package lava.search.result

import androidx.lifecycle.SavedStateHandle
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * Mocking note (Seventh Law clause 2 + 4(a)): this test mocks the use
 * cases the existing ViewModel takes as ctor args. The use cases are
 * NOT the SUT — the ViewModel is. The new contract under test
 * (proposeFallback / FallbackAccept / FallbackDismiss) does not touch
 * ANY of these use cases at all; mocks here exist only because the
 * ViewModel constructor requires non-null instances. This is the
 * "lowest permitted boundary" exception; the assertions are on the
 * ViewModel state (the surface the screen reads) and a side-effect
 * emission. The Phase 5 Challenge Tests will replace these mocks with
 * real fakes once the paging-path migration to the SDK lands.
 *
 * Bluff-Audit:
 * - Test type: VM-CONTRACT — primary assertions on ViewModel state
 *   (the surface the screen reads + sideEffect emission). Rendered-UI
 *   Challenge gate is owed (Phase 5).
 * - Falsifiability rehearsal: removed the
 *   `state.copy(crossTrackerFallback = ...)` line from
 *   SearchResultViewModel.proposeFallback. Test
 *   proposeFallback_sets_crossTrackerFallback_state_slot
 *   failed with "expected: not null but was: null". Reverted before
 *   commit.
 * - Forbidden patterns: SUT mocking is absent (the ViewModel is real;
 *   only its use-case dependencies are mocked at the boundary below
 *   the SUT — see "Mocking note" above), no verify-only assertions,
 *   no @Ignore.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchResultViewModelFallbackTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun createViewModel(): SearchResultViewModel {
        // Permitted-boundary mocks: the use cases are dependencies, not
        // the SUT. Real fakes will land in Phase 5 with the paging-path
        // migration.
        val saved = SavedStateHandle()
        val observePaging = mockk<ObserveSearchPagingDataUseCase>(relaxed = true) {
            every { this@mockk.invoke(any(), any(), any()) } returns flowOf()
        }
        val addHistory = mockk<AddSearchHistoryUseCase>(relaxed = true)
        val enrich = mockk<EnrichFilterUseCase>(relaxed = true)
        coEvery { enrich.invoke(any()) } answers { firstArg() }
        val toggleFav = mockk<ToggleFavoriteUseCase>(relaxed = true)
        val observeAuth = mockk<ObserveAuthStateUseCase>(relaxed = true) {
            every { this@mockk.invoke() } returns flowOf(AuthState.Unauthorized)
        }
        return SearchResultViewModel(
            savedStateHandle = saved,
            loggerFactory = TestLoggerFactory(),
            observeSearchPagingDataUseCase = observePaging,
            addSearchHistoryUseCase = addHistory,
            enrichFilterUseCase = enrich,
            toggleFavoriteUseCase = toggleFav,
            observeAuthStateUseCase = observeAuth,
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

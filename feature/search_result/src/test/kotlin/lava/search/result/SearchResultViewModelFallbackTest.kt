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
import lava.models.search.Order
import lava.models.search.Sort
import lava.models.topic.TopicModel
import lava.models.topic.Torrent
import lava.testing.logger.TestLoggerFactory
import lava.testing.repository.TestSettingsRepository
import lava.testing.rule.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.orbitmvi.orbit.test.test

/**
 * Tests the SP-3a Phase 4 (Task 4.18) cross-tracker fallback state-machine
 * slice added to [SearchResultViewModel]: the proposeFallback intent +
 * FallbackAccept / FallbackDismiss action handling.
 *
 * ## Phase 4 paging-graph FULL closure (2026-04-30)
 *
 * As of 2026-04-30 the four use-cases consumed by this ViewModel
 * (`ObserveSearchPagingDataUseCase`, `AddSearchHistoryUseCase`,
 * `EnrichFilterUseCase`, `ToggleFavoriteUseCase`) were promoted from
 * FINAL Kotlin classes to interfaces with `*Impl` Hilt bindings. This
 * test now uses **named, real test fakes** that implement those
 * interfaces — no `mockk<...>(relaxed = true)` for any internal
 * business-logic boundary, no `coEvery { ... } answers { firstArg() }`
 * mock-script. The fakes are observable: tests can read counters
 * (`addSearchHistoryFake.invocations`, `toggleFavoriteFake.invocations`)
 * to confirm side-effects without resorting to mockk's count-only
 * `verify` (a Forbidden Test Pattern, Seventh Law clause 4(b)).
 *
 * The remaining mock-style boundary is `ObserveAuthStateUseCase`, but it
 * is a real lambda-style implementation (no mockk) — the interface is
 * `() -> Flow<AuthState>`.
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
 * - Falsifiability rehearsal (Sixth Law clause 2): replaced
 *   `FakeObserveSearchPagingDataUseCase.invoke` to return `flowOf()` with
 *   a recorded boolean indicating whether the fake's invoke was even
 *   reached during the proposal flow; then mutated the
 *   `proposeFallback` reduce to `state.copy(crossTrackerFallback = null)`
 *   and confirmed the proposeFallback test fired with a non-null /
 *   null assertion mismatch.
 *   See commit body Bluff-Audit stamp for the actual mutation+failure
 *   recorded for this commit.
 * - Forbidden patterns audited 2026-04-30:
 *   - SUT mocking: ABSENT (the ViewModel is real; no `mockk<SearchResultViewModel>`).
 *   - Count-only verify: ABSENT (assertions are on returned state /
 *     emitted side effect, not `verify { mock invoked N times }`).
 *   - @Ignore: ABSENT (no skipped tests).
 *   - Build-but-don't-invoke: ABSENT (every test calls `vm.proposeFallback`
 *     or `vm.perform(...)` and asserts on the resulting state).
 *   - BUILD-SUCCESSFUL-only: ABSENT (assertions on user-visible state).
 *   - Anonymous boundary mocking: ABSENT (every fake is a named class
 *     with deterministic, observable behaviour).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchResultViewModelFallbackTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /**
     * Real, named fake for [ObserveSearchPagingDataUseCase]. Returns an
     * empty paging-data flow on every invocation (the fallback-state-slot
     * tests do not assert on paging output) and records every invocation
     * so a future test can verify the paging path was actually triggered.
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
            return flowOf()
        }
    }

    /**
     * Real, named fake for [AddSearchHistoryUseCase]. Records the filters
     * passed to it so tests can assert on the user-visible "history was
     * recorded" outcome by reading `recordedFilters` rather than verifying
     * a mock-call count.
     */
    private class FakeAddSearchHistoryUseCase : AddSearchHistoryUseCase {
        val recordedFilters = mutableListOf<Filter>()

        override suspend fun invoke(filter: Filter) {
            recordedFilters += filter
        }
    }

    /**
     * Real, named fake for [EnrichFilterUseCase]. Identity transform —
     * mirrors the production behaviour for filters that contain no
     * categories (the only shape this test uses).
     */
    private class FakeEnrichFilterUseCase : EnrichFilterUseCase {
        var invocations = 0
            private set

        override suspend fun invoke(filter: Filter): Filter {
            invocations += 1
            return filter
        }
    }

    /**
     * Real, named fake for [ToggleFavoriteUseCase]. Records every id it
     * was asked to toggle. None of the fallback-state-slot tests trigger
     * this path; the recording lets us assert that fact.
     */
    private class FakeToggleFavoriteUseCase : ToggleFavoriteUseCase {
        val toggledIds = mutableListOf<String>()

        override suspend fun invoke(id: String) {
            toggledIds += id
        }
    }

    /**
     * Real lambda implementation of [ObserveAuthStateUseCase]. The
     * interface IS `() -> Flow<AuthState>` so this is genuinely a real,
     * non-mock implementation — no mockk involved. The auth flow is a
     * MutableStateFlow so a subsequent test can promote to
     * `AuthState.Authorized` and observe the paging path actually
     * activate without restructuring the harness.
     */
    private class FakeObserveAuthStateUseCase(
        initial: AuthState = AuthState.Unauthorized,
    ) : ObserveAuthStateUseCase {
        val state = MutableStateFlow(initial)

        override fun invoke(): Flow<AuthState> = state
    }

    private lateinit var pagingFake: FakeObserveSearchPagingDataUseCase
    private lateinit var addHistoryFake: FakeAddSearchHistoryUseCase
    private lateinit var enrichFake: FakeEnrichFilterUseCase
    private lateinit var toggleFavFake: FakeToggleFavoriteUseCase
    private lateinit var authFake: FakeObserveAuthStateUseCase

    private fun createViewModel(): SearchResultViewModel {
        pagingFake = FakeObserveSearchPagingDataUseCase()
        addHistoryFake = FakeAddSearchHistoryUseCase()
        enrichFake = FakeEnrichFilterUseCase()
        toggleFavFake = FakeToggleFavoriteUseCase()
        authFake = FakeObserveAuthStateUseCase(AuthState.Unauthorized)
        return SearchResultViewModel(
            savedStateHandle = SavedStateHandle(),
            loggerFactory = TestLoggerFactory(),
            observeSearchPagingDataUseCase = pagingFake,
            addSearchHistoryUseCase = addHistoryFake,
            enrichFilterUseCase = enrichFake,
            toggleFavoriteUseCase = toggleFavFake,
            observeAuthStateUseCase = authFake,
            observeSettingsUseCase = ObserveSettingsUseCase(TestSettingsRepository()),
            analytics = object : lava.common.analytics.AnalyticsTracker {
                override fun event(name: String, params: Map<String, String>) {}
                override fun setUserId(userId: String?) {}
                override fun setProperty(key: String, value: String?) {}
                override fun recordNonFatal(throwable: Throwable, context: Map<String, String>) {}
                override fun recordWarning(message: String, context: Map<String, String>) {}
                override fun log(message: String) {}
            },
            // SP-4 Phase D added an `sdk: LavaTrackerSdk` constructor param
            // for the new streamMultiSearch consumer branch. This test only
            // exercises the legacy paging path, so a registry-only SDK
            // suffices.
            sdk = lava.tracker.client.LavaTrackerSdk(
                registry = lava.tracker.registry.DefaultTrackerRegistry(),
            ),
        )
    }

    // VM-CONTRACT
    @Test
    fun proposeFallback_sets_crossTrackerFallback_state_slot() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = createViewModel()
        vm.test(this) {
            runOnCreate()
            vm.proposeFallback(failedTrackerId = "rutracker", proposedTrackerId = "rutor")
            cancelAndIgnoreRemainingItems()
        }
        val s = vm.container.stateFlow.value
        assertNotNull("crossTrackerFallback MUST be populated after proposeFallback", s.crossTrackerFallback)
        assertEquals("rutracker", s.crossTrackerFallback?.failedTrackerId)
        assertEquals("rutor", s.crossTrackerFallback?.proposedTrackerId)
        // Side-channel honesty: confirm no spurious favorite-toggle was triggered by the proposal flow.
        assertTrue(
            "proposeFallback must NOT trigger toggleFavorite as a side effect, recorded ${toggleFavFake.toggledIds}",
            toggleFavFake.toggledIds.isEmpty(),
        )
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

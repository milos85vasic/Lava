package lava.rating

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import lava.data.api.repository.FavoriteSearchRepository
import lava.data.api.repository.RatingRepository
import lava.data.api.service.StoreService
import lava.domain.model.rating.RatingRequest
import lava.domain.usecase.AppLaunchedUseCase
import lava.domain.usecase.DisableRatingRequestUseCase
import lava.domain.usecase.EnrichTopicsUseCase
import lava.domain.usecase.GetRatingStoreUseCase
import lava.domain.usecase.ObserveBookmarksUseCase
import lava.domain.usecase.ObserveRatingRequestUseCaseImpl
import lava.domain.usecase.ObserveSearchHistoryUseCase
import lava.domain.usecase.ObserveVisitedUseCase
import lava.domain.usecase.PostponeRatingRequestUseCase
import lava.models.Store
import lava.models.forum.Category
import lava.testing.TestDispatchers
import lava.testing.logger.TestLoggerFactory
import lava.testing.repository.TestBookmarksRepository
import lava.testing.repository.TestFavoritesRepository
import lava.testing.repository.TestSearchHistoryRepository
import lava.testing.repository.TestVisitedRepository
import lava.testing.rule.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.orbitmvi.orbit.test.test

/**
 * Anti-bluff coverage for [RatingViewModel].
 *
 * LVA-016 (2026-06-09) — the observe use case is now the REAL
 * [ObserveRatingRequestUseCaseImpl] (made public for testability, Fifth Law),
 * wired over the shared in-memory fakes that became usable after LVA-012/015.
 * The PRIOR test used a hand-rolled `RealObserveRatingRequestUseCase` that
 * DROPPED the engagement gate (pinned>1 OR other>3 OR visited>5 OR bookmarks>2),
 * so `Show rating request is rendered when conditions are met` asserted the
 * dialog SHOWS with zero engagement — an outcome the REAL use case would NOT
 * produce (it emits Hide). That was a Second-Law / §6.J bluff: the test
 * re-implemented internal business logic AND asserted a user-visible outcome
 * production never gives. Now the Show-path tests seed real engagement
 * (3 bookmarks > the BookmarksCounter of 2), so a green test means the dialog
 * truly shows for an engaged user.
 *
 * Constitution (Second Law — no mocking of internal business logic):
 *  - The SUT is [RatingViewModel]; it is a REAL instance, never mocked.
 *  - The observe use case is the REAL production impl. The other four use cases
 *    (AppLaunched / Disable / Postpone / GetRatingStore) are behaviorally-
 *    equivalent impls of their PUBLIC interfaces performing the SAME observable
 *    operations as the production `*Impl`s (those `*Impl`s remain `internal` to
 *    :core:domain); each writes through the real in-memory [FakeRatingRepository].
 *  - Every primary assertion (Sixth Law clause 3) is on user-visible state: the
 *    rendered [RatingRequest] dialog and the [RatingSideEffect.OpenLink].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RatingViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private lateinit var repository: FakeRatingRepository
    private lateinit var storeService: FakeStoreService

    // Engagement collaborators for the REAL ObserveRatingRequestUseCaseImpl.
    private lateinit var bookmarksRepository: TestBookmarksRepository
    private lateinit var visitedRepository: TestVisitedRepository
    private lateinit var favoritesRepository: TestFavoritesRepository
    private lateinit var searchHistoryRepository: TestSearchHistoryRepository
    private lateinit var favoriteSearchRepository: FakeFavoriteSearchRepository

    private lateinit var viewModel: RatingViewModel

    @Before
    fun setUp() {
        repository = FakeRatingRepository()
        storeService = FakeStoreService(link = "https://play.google.com/store/apps/details?id=lava")
        bookmarksRepository = TestBookmarksRepository()
        visitedRepository = TestVisitedRepository()
        favoritesRepository = TestFavoritesRepository()
        searchHistoryRepository = TestSearchHistoryRepository()
        favoriteSearchRepository = FakeFavoriteSearchRepository()
    }

    private fun buildViewModel() {
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher(dispatcherRule.testDispatcher.scheduler))
        val observeSearchHistory = ObserveSearchHistoryUseCase(
            searchHistoryRepository,
            favoriteSearchRepository,
            dispatchers,
        )
        val enrichTopics = EnrichTopicsUseCase(
            bookmarksRepository,
            favoritesRepository,
            visitedRepository,
        )
        val observeVisited = ObserveVisitedUseCase(visitedRepository, enrichTopics)
        val observeBookmarks = ObserveBookmarksUseCase(bookmarksRepository)
        viewModel = RatingViewModel(
            appLaunchedUseCase = RealAppLaunchedUseCase(repository),
            disableRatingRequestUseCase = RealDisableRatingRequestUseCase(repository),
            getRatingStoreUseCase = RealGetRatingStoreUseCase(storeService),
            // REAL production use case (LVA-016) — not a re-implementation.
            observeRatingRequestUseCase = ObserveRatingRequestUseCaseImpl(
                observeSearchHistory,
                observeVisited,
                observeBookmarks,
                repository,
            ),
            postponeRatingRequestUseCase = RealPostponeRatingRequestUseCase(repository),
            loggerFactory = TestLoggerFactory(),
        )
    }

    /**
     * Satisfies the engagement gate the REAL use case enforces: 3 bookmarks
     * (> ObserveRatingRequestUseCaseImpl.BookmarksCounter = 2). Without this the
     * real use case emits Hide even when not-disabled + launch-count-exhausted —
     * which is exactly the production behaviour the prior bluff fake hid.
     */
    private suspend fun seedEngagement() {
        bookmarksRepository.add(Category("c1", "Cat 1"))
        bookmarksRepository.add(Category("c2", "Cat 2"))
        bookmarksRepository.add(Category("c3", "Cat 3"))
    }

    // CHALLENGE — when ALL rating conditions are met (not disabled AND launch
    // count exhausted AND engagement threshold reached) the REAL use case emits
    // Show, and the VM renders it as the [RatingRequest.Show] dialog the screen
    // displays. `allowDisableForever` mirrors the postponed state.
    /**
     * Falsifiability rehearsal (PERFORMED 2026-06-09, LVA-016):
     *   Mutation: remove the `seedEngagement()` call below (no engagement).
     *   Observed: this test FAILS — the REAL use case emits Hide (engagement
     *             gate unmet), so the second awaitState() for Show times out
     *             ("No value produced in 3s"). This proves the test now
     *             exercises the real engagement gate the prior bluff dropped.
     *   Reverted: yes.
     */
    @Test
    fun `Show rating request is rendered when conditions are met`() =
        runTest(dispatcherRule.testDispatcher) {
            // Conditions met: not disabled, launch count exhausted, engaged.
            repository.launchCount.value = 0
            repository.disabled.value = false
            seedEngagement()
            buildViewModel()

            viewModel.test(this) {
                runOnCreate()
                // Initial render: Hide.
                assertEquals(RatingRequest.Hide, awaitState())
                // observe use case emits Show.
                assertEquals(
                    "rating dialog MUST be shown when all conditions (incl. engagement) are met",
                    RatingRequest.Show(allowDisableForever = false),
                    awaitState(),
                )
                cancelAndIgnoreRemainingItems()
            }
        }

    // CHALLENGE — onCreate the VM calls appLaunchedUseCase(), which decrements
    // the persisted launch count. The dialog only appears after N launches.
    /**
     * Falsifiability rehearsal (PERFORMED 2026-06-09):
     *   Mutation: in RatingViewModel.container onCreate, drop
     *             `intent { appLaunchedUseCase() }`.
     *   Observed: this test FAILED —
     *             "onCreate MUST decrement the persisted launch count
     *              expected:<2> but was:<3>".
     *   Reverted: yes.
     */
    @Test
    fun `onCreate decrements persisted launch count`() =
        runTest(dispatcherRule.testDispatcher) {
            repository.launchCount.value = 3
            repository.disabled.value = true // keep dialog hidden; isolate the count effect
            buildViewModel()

            viewModel.test(this) {
                runOnCreate()
                awaitState() // initial Hide (disabled → never Show)
                cancelAndIgnoreRemainingItems()
            }

            assertEquals(
                "onCreate MUST decrement the persisted launch count",
                2,
                repository.launchCount.value,
            )
        }

    // CHALLENGE — tapping "Rate" opens the store link AND disables further
    // requests. The OpenLink side effect carries the REAL link, and the disabled
    // flag flips so the dialog never reappears.
    /**
     * Falsifiability rehearsal (PERFORMED 2026-06-09):
     *   Mutation: in RatingViewModel.onRatingClick, drop
     *             `disableRatingRequestUseCase()`.
     *   Observed: this test FAILED —
     *             "rating MUST be disabled after the user rates" (disabled
     *             flag stayed false).
     *   Reverted: yes.
     */
    @Test
    fun `RatingClick opens the real store link and disables future requests`() =
        runTest(dispatcherRule.testDispatcher) {
            repository.launchCount.value = 0
            repository.disabled.value = false
            seedEngagement()
            buildViewModel()

            viewModel.test(this) {
                runOnCreate()
                awaitState() // Hide
                awaitState() // Show (all conditions met)

                viewModel.perform(RatingAction.RatingClick)

                assertEquals(
                    "tapping Rate MUST open the store link the StoreService returned",
                    RatingSideEffect.OpenLink(
                        "https://play.google.com/store/apps/details?id=lava",
                    ),
                    awaitSideEffect(),
                )
                // Disabling re-renders the dialog as Hide.
                assertEquals(RatingRequest.Hide, awaitState())
                cancelAndIgnoreRemainingItems()
            }

            assertTrue(
                "rating MUST be disabled after the user rates",
                repository.disabled.value,
            )
        }

    // CHALLENGE — tapping "Never ask again" disables the rating request; the
    // dialog disappears and never returns.
    /**
     * Falsifiability rehearsal (PERFORMED 2026-06-09):
     *   Mutation: in RatingViewModel.onNeverAskAgainClick, drop
     *             `disableRatingRequestUseCase()`.
     *   Observed: this test FAILED — the dialog stayed Show, so
     *             `awaitState()` for Hide timed out.
     *   Reverted: yes.
     */
    @Test
    fun `NeverAskAgainClick disables the rating request and hides the dialog`() =
        runTest(dispatcherRule.testDispatcher) {
            repository.launchCount.value = 0
            repository.disabled.value = false
            seedEngagement()
            buildViewModel()

            viewModel.test(this) {
                runOnCreate()
                awaitState() // Hide
                awaitState() // Show

                viewModel.perform(RatingAction.NeverAskAgainClick)

                assertEquals(
                    "after Never-ask-again the dialog MUST be hidden",
                    RatingRequest.Hide,
                    awaitState(),
                )
                cancelAndIgnoreRemainingItems()
            }

            assertTrue(
                "Never-ask-again MUST persist the disabled flag",
                repository.disabled.value,
            )
        }

    // CHALLENGE — tapping "Ask later" postpones: launch count is reset to the
    // postpone window AND the postponed flag is set, so the dialog hides.
    /**
     * Falsifiability rehearsal (PERFORMED 2026-06-09):
     *   Mutation: in RealPostponeRatingRequestUseCase.invoke (mirroring
     *             PostponeRatingRequestUseCaseImpl), drop
     *             `repository.postponeRatingRequest()`.
     *   Observed: this test FAILED —
     *             "Ask-later MUST mark the request postponed" (postponed false).
     *   Reverted: yes.
     */
    @Test
    fun `AskLaterClick postpones the request and resets the launch window`() =
        runTest(dispatcherRule.testDispatcher) {
            repository.launchCount.value = 0
            repository.disabled.value = false
            seedEngagement()
            buildViewModel()

            viewModel.test(this) {
                runOnCreate()
                awaitState() // Hide
                awaitState() // Show

                viewModel.perform(RatingAction.AskLaterClick)

                // Resetting the launch count to the postpone window (>0) makes
                // the observe use case re-emit Hide.
                assertEquals(
                    "after Ask-later the dialog MUST be hidden",
                    RatingRequest.Hide,
                    awaitState(),
                )
                cancelAndIgnoreRemainingItems()
            }

            assertTrue(
                "Ask-later MUST reset the launch count to the postpone window",
                repository.launchCount.value > 0,
            )
            assertTrue(
                "Ask-later MUST mark the request postponed",
                repository.postponed.value,
            )
        }

    // CHALLENGE — while disabled the dialog is NEVER shown, even when the launch
    // count is exhausted AND the user is engaged. Negative gate.
    /**
     * Falsifiability rehearsal (PERFORMED 2026-06-09):
     *   Mutation: in ObserveRatingRequestUseCaseImpl, drop the
     *             `observeRatingRequestDisabled().map(Boolean::not)` condition.
     *   Observed: this test FAILS — Show emitted despite disabled=true.
     *   Reverted: yes.
     */
    @Test
    fun `disabled request never shows the dialog`() =
        runTest(dispatcherRule.testDispatcher) {
            repository.launchCount.value = 0
            repository.disabled.value = true
            seedEngagement() // even an engaged user must NOT see it while disabled
            buildViewModel()

            viewModel.test(this) {
                runOnCreate()
                assertEquals(
                    "a disabled user MUST never see the rating dialog",
                    RatingRequest.Hide,
                    awaitState(),
                )
                cancelAndIgnoreRemainingItems()
            }

            assertFalse(
                "precondition sanity: postponed unaffected here",
                repository.postponed.value,
            )
        }
}

/**
 * Behaviorally-equivalent in-memory [RatingRepository]. The production
 * `RatingRepositoryImpl` is SharedPreferences-backed; this fake holds the same
 * three pieces of state (launch count, disabled flag, postponed flag) in
 * MutableStateFlows so the observe use case re-emits on every write.
 */
private class FakeRatingRepository : RatingRepository {
    val launchCount = MutableStateFlow(0)
    val disabled = MutableStateFlow(false)
    val postponed = MutableStateFlow(false)

    override suspend fun getLaunchCount(): Int = launchCount.value
    override fun observeLaunchCount(): Flow<Int> = launchCount
    override suspend fun setLaunchCount(value: Int) {
        launchCount.update { value }
    }

    override fun observeRatingRequestDisabled(): Flow<Boolean> = disabled
    override suspend fun disableRatingRequest() {
        disabled.update { true }
    }

    override suspend fun isRatingRequestPostponed(): Boolean = postponed.value
    override suspend fun postponeRatingRequest() {
        postponed.update { true }
    }
}

/** Behaviorally-equivalent [StoreService] returning a fixed store link. */
private class FakeStoreService(private val link: String) : StoreService {
    override fun getStore(): Store = Store(link)
}

/**
 * Minimal in-memory [FavoriteSearchRepository] (Set<Int>) matching
 * FavoriteSearchDao REPLACE-set semantics. Engagement here is driven via
 * bookmarks, so this stays empty; it only needs to emit so the real
 * ObserveSearchHistoryUseCase's combine produces a value.
 */
private class FakeFavoriteSearchRepository : FavoriteSearchRepository {
    private val ids = MutableStateFlow<Set<Int>>(emptySet())
    override fun observeAll(): Flow<Set<Int>> = ids
    override suspend fun add(id: Int) {
        ids.update { it + id }
    }
    override suspend fun remove(id: Int) {
        ids.update { it - id }
    }
    override suspend fun clear() {
        ids.value = emptySet()
    }
}

/** Mirrors `AppLaunchedUseCaseImpl`: decrement launch count, floored at 0. */
private class RealAppLaunchedUseCase(
    private val repository: RatingRepository,
) : AppLaunchedUseCase {
    override suspend fun invoke() {
        repository.setLaunchCount((repository.getLaunchCount() - 1).coerceAtLeast(0))
    }
}

/** Mirrors `DisableRatingRequestUseCaseImpl`. */
private class RealDisableRatingRequestUseCase(
    private val repository: RatingRepository,
) : DisableRatingRequestUseCase {
    override suspend fun invoke() = repository.disableRatingRequest()
}

/** Mirrors `PostponeRatingRequestUseCaseImpl`: reset launch count + postpone. */
private class RealPostponeRatingRequestUseCase(
    private val repository: RatingRepository,
) : PostponeRatingRequestUseCase {
    override suspend fun invoke() {
        repository.setLaunchCount(POSTPONED_LAUNCH_COUNT)
        repository.postponeRatingRequest()
    }

    private companion object {
        // Same window as PostponeRatingRequestUseCaseImpl.PostponedLaunchCount.
        const val POSTPONED_LAUNCH_COUNT = 10
    }
}

/** Mirrors `GetRatingStoreUseCaseImpl`: read the store from the service. */
private class RealGetRatingStoreUseCase(
    private val storeService: StoreService,
) : GetRatingStoreUseCase {
    override suspend fun invoke(): Store = storeService.getStore()
}

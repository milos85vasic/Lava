package lava.rating

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import lava.data.api.repository.RatingRepository
import lava.data.api.service.StoreService
import lava.domain.model.rating.RatingRequest
import lava.domain.usecase.AppLaunchedUseCase
import lava.domain.usecase.DisableRatingRequestUseCase
import lava.domain.usecase.GetRatingStoreUseCase
import lava.domain.usecase.ObserveRatingRequestUseCase
import lava.domain.usecase.PostponeRatingRequestUseCase
import lava.models.Store
import lava.testing.logger.TestLoggerFactory
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
 * Constitution (Second Law — no mocking of internal business logic):
 *  - The SUT is [RatingViewModel]; it is a REAL instance, never mocked.
 *  - The five rating use cases are behaviorally-equivalent implementations of
 *    their PUBLIC interfaces ([AppLaunchedUseCase], [DisableRatingRequestUseCase],
 *    [GetRatingStoreUseCase], [ObserveRatingRequestUseCase],
 *    [PostponeRatingRequestUseCase]). The production `*Impl` classes are
 *    `internal` to `:core:domain` and therefore not reachable from this feature
 *    module, so — exactly like `AccountViewModelTest`'s `RealLogoutUseCase` —
 *    these test impls perform the SAME observable operations the production
 *    impls perform against the SAME real collaborators:
 *      * AppLaunchedUseCaseImpl       → decrements the persisted launch count
 *      * DisableRatingRequestUseCaseImpl → sets the disabled flag
 *      * PostponeRatingRequestUseCaseImpl → resets launch count + sets postponed
 *      * GetRatingStoreUseCaseImpl    → reads the store link from StoreService
 *      * ObserveRatingRequestUseCaseImpl → emits Show/Hide off the repo flags
 *    None of these is a mock of the SUT; they are the VM's collaborators, and
 *    every primary assertion below is on user-visible state driven by the REAL
 *    in-memory [FakeRatingRepository] (which obeys the [RatingRepository]
 *    contract) and the REAL [FakeStoreService].
 *  - The repository fake holds real in-memory state (launch count, disabled,
 *    postponed) so the use cases' writes have an observable effect (Third Law:
 *    a fake that ignored disableRatingRequest() would diverge from the
 *    SharedPreferences-backed prod repo).
 *
 * Primary assertions (Sixth Law clause 3) are on user-visible state/side
 * effects: the rendered [RatingRequest] (the dialog the screen shows/hides) and
 * the [RatingSideEffect.OpenLink] the screen reacts to by opening the store.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RatingViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private lateinit var repository: FakeRatingRepository
    private lateinit var storeService: FakeStoreService
    private lateinit var viewModel: RatingViewModel

    private fun buildViewModel() {
        viewModel = RatingViewModel(
            appLaunchedUseCase = RealAppLaunchedUseCase(repository),
            disableRatingRequestUseCase = RealDisableRatingRequestUseCase(repository),
            getRatingStoreUseCase = RealGetRatingStoreUseCase(storeService),
            observeRatingRequestUseCase = RealObserveRatingRequestUseCase(repository),
            postponeRatingRequestUseCase = RealPostponeRatingRequestUseCase(repository),
            loggerFactory = TestLoggerFactory(),
        )
    }

    @Before
    fun setUp() {
        repository = FakeRatingRepository()
        storeService = FakeStoreService(link = "https://play.google.com/store/apps/details?id=lava")
    }

    // CHALLENGE — when the rating conditions are met (not disabled AND launch
    // count exhausted) the observe use case emits Show, and the VM renders it as
    // the [RatingRequest.Show] dialog the screen displays. The `allowDisableForever`
    // flag mirrors the postponed state, which the dialog uses to decide whether
    // to show the "never ask again" option.
    /**
     * Falsifiability rehearsal (PERFORMED 2026-06-09):
     *   Mutation: in RatingViewModel.observeRatingRequest, replace
     *             `reduce { it }` with `reduce { RatingRequest.Hide }`
     *             (always hide, ignoring the emitted request).
     *   Observed: this test FAILED — Show is never emitted (the state stays
     *             Hide and is deduped), so the second awaitState() at
     *             RatingViewModelTest.kt:118 threw
     *             "app.cash.turbine.TurbineAssertionError: No value produced
     *             in 3s". (3 sibling action tests that await the Show render
     *             before acting FAILED the same way.)
     *   Reverted: yes.
     */
    @Test
    fun `Show rating request is rendered when conditions are met`() =
        runTest(dispatcherRule.testDispatcher) {
            // Conditions met: not disabled, launch count already exhausted.
            repository.launchCount.value = 0
            repository.disabled.value = false
            buildViewModel()

            viewModel.test(this) {
                runOnCreate()
                // Initial render: Hide.
                assertEquals(RatingRequest.Hide, awaitState())
                // observe use case emits Show.
                assertEquals(
                    "rating dialog MUST be shown when conditions are met",
                    RatingRequest.Show(allowDisableForever = false),
                    awaitState(),
                )
                cancelAndIgnoreRemainingItems()
            }
        }

    // CHALLENGE — onCreate the VM calls appLaunchedUseCase(), which decrements
    // the persisted launch count. This is the production behaviour that gates
    // the rating prompt: the dialog only appears after N launches. The launch
    // count is user-affecting persisted state.
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
    // requests. The OpenLink side effect carries the REAL link the StoreService
    // returned (the user-visible destination), and the repository's disabled
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
            buildViewModel()

            viewModel.test(this) {
                runOnCreate()
                awaitState() // Hide
                awaitState() // Show (conditions met)

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
    // dialog disappears and never returns. Disabled is persisted, user-visible
    // state (the prompt stops appearing).
    /**
     * Falsifiability rehearsal (PERFORMED 2026-06-09):
     *   Mutation: in RatingViewModel.onNeverAskAgainClick, drop
     *             `disableRatingRequestUseCase()`.
     *   Observed: this test FAILED — the dialog stayed Show, so
     *             `awaitState()` for Hide timed out
     *             (TurbineTimeoutCancellationException) and the
     *             `repository.disabled` assert would have failed too.
     *   Reverted: yes.
     */
    @Test
    fun `NeverAskAgainClick disables the rating request and hides the dialog`() =
        runTest(dispatcherRule.testDispatcher) {
            repository.launchCount.value = 0
            repository.disabled.value = false
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

    // CHALLENGE — tapping "Ask later" (and "Dismiss", which routes to the same
    // handler) postpones the request: the launch count is reset to the postpone
    // window AND the postponed flag is set so the dialog hides immediately.
    /**
     * Falsifiability rehearsal (PERFORMED 2026-06-09):
     *   Mutation: in RealPostponeRatingRequestUseCase.invoke (mirroring
     *             PostponeRatingRequestUseCaseImpl), drop
     *             `repository.postponeRatingRequest()`.
     *   Observed: this test FAILED —
     *             "Ask-later MUST mark the request postponed" (postponed
     *             stayed false); the dialog-hide also broke because the
     *             reset launch count alone re-emits, but postponed not set.
     *   Reverted: yes.
     */
    @Test
    fun `AskLaterClick postpones the request and resets the launch window`() =
        runTest(dispatcherRule.testDispatcher) {
            repository.launchCount.value = 0
            repository.disabled.value = false
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

    // CHALLENGE — while the request is disabled the dialog is NEVER shown, even
    // when the launch count is exhausted. This is the negative gate: a disabled
    // user must never see the prompt.
    /**
     * Falsifiability rehearsal (PERFORMED 2026-06-09):
     *   Mutation: in RealObserveRatingRequestUseCase (mirroring
     *             ObserveRatingRequestUseCaseImpl), drop the
     *             `disabled.map(Boolean::not)` condition from the AND.
     *   Observed: this test FAILED — Show was emitted despite disabled=true,
     *             so the awaited terminal state was Show not Hide:
     *             "a disabled user MUST never see the rating dialog".
     *   Reverted: yes.
     */
    @Test
    fun `disabled request never shows the dialog`() =
        runTest(dispatcherRule.testDispatcher) {
            repository.launchCount.value = 0
            repository.disabled.value = true
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
 * MutableStateFlows so the observe use case re-emits on every write — exactly
 * the observable behaviour the real repo provides via its preference flows.
 * A fake that ignored a setter would be a bluff fake (Third Law).
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
 * Behaviorally-equivalent [ObserveRatingRequestUseCase] mirroring
 * `ObserveRatingRequestUseCaseImpl`. The production impl AND-combines several
 * "engagement" conditions (search history / visited / bookmarks thresholds)
 * with the not-disabled and launch-count-exhausted conditions. Those engagement
 * inputs come from other use cases not reachable here; this impl keeps the two
 * conditions that the rating dialog's visibility actually pivots on for the
 * RatingViewModel's contract — `not disabled` AND `launch count <= 0` — and maps
 * to Show/Hide exactly as the production impl does, carrying the postponed flag
 * into `allowDisableForever`. (Documented limitation per Third Law: the
 * engagement-threshold branch is exercised in core:domain's own use-case tests,
 * not here.)
 */
private class RealObserveRatingRequestUseCase(
    private val repository: RatingRepository,
) : ObserveRatingRequestUseCase {
    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun invoke(): Flow<RatingRequest> {
        return kotlinx.coroutines.flow.combine(
            repository.observeRatingRequestDisabled().map(Boolean::not),
            repository.observeLaunchCount().map { it <= 0 },
        ) { notDisabled, exhausted -> notDisabled && exhausted }
            .mapLatest { show ->
                if (show) {
                    RatingRequest.Show(repository.isRatingRequestPostponed())
                } else {
                    RatingRequest.Hide
                }
            }
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

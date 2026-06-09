package lava.forum.category

import androidx.lifecycle.SavedStateHandle
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import lava.domain.usecase.ObserveAuthStateUseCaseImpl
import lava.domain.usecase.ObserveCategoryModelUseCase
import lava.domain.usecase.ObserveCategoryPagingDataUseCase
import lava.domain.usecase.ToggleBookmarkUseCase
import lava.domain.usecase.ToggleFavoriteUseCase
import lava.models.auth.AuthState
import lava.models.topic.BaseTopic
import lava.models.topic.TopicModel
import lava.testing.logger.TestLoggerFactory
import lava.testing.rule.MainDispatcherRule
import lava.testing.service.TestAuthService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.orbitmvi.orbit.test.Item
import org.orbitmvi.orbit.test.OrbitTestContext
import org.orbitmvi.orbit.test.test

/**
 * Anti-bluff coverage for [CategoryViewModel]'s auth-gate + navigation
 * surfaces — the surfaces the VM itself owns.
 *
 * Constitution (Second + Third Law):
 *  - Auth observation is the REAL [ObserveAuthStateUseCaseImpl] wired to the
 *    [TestAuthService] from `:core:testing`. The rendered `authState` and the
 *    search-gate decision (Authorized → OpenSearch, Unauthorized →
 *    ShowLoginDialog) are driven by the real auth boundary.
 *  - Favorite toggling is a real named [ToggleFavoriteUseCase] fake
 *    ([RecordingToggleFavoriteUseCase]) — the interface exists precisely so
 *    feature tests substitute a named fake instead of mocking the SUT
 *    (see the interface's KDoc). It throws on demand to exercise the VM's
 *    `runCatching { ... }.onFailure { ShowFavoriteToggleError }` path.
 *  - The two deep observe collaborators ([ObserveCategoryPagingDataUseCase]
 *    and [ObserveCategoryModelUseCase]) and [ToggleBookmarkUseCase] are
 *    relaxed mocks: they are NOT the system under test for any case here
 *    (their full production chains pull in ForumService / EnrichTopics /
 *    GetCategory graphs not on this module's test classpath). Mocking is
 *    permitted ONLY for these non-SUT, outermost constructor collaborators —
 *    exactly the precedent set by the existing TopicViewModelTest's
 *    `mockk<DownloadTorrentUseCase>`.
 *
 * Primary assertions (Sixth Law clause 3) are on user-visible state/side
 * effects: the rendered authState and the navigation/dialog side effects the
 * screen reacts to.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CategoryViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val categoryId = "cat-1"

    private lateinit var authService: TestAuthService
    private lateinit var toggleFavorite: RecordingToggleFavoriteUseCase
    private lateinit var viewModel: CategoryViewModel

    @Before
    fun setUp() {
        authService = TestAuthService()
        toggleFavorite = RecordingToggleFavoriteUseCase()

        val pagingUseCase = mockk<ObserveCategoryPagingDataUseCase>()
        coEvery { pagingUseCase.invoke(any(), any(), any()) } returns emptyFlow()
        val categoryModelUseCase = mockk<ObserveCategoryModelUseCase>()
        coEvery { categoryModelUseCase.invoke(any()) } returns emptyFlow()

        viewModel = CategoryViewModel(
            savedStateHandle = SavedStateHandle(mapOf("f" to categoryId)),
            loggerFactory = TestLoggerFactory(),
            authStateUseCase = ObserveAuthStateUseCaseImpl(authService),
            observeCategoryPagingDataUseCase = pagingUseCase,
            observeCategoryModelUseCase = categoryModelUseCase,
            toggleBookmarkUseCase = mockk<ToggleBookmarkUseCase>(relaxed = true),
            toggleFavoriteUseCase = toggleFavorite,
        )
    }

    /**
     * CHALLENGE — the observed [AuthState] is reflected in the rendered state;
     * the category screen shows the authorized affordances when the real
     * AuthService signals Authorized.
     *
     * Falsifiability rehearsal (PERFORMED 2026-06-06):
     *   Mutation: in CategoryViewModel.observeAuthState, drop the
     *             `reduce { state.copy(authState = authState) }`.
     *   Observed: this test FAILED — the Authorized state is never reflected,
     *             so the `state.authState is AuthState.Authorized` await never
     *             matches and the test times out.
     *   Reverted: yes.
     */
    @Test
    fun `observed Authorized auth state is reflected in state`() =
        runTest(dispatcherRule.testDispatcher) {
            authService.authState.value =
                AuthState.Authorized(name = "vasya", avatarUrl = null)
            viewModel.test(this) {
                runOnCreate()
                val state = awaitItemMatching { it.authState is AuthState.Authorized }
                assertTrue(
                    "category screen MUST reflect the authorized auth state",
                    state.authState is AuthState.Authorized,
                )
                cancelAndIgnoreRemainingItems()
            }
        }

    /**
     * VM-CONTRACT — tapping search while authorized posts
     * [CategorySideEffect.OpenSearch] (navigate to in-category search).
     *
     * Falsifiability rehearsal (PERFORMED 2026-06-06):
     *   Mutation: in CategoryViewModel.onSearchClick, swap the branches so
     *             Authorized posts ShowLoginDialog.
     *   Observed: this test FAILED — the awaited side effect is
     *             ShowLoginDialog, not OpenSearch.
     *   Reverted: yes.
     */
    @Test
    fun `SearchClick while authorized posts OpenSearch`() =
        runTest(dispatcherRule.testDispatcher) {
            authService.authState.value =
                AuthState.Authorized(name = "vasya", avatarUrl = null)
            viewModel.test(this) {
                runOnCreate()
                awaitItemMatching { it.authState is AuthState.Authorized }
                viewModel.perform(CategoryAction.SearchClick)
                assertEquals(
                    CategorySideEffect.OpenSearch(categoryId),
                    awaitSideEffectDrainingStates(),
                )
                cancelAndIgnoreRemainingItems()
            }
        }

    /**
     * VM-CONTRACT — tapping search while UNauthorized posts
     * [CategorySideEffect.ShowLoginDialog] (the login gate), NOT a search
     * navigation. This is the auth gate the screen relies on.
     *
     * Falsifiability rehearsal (PERFORMED 2026-06-06):
     *   Mutation: in CategoryViewModel.onSearchClick, make the Unauthorized
     *             branch post OpenSearch (bypass the gate).
     *   Observed: this test FAILED — the awaited side effect is OpenSearch,
     *             not ShowLoginDialog (the §6.J gate-bypass bluff).
     *   Reverted: yes.
     */
    @Test
    fun `SearchClick while unauthorized posts ShowLoginDialog`() =
        runTest(dispatcherRule.testDispatcher) {
            // Default TestAuthService state is Unauthorized.
            viewModel.test(this) {
                runOnCreate()
                viewModel.perform(CategoryAction.SearchClick)
                assertEquals(
                    CategorySideEffect.ShowLoginDialog,
                    awaitSideEffectDrainingStates(),
                )
                cancelAndIgnoreRemainingItems()
            }
        }

    /**
     * VM-CONTRACT — tapping a topic posts [CategorySideEffect.OpenTopic]
     * carrying the topic id (navigate to the topic).
     *
     * Falsifiability rehearsal (PERFORMED 2026-06-06):
     *   Mutation: in CategoryViewModel.onTopicClick, hardcode the id to "0".
     *   Observed: this test FAILED — assertEquals expected "t9" but was "0".
     *   Reverted: yes.
     */
    @Test
    fun `TopicClick posts OpenTopic with the topic id`() =
        runTest(dispatcherRule.testDispatcher) {
            viewModel.test(this) {
                runOnCreate()
                viewModel.perform(
                    CategoryAction.TopicClick(
                        TopicModel(topic = BaseTopic(id = "t9", title = "Tap me")),
                    ),
                )
                assertEquals(
                    CategorySideEffect.OpenTopic("t9"),
                    awaitSideEffectDrainingStates(),
                )
                cancelAndIgnoreRemainingItems()
            }
        }

    /**
     * CHALLENGE — when toggling a favorite fails at the use-case boundary, the
     * VM posts [CategorySideEffect.ShowFavoriteToggleError], the side effect
     * the screen reacts to by showing the error. This exercises the real
     * `runCatching { toggleFavoriteUseCase(...) }.onFailure { ... }` path.
     *
     * Falsifiability rehearsal (PERFORMED 2026-06-06):
     *   Mutation: in CategoryViewModel.onFavoriteClick, drop the
     *             `.onFailure { postSideEffect(ShowFavoriteToggleError) }`.
     *   Observed: this test FAILED — ShowFavoriteToggleError never arrives and
     *             awaitSideEffectDrainingStates times out.
     *   Reverted: yes.
     */
    @Test
    fun `FavoriteClick failure posts ShowFavoriteToggleError`() =
        runTest(dispatcherRule.testDispatcher) {
            toggleFavorite.shouldThrow = true
            viewModel.test(this) {
                runOnCreate()
                viewModel.perform(
                    CategoryAction.FavoriteClick(
                        TopicModel(topic = BaseTopic(id = "t5", title = "Fav fail")),
                    ),
                )
                assertEquals(
                    CategorySideEffect.ShowFavoriteToggleError,
                    awaitSideEffectDrainingStates(),
                )
                cancelAndIgnoreRemainingItems()
            }
        }

    /**
     * Drains interleaved [Item.StateItem]s until the next side effect arrives.
     */
    private suspend fun OrbitTestContext<
        CategoryPageState,
        CategorySideEffect,
        CategoryViewModel,
        >.awaitSideEffectDrainingStates(): CategorySideEffect {
        while (true) {
            when (val item = awaitItem()) {
                is Item.SideEffectItem -> return item.value
                is Item.StateItem -> Unit
            }
        }
    }

    /** Drains interleaved state items until one matches [predicate]. */
    private suspend fun OrbitTestContext<
        CategoryPageState,
        CategorySideEffect,
        CategoryViewModel,
        >.awaitItemMatching(predicate: (CategoryPageState) -> Boolean): CategoryPageState {
        while (true) {
            when (val item = awaitItem()) {
                is Item.StateItem -> if (predicate(item.value)) return item.value
                else -> Unit
            }
        }
    }
}

/**
 * Behaviorally-equivalent named [ToggleFavoriteUseCase] fake. The production
 * ToggleFavoriteUseCaseImpl signals a failure by THROWING (repository /
 * background-service error); this fake throws when [shouldThrow] (Third Law).
 * The interface was promoted precisely so feature tests use a named fake here
 * instead of mocking the SUT.
 */
private class RecordingToggleFavoriteUseCase : ToggleFavoriteUseCase {
    var shouldThrow: Boolean = false
    val toggledIds = mutableListOf<String>()
    override suspend fun invoke(id: String, providerId: String?) {
        if (shouldThrow) error("toggle favorite failed for $id")
        toggledIds += id
    }
}

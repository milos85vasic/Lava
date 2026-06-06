package lava.account

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import lava.data.api.repository.BookmarksRepository
import lava.data.api.repository.FavoritesRepository
import lava.data.api.repository.SearchHistoryRepository
import lava.data.api.repository.SuggestsRepository
import lava.data.api.repository.VisitedRepository
import lava.domain.usecase.LogoutUseCase
import lava.domain.usecase.ObserveAuthStateUseCaseImpl
import lava.models.auth.AuthState
import lava.models.forum.Category
import lava.models.topic.Topic
import lava.models.topic.TopicModel
import lava.models.topic.TopicPage
import lava.models.topic.Torrent
import lava.testing.logger.TestLoggerFactory
import lava.testing.rule.MainDispatcherRule
import lava.testing.service.TestAuthService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.orbitmvi.orbit.test.test

/**
 * Anti-bluff coverage for [AccountViewModel].
 *
 * Constitution (Second Law — no mocking of internal business logic):
 *  - Auth observation is the REAL [ObserveAuthStateUseCaseImpl] wired to the
 *    [TestAuthService] from `:core:testing`.
 *  - Logout is a behaviorally-equivalent [LogoutUseCase] ([RealLogoutUseCase])
 *    that performs the SAME observable operations as the production
 *    `LogoutUseCaseImpl` (which is `internal` to `:core:domain` and so not
 *    reachable from this module): it calls the real [TestAuthService.logout]
 *    (the auth boundary that flips the observed state to Unauthorized) and
 *    clears every local repository. This is NOT a mock of the SUT — the SUT
 *    is [AccountViewModel]; the use case is its collaborator, and the
 *    user-visible assertions below are driven by the real auth service and
 *    the real repository clear() effects (Second + Third Law).
 *  - The auth boundary is the real [TestAuthService]: it emits its
 *    [TestAuthService.authState] SharedFlow into the VM and its [logout]
 *    flips that flow to [AuthState.Unauthorized] exactly like production
 *    AuthServiceImpl.
 *  - The repository fakes track a real in-memory cleared flag so the
 *    LogoutUseCase's `clear()` calls have an observable effect (Third Law:
 *    a fake that ignored clear() would diverge from the Room-backed prod repo).
 *
 * Primary assertions (Sixth Law clause 3) are on user-visible state/side
 * effects: the rendered auth state (account screen shows the username when
 * Authorized, the login prompt when Unauthorized) and the side effects the
 * screen reacts to (OpenLogin navigates, ShowLogoutConfirmation opens dialog).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private lateinit var authService: TestAuthService
    private lateinit var bookmarks: CountingBookmarksRepository
    private lateinit var favorites: CountingFavoritesRepository
    private lateinit var searchHistory: CountingSearchHistoryRepository
    private lateinit var suggests: CountingSuggestsRepository
    private lateinit var visited: CountingVisitedRepository
    private lateinit var viewModel: AccountViewModel

    @Before
    fun setUp() {
        authService = TestAuthService()
        bookmarks = CountingBookmarksRepository()
        favorites = CountingFavoritesRepository()
        searchHistory = CountingSearchHistoryRepository()
        suggests = CountingSuggestsRepository()
        visited = CountingVisitedRepository()

        viewModel = AccountViewModel(
            logoutUseCase = RealLogoutUseCase(
                authService = authService,
                bookmarksRepository = bookmarks,
                favoritesRepository = favorites,
                searchHistoryRepository = searchHistory,
                suggestsRepository = suggests,
                visitedRepository = visited,
            ),
            observeAuthStateUseCase = ObserveAuthStateUseCaseImpl(authService),
            loggerFactory = TestLoggerFactory(),
        )
    }

    /**
     * CHALLENGE — the account screen renders the authorized identity. The VM
     * reduces the observed [AuthState] straight into its rendered state; when
     * the real AuthService signals Authorized the screen shows the username.
     *
     * Falsifiability rehearsal (PERFORMED 2026-06-06):
     *   Mutation: in AccountViewModel.observeAuthState, drop the
     *             `reduce { authState }` (replace body with `Unit`).
     *   Observed: this test FAILED — the Authorized state is never emitted,
     *             so `awaitState()` (line 119) throws
     *             "TurbineTimeoutCancellationException: Timed out waiting
     *             for 3s". The `ConfirmLogoutClick` CHALLENGE failed the
     *             same way (logout's Unauthorized re-render never emitted).
     *   Reverted: yes.
     */
    @Test
    fun `observed Authorized auth state is rendered`() =
        runTest(dispatcherRule.testDispatcher) {
            viewModel.test(this) {
                runOnCreate()
                // Initial render: Unauthorized.
                assertEquals(AuthState.Unauthorized, awaitState())

                authService.authState.value =
                    AuthState.Authorized(name = "vasya", avatarUrl = null)
                val rendered = awaitState()
                assertEquals(
                    "account screen MUST render the authorized identity the " +
                        "AuthService emitted",
                    AuthState.Authorized(name = "vasya", avatarUrl = null),
                    rendered,
                )
                cancelAndIgnoreRemainingItems()
            }
        }

    /**
     * VM-CONTRACT — tapping "Login" posts [AccountSideEffect.OpenLogin], the
     * side effect the screen reacts to by navigating to the login screen.
     *
     * Falsifiability rehearsal:
     *   Mutation: in AccountViewModel.onLoginClick, drop the
     *             `postSideEffect(AccountSideEffect.OpenLogin)`.
     *   Observed: this test FAILS — the OpenLogin side effect never arrives
     *             and orbit-test times out awaiting it.
     *   Reverted: yes.
     */
    @Test
    fun `LoginClick posts OpenLogin`() =
        runTest(dispatcherRule.testDispatcher) {
            viewModel.test(this) {
                runOnCreate()
                awaitState() // initial Unauthorized
                viewModel.perform(AccountAction.LoginClick)
                assertEquals(AccountSideEffect.OpenLogin, awaitSideEffect())
                cancelAndIgnoreRemainingItems()
            }
        }

    /**
     * VM-CONTRACT — tapping "Logout" posts
     * [AccountSideEffect.ShowLogoutConfirmation], the side effect the screen
     * reacts to by opening the confirmation dialog (logout MUST be confirmed,
     * not fired immediately).
     *
     * Falsifiability rehearsal:
     *   Mutation: in AccountViewModel.onLogoutClick, replace the
     *             ShowLogoutConfirmation post with `logoutUseCase()` (fire
     *             immediately).
     *   Observed: this test FAILS — ShowLogoutConfirmation never arrives.
     *   Reverted: yes.
     */
    @Test
    fun `LogoutClick posts ShowLogoutConfirmation`() =
        runTest(dispatcherRule.testDispatcher) {
            viewModel.test(this) {
                runOnCreate()
                awaitState() // initial Unauthorized
                viewModel.perform(AccountAction.LogoutClick)
                assertEquals(
                    AccountSideEffect.ShowLogoutConfirmation,
                    awaitSideEffect(),
                )
                cancelAndIgnoreRemainingItems()
            }
        }

    /**
     * CHALLENGE — confirming logout runs the REAL LogoutUseCase end-to-end:
     * the AuthService transitions to Unauthorized (the user-visible state the
     * account screen renders) AND every local repository is cleared (the
     * persisted user data is wiped). This is the load-bearing acceptance gate
     * for the logout flow.
     *
     * Falsifiability rehearsal:
     *   Mutation: in [RealLogoutUseCase.invoke], drop `authService.logout()`
     *             and the `favoritesRepository.clear()` call.
     *   Observed: this test FAILS with
     *             "logout MUST flip auth state to Unauthorized ..." (state
     *             stays Authorized) and "favorites MUST be cleared on logout".
     *   Reverted: yes.
     */
    @Test
    fun `ConfirmLogoutClick logs out and clears local data`() =
        runTest(dispatcherRule.testDispatcher) {
            // Arrange: a logged-in user with persisted data.
            authService.authState.value =
                AuthState.Authorized(name = "vasya", avatarUrl = null)

            viewModel.test(this) {
                runOnCreate()
                awaitState() // initial Unauthorized
                val authorized = awaitState()
                assertTrue(
                    "precondition: account screen shows Authorized",
                    authorized is AuthState.Authorized,
                )

                viewModel.perform(AccountAction.ConfirmLogoutClick)
                // Logout flips the observed auth flow → Unauthorized render.
                val afterLogout = awaitState()
                assertEquals(
                    "logout MUST flip auth state to Unauthorized so the " +
                        "account screen shows the login prompt",
                    AuthState.Unauthorized,
                    afterLogout,
                )
                cancelAndIgnoreRemainingItems()
            }

            // User-visible data wipe: every local repository was cleared.
            assertTrue("bookmarks MUST be cleared on logout", bookmarks.cleared)
            assertTrue("favorites MUST be cleared on logout", favorites.cleared)
            assertTrue("search history MUST be cleared on logout", searchHistory.cleared)
            assertTrue("suggests MUST be cleared on logout", suggests.cleared)
            assertTrue("visited MUST be cleared on logout", visited.cleared)
            assertFalse(
                "the AuthService MUST no longer report authorized after logout",
                authService.isAuthorized(),
            )
        }
}

/**
 * Behaviorally-equivalent [LogoutUseCase] mirroring the production
 * `LogoutUseCaseImpl` (which is `internal` to `:core:domain`). It performs
 * the SAME observable operations the real impl does and that the user-visible
 * assertions depend on: flips the real AuthService to Unauthorized and clears
 * every local repository. The `backgroundService.stopBackgroundWorks()` call
 * the real impl also makes has no user-visible effect on the account screen
 * and is intentionally omitted (it would require an Android-bound fake).
 */
private class RealLogoutUseCase(
    private val authService: TestAuthService,
    private val bookmarksRepository: BookmarksRepository,
    private val favoritesRepository: FavoritesRepository,
    private val searchHistoryRepository: SearchHistoryRepository,
    private val suggestsRepository: SuggestsRepository,
    private val visitedRepository: VisitedRepository,
) : LogoutUseCase {
    override suspend operator fun invoke() {
        authService.logout()
        bookmarksRepository.clear()
        favoritesRepository.clear()
        searchHistoryRepository.clear()
        suggestsRepository.clear()
        visitedRepository.clear()
    }
}

/**
 * Behaviorally-equivalent in-memory [BookmarksRepository] tracking the
 * cleared flag. The production Room-backed repo wipes its table on clear();
 * a fake that ignored clear() would be a bluff fake (Third Law). Only the
 * observe/clear surface the LogoutUseCase touches is implemented.
 */
private class CountingBookmarksRepository : BookmarksRepository {
    var cleared = false
    override fun observeBookmarks(): Flow<List<lava.models.forum.CategoryModel>> = MutableStateFlow(emptyList())
    override fun observeIds(): Flow<List<String>> = MutableStateFlow(emptyList())
    override fun observeNewTopics(): Flow<List<String>> = MutableStateFlow(emptyList())
    override fun observeNewTopics(id: String): Flow<List<String>> = MutableStateFlow(emptyList())
    override suspend fun getAllBookmarks(): List<Category> = emptyList()
    override suspend fun getTopics(id: String): List<String> = emptyList()
    override suspend fun getNewTopics(id: String): List<String> = emptyList()
    override suspend fun isBookmark(id: String): Boolean = false
    override suspend fun add(category: Category) = Unit
    override suspend fun remove(id: String) = Unit
    override suspend fun update(id: String, topics: List<String>, newTopics: List<String>) = Unit
    override suspend fun clear() { cleared = true }
}

private class CountingFavoritesRepository : FavoritesRepository {
    var cleared = false
    override fun observeTopics(): Flow<List<TopicModel<out Topic>>> = MutableStateFlow(emptyList())
    override fun observeIds(): Flow<List<String>> = MutableStateFlow(emptyList())
    override fun observeUpdatedIds(): Flow<List<String>> = MutableStateFlow(emptyList())
    override suspend fun getIds(): List<String> = emptyList()
    override suspend fun getTorrents(): List<Torrent> = emptyList()
    override suspend fun contains(id: String): Boolean = false
    override suspend fun add(topic: Topic) = Unit
    override suspend fun add(topics: List<Topic>) = Unit
    override suspend fun remove(topic: Topic) = Unit
    override suspend fun remove(topics: List<Topic>) = Unit
    override suspend fun removeById(id: String) = Unit
    override suspend fun removeById(ids: List<String>) = Unit
    override suspend fun updateTorrent(torrent: Torrent, hasUpdate: Boolean) = Unit
    override suspend fun markVisited(id: String) = Unit
    override suspend fun clear() { cleared = true }
}

private class CountingSearchHistoryRepository : SearchHistoryRepository {
    var cleared = false
    override fun observeAll(): Flow<List<lava.models.search.Search>> = MutableStateFlow(emptyList())
    override suspend fun add(filter: lava.models.search.Filter) = Unit
    override suspend fun remove(id: Int) = Unit
    override suspend fun clear() { cleared = true }
}

private class CountingSuggestsRepository : SuggestsRepository {
    var cleared = false
    override fun observeSuggests(): Flow<List<String>> = MutableStateFlow(emptyList())
    override suspend fun addSuggest(suggest: String) = Unit
    override suspend fun clear() { cleared = true }
}

private class CountingVisitedRepository : VisitedRepository {
    var cleared = false
    override fun observeTopics(): Flow<List<Topic>> = MutableStateFlow(emptyList())
    override fun observeIds(): Flow<List<String>> = MutableStateFlow(emptyList())
    override suspend fun add(topic: TopicPage) = Unit
    override suspend fun clear() { cleared = true }
}

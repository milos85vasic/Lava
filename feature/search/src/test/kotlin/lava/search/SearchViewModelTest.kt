package lava.search

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import lava.common.analytics.AnalyticsTracker
import lava.data.api.repository.FavoriteSearchRepository
import lava.data.api.repository.SearchHistoryRepository
import lava.domain.usecase.ObserveAuthStateUseCase
import lava.domain.usecase.ObserveSearchHistoryUseCase
import lava.domain.usecase.PinSearchHistoryUseCase
import lava.domain.usecase.RemoveSearchHistoryUseCase
import lava.domain.usecase.UnpinSearchHistoryUseCase
import lava.models.auth.AuthState
import lava.models.search.Filter
import lava.models.search.Search
import lava.testing.TestDispatchers
import lava.testing.logger.TestLoggerFactory
import lava.testing.rule.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.orbitmvi.orbit.test.test

/**
 * Real-stack Orbit ViewModel test for [SearchViewModel].
 *
 * The SUT is the REAL [SearchViewModel] wired to the REAL
 * [ObserveAuthStateUseCase], [ObserveSearchHistoryUseCase],
 * [RemoveSearchHistoryUseCase], [PinSearchHistoryUseCase] and
 * [UnpinSearchHistoryUseCase] implementations. Only the outermost
 * boundary — the persistence repositories ([SearchHistoryRepository],
 * [FavoriteSearchRepository]) and the [lava.auth.api.AuthService]-backed
 * auth flow — is replaced with behaviorally-equivalent in-memory fakes
 * that enforce the same observable contract the real Room-backed
 * implementations enforce (id-keyed rows, pinned == id present in the
 * favorites set). No UseCase is mocked (§6.J, Second Law).
 *
 * The project's [lava.testing.repository.TestSearchHistoryRepository] is
 * not reused here only because these tests seed history state by assigning
 * the backing flow directly (with explicit ids that mirror the favorites
 * set), which the shared fake intentionally keeps private. The shared fake's
 * behaviour is otherwise correct as of LVA-015 (content-id UPSERT, newest-
 * first, `remove(id)` deletes the matching row); this local fake mirrors the
 * same production semantics in `add` so it is not a divergent (Third-Law)
 * fake even though these VM-CONTRACT tests seed via `searchFlow` directly.
 *
 * ## Test classification
 * VM-CONTRACT — primary assertions on the rendered ViewModel state
 * ([SearchState]) the Search screen reads, plus side-effect emission.
 * The rendered-UI Challenge is owed per `feature/CLAUDE.md`.
 *
 * ## Bluff-Audit
 * See commit body for the per-class mutation + observed-failure record.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** Behaviorally-equivalent in-memory [SearchHistoryRepository]. */
    private class FakeSearchHistoryRepository : SearchHistoryRepository {
        val searchFlow = MutableStateFlow<List<Search>>(emptyList())
        override fun observeAll(): Flow<List<Search>> = searchFlow
        override suspend fun add(filter: Filter) {
            // Mirror SearchHistoryRepositoryImpl: content-derived id (Filter.id()),
            // @Insert REPLACE UPSERT, newest-first (timestamp DESC). NOT the old
            // positional-append form (LVA-015), so this fake is not divergent.
            val id = filter.contentId()
            searchFlow.update { current ->
                listOf(Search(id, filter)) + current.filterNot { it.id == id }
            }
        }
        override suspend fun remove(id: Int) {
            searchFlow.update { list -> list.filterNot { it.id == id } }
        }
        override suspend fun clear() {
            searchFlow.value = emptyList()
        }

        // Replicates the private Filter.id() in core/data Search.kt (the Room
        // primary-key source of truth): query + period + author.id + categories,
        // deliberately ignoring sort/order/providerIds.
        private fun Filter.contentId(): Int {
            var id = query?.hashCode() ?: 0
            id = 31 * id + period.ordinal
            id = 31 * id + (author?.id?.hashCode() ?: 0)
            id = 31 * id + (categories?.sumOf { it.hashCode() } ?: 0)
            return id
        }
    }

    /** Behaviorally-equivalent in-memory [FavoriteSearchRepository]. */
    private class FakeFavoriteSearchRepository : FavoriteSearchRepository {
        val favorites = MutableStateFlow<Set<Int>>(emptySet())
        override fun observeAll(): Flow<Set<Int>> = favorites
        override suspend fun add(id: Int) {
            favorites.update { it + id }
        }
        override suspend fun remove(id: Int) {
            favorites.update { it - id }
        }
        override suspend fun clear() {
            favorites.value = emptySet()
        }
    }

    private class NoopAnalytics : AnalyticsTracker {
        override fun event(name: String, params: Map<String, String>) {}
        override fun setUserId(userId: String?) {}
        override fun setProperty(key: String, value: String?) {}
        override fun recordNonFatal(throwable: Throwable, context: Map<String, String>) {}
        override fun recordWarning(message: String, context: Map<String, String>) {}
        override fun log(message: String) {}
    }

    /** Real lambda-backed [ObserveAuthStateUseCase] (the interface IS `() -> Flow<AuthState>`). */
    private class FakeObserveAuthStateUseCase(
        val state: MutableStateFlow<AuthState>,
    ) : ObserveAuthStateUseCase {
        override fun invoke(): Flow<AuthState> = state
    }

    private lateinit var historyRepo: FakeSearchHistoryRepository
    private lateinit var favoritesRepo: FakeFavoriteSearchRepository
    private lateinit var authState: MutableStateFlow<AuthState>

    private fun createViewModel(
        initialAuth: AuthState = AuthState.Authorized("user", null),
    ): SearchViewModel {
        historyRepo = FakeSearchHistoryRepository()
        favoritesRepo = FakeFavoriteSearchRepository()
        authState = MutableStateFlow(initialAuth)
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher(mainDispatcherRule.testDispatcher.scheduler))
        val observeAuth = FakeObserveAuthStateUseCase(authState)
        val observeHistory = ObserveSearchHistoryUseCase(historyRepo, favoritesRepo, dispatchers)
        return SearchViewModel(
            observeAuthStateUseCase = observeAuth,
            observeSearchHistoryUseCase = observeHistory,
            removeSearchHistoryUseCase = RemoveSearchHistoryUseCase(historyRepo, favoritesRepo),
            pinSearchHistoryUseCase = PinSearchHistoryUseCase(favoritesRepo),
            unpinSearchHistoryUseCase = UnpinSearchHistoryUseCase(favoritesRepo),
            analytics = NoopAnalytics(),
            loggerFactory = TestLoggerFactory(),
        )
    }

    private fun search(id: Int, query: String) = Search(id, Filter(query = query))

    // VM-CONTRACT
    @Test
    fun unauthorised_auth_state_renders_Unauthorised() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = createViewModel(initialAuth = AuthState.Unauthorized)
            vm.test(this) {
                runOnCreate()
                cancelAndIgnoreRemainingItems()
            }
            assertEquals(SearchState.Unauthorised, vm.container.stateFlow.value)
        }

    // VM-CONTRACT
    @Test
    fun authorised_with_empty_history_renders_Empty() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = createViewModel(initialAuth = AuthState.Authorized("user", null))
            vm.test(this) {
                runOnCreate()
                cancelAndIgnoreRemainingItems()
            }
            assertEquals(SearchState.Empty, vm.container.stateFlow.value)
        }

    // VM-CONTRACT
    //
    // Covers the auth-state TOGGLE the nested `collectLatest` in
    // `observeSearchHistory` exists to handle: a user who is signed in and
    // viewing their search history, then signs out (token expiry / explicit
    // logout). The outer auth `collectLatest` MUST cancel the inner history
    // collection and re-render `Unauthorised` — otherwise the screen would
    // keep showing the now-unauthorised user's private search history. No
    // existing test drove this transition; the prior tests only set a single
    // fixed initial auth state. A mutation that drops the `else -> reduce {
    // Unauthorised }` arm leaves the stale SearchList rendered, which this
    // catches.
    @Test
    fun signing_out_while_viewing_history_re_renders_Unauthorised() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = createViewModel(initialAuth = AuthState.Authorized("user", null))
            historyRepo.searchFlow.value = listOf(search(0, "ubuntu"))
            vm.test(this) {
                runOnCreate()
                // Confirm we start in the authorised SearchList state.
                assertTrue(
                    "expected SearchList while authorised",
                    vm.container.stateFlow.value is SearchState.SearchList,
                )
                // The user signs out.
                authState.value = AuthState.Unauthorized
                cancelAndIgnoreRemainingItems()
            }
            assertEquals(SearchState.Unauthorised, vm.container.stateFlow.value)
        }

    // VM-CONTRACT
    @Test
    fun authorised_with_history_partitions_pinned_and_other() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = createViewModel(initialAuth = AuthState.Authorized("user", null))
            historyRepo.searchFlow.value = listOf(search(0, "ubuntu"), search(1, "debian"))
            favoritesRepo.favorites.value = setOf(1)
            vm.test(this) {
                runOnCreate()
                cancelAndIgnoreRemainingItems()
            }
            val state = vm.container.stateFlow.value
            assertTrue("expected SearchList, got $state", state is SearchState.SearchList)
            state as SearchState.SearchList
            assertEquals(listOf(search(1, "debian")), state.pinned)
            assertEquals(listOf(search(0, "ubuntu")), state.other)
        }

    // VM-CONTRACT
    @Test
    fun PinItemClick_persists_favorite_and_promotes_item_to_pinned() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = createViewModel(initialAuth = AuthState.Authorized("user", null))
            historyRepo.searchFlow.value = listOf(search(0, "ubuntu"))
            vm.test(this) {
                runOnCreate()
                vm.perform(SearchAction.PinItemClick(search(0, "ubuntu")))
                cancelAndIgnoreRemainingItems()
            }
            // Primary user-visible assertion: the item moved into `pinned`.
            val state = vm.container.stateFlow.value as SearchState.SearchList
            assertEquals(listOf(search(0, "ubuntu")), state.pinned)
            assertTrue("other must be empty after pinning the only item", state.other.isEmpty())
            // Repository-state assertion: the favorite was actually persisted.
            assertEquals(setOf(0), favoritesRepo.favorites.value)
        }

    // VM-CONTRACT
    @Test
    fun UnpinItemClick_removes_favorite_and_demotes_item_to_other() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = createViewModel(initialAuth = AuthState.Authorized("user", null))
            historyRepo.searchFlow.value = listOf(search(0, "ubuntu"))
            favoritesRepo.favorites.value = setOf(0)
            vm.test(this) {
                runOnCreate()
                vm.perform(SearchAction.UnpinItemClick(search(0, "ubuntu")))
                cancelAndIgnoreRemainingItems()
            }
            val state = vm.container.stateFlow.value as SearchState.SearchList
            assertTrue("pinned must be empty after unpinning", state.pinned.isEmpty())
            assertEquals(listOf(search(0, "ubuntu")), state.other)
            assertEquals(emptySet<Int>(), favoritesRepo.favorites.value)
        }

    // VM-CONTRACT
    @Test
    fun DeleteItemClick_removes_row_from_history() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = createViewModel(initialAuth = AuthState.Authorized("user", null))
            historyRepo.searchFlow.value = listOf(search(0, "ubuntu"), search(1, "debian"))
            vm.test(this) {
                runOnCreate()
                vm.perform(SearchAction.DeleteItemClick(search(0, "ubuntu")))
                cancelAndIgnoreRemainingItems()
            }
            val state = vm.container.stateFlow.value as SearchState.SearchList
            // Deleted row must be gone from the rendered list AND the repo.
            assertEquals(listOf(search(1, "debian")), state.other)
            assertEquals(listOf(search(1, "debian")), historyRepo.searchFlow.value)
        }

    // VM-CONTRACT
    @Test
    fun LoginClick_emits_OpenLogin_side_effect() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = createViewModel(initialAuth = AuthState.Unauthorized)
            var captured: SearchSideEffect? = null
            vm.test(this) {
                runOnCreate()
                vm.perform(SearchAction.LoginClick)
                captured = drainSideEffect()
                cancelAndIgnoreRemainingItems()
            }
            assertEquals(SearchSideEffect.OpenLogin, captured)
        }

    // VM-CONTRACT
    @Test
    fun SearchActionClick_emits_OpenSearchInput_side_effect() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = createViewModel(initialAuth = AuthState.Authorized("user", null))
            var captured: SearchSideEffect? = null
            vm.test(this) {
                runOnCreate()
                vm.perform(SearchAction.SearchActionClick)
                captured = drainSideEffect()
                cancelAndIgnoreRemainingItems()
            }
            assertEquals(SearchSideEffect.OpenSearchInput, captured)
        }

    // VM-CONTRACT
    @Test
    fun SearchItemClick_emits_OpenSearch_with_the_clicked_filter() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = createViewModel(initialAuth = AuthState.Authorized("user", null))
            val clicked = search(3, "ubuntu")
            var captured: SearchSideEffect? = null
            vm.test(this) {
                runOnCreate()
                vm.perform(SearchAction.SearchItemClick(clicked))
                captured = drainSideEffect()
                cancelAndIgnoreRemainingItems()
            }
            assertEquals(SearchSideEffect.OpenSearch(clicked.filter), captured)
        }
}

/**
 * Drains the item stream until the first [SearchSideEffect] is observed,
 * ignoring interleaved state items (same pattern as
 * [lava.search.result.SearchResultViewModelFallbackTest]).
 */
private suspend fun org.orbitmvi.orbit.test.OrbitTestContext<SearchState, SearchSideEffect, SearchViewModel>.drainSideEffect(): SearchSideEffect {
    while (true) {
        val item = awaitItem()
        if (item is org.orbitmvi.orbit.test.Item.SideEffectItem) return item.value
    }
}

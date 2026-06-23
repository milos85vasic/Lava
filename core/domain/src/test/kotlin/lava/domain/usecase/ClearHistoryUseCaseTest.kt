package lava.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import lava.data.api.repository.FavoriteSearchRepository
import lava.models.Page
import lava.models.search.Filter
import lava.models.topic.Author
import lava.models.topic.Post
import lava.models.topic.TopicPage
import lava.testing.repository.TestSearchHistoryRepository
import lava.testing.repository.TestSuggestsRepository
import lava.testing.repository.TestVisitedRepository
import lava.testing.testDispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real-stack test for [ClearHistoryUseCase] wired to REAL repository fakes that
 * are behaviorally equivalent to their Room-backed counterparts (Anti-Bluff Pact
 * Second + Third Law). Nothing in the use case is mocked — the SUT is a real
 * [ClearHistoryUseCase] and the four collaborators are the actual `core:testing`
 * fakes (suggests / search-history / visited) plus a minimal behaviorally-
 * equivalent inline [FavoriteSearchRepository] (no shared fake exists for it).
 *
 * User-visible feature covered: the single "Clear history" action exposed in
 * settings. It MUST wipe ALL FOUR distinct user-history stores in one go —
 * search suggestions, recent searches, favorited searches, and visited topics.
 * Each of these backs a separate on-screen list. The danger this test guards is
 * a fan-out regression: if any one of the four `clear()` calls is dropped (or
 * the whole body is moved off the dispatcher and silently skipped), the user
 * taps "Clear history" and one of those lists stubbornly keeps showing stale
 * rows. A green unit test that only checked one store would let that ship.
 *
 * Primary assertions are on the OBSERVED CONTENT of each store's Flow (what the
 * four screens render) before and after the action — never on call counts.
 * Bluff-Audit: see FALSIFIABILITY REHEARSAL at the bottom.
 */
class ClearHistoryUseCaseTest {

    /**
     * Minimal in-memory [FavoriteSearchRepository] mirroring
     * `FavoriteSearchRepositoryImpl`: an observable set of favorited search ids;
     * `add` inserts (idempotent, set semantics — like the Room UPSERT on a
     * primary key), `remove` deletes, `clear` empties. The relevant branch for
     * this SUT is `clear()` emptying the observed set.
     */
    private class FakeFavoriteSearchRepository : FavoriteSearchRepository {
        private val ids = MutableStateFlow<Set<Int>>(emptySet())
        override fun observeAll(): Flow<Set<Int>> = ids
        override suspend fun add(id: Int) = ids.update { it + id }
        override suspend fun remove(id: Int) = ids.update { it - id }
        override suspend fun clear() {
            ids.value = emptySet()
        }
    }

    private fun topicPage(id: String, title: String) = TopicPage(
        id = id,
        title = title,
        author = Author(id = "a", name = "author"),
        category = null,
        torrentData = null,
        commentsPage = Page(items = emptyList<Post>(), page = 0, pages = 0),
    )

    @Test
    fun `clear history wipes all four user-history stores`() = runTest {
        val suggests = TestSuggestsRepository()
        val searchHistory = TestSearchHistoryRepository()
        val favoriteSearch = FakeFavoriteSearchRepository()
        val visited = TestVisitedRepository()

        // Seed every store with real content the four screens would render.
        suggests.addSuggest("ubuntu")
        suggests.addSuggest("debian")
        searchHistory.add(Filter(query = "linux iso"))
        favoriteSearch.add(101)
        favoriteSearch.add(202)
        visited.add(topicPage(id = "t1", title = "Visited Topic"))

        // Precondition: all four stores are non-empty (the user has history).
        assertEquals(listOf("debian", "ubuntu"), suggests.observeSuggests().first())
        assertEquals(listOf("linux iso"), searchHistory.observeAll().first().map { it.filter.query })
        assertEquals(setOf(101, 202), favoriteSearch.observeAll().first())
        assertEquals(listOf("t1"), visited.observeIds().first())

        val useCase = ClearHistoryUseCase(
            suggestsRepository = suggests,
            searchHistoryRepository = searchHistory,
            favoriteSearchRepository = favoriteSearch,
            visitedRepository = visited,
            dispatchers = testDispatchers(),
        )

        useCase()

        // User-visible outcome: every one of the four screens now renders empty.
        assertTrue("suggests not cleared", suggests.observeSuggests().first().isEmpty())
        assertTrue("search history not cleared", searchHistory.observeAll().first().isEmpty())
        assertTrue("favorite searches not cleared", favoriteSearch.observeAll().first().isEmpty())
        assertTrue("visited topics not cleared", visited.observeIds().first().isEmpty())
    }
}

/*
 * FALSIFIABILITY REHEARSAL (§6.J clause 2 / Seventh Law clause 5)
 *
 * Mutation — ClearHistoryUseCase.invoke: delete the line
 *   `visitedRepository.clear()` (drop one of the four fan-out clears).
 *   Observed failure: `clear history wipes all four user-history stores` FAILS
 *   at `assertTrue("visited topics not cleared", ...)` —
 *   java.lang.AssertionError: visited topics not cleared (the visited store
 *   still observed ["t1"]). The other three assertions stay green, proving the
 *   test discriminates the dropped clear specifically.
 *   Reverted: yes — line restored, suite re-run green.
 */

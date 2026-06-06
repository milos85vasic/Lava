package lava.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import lava.data.api.repository.FavoriteSearchRepository
import lava.data.api.repository.SearchHistoryRepository
import lava.models.search.Filter
import lava.models.search.Search
import lava.testing.TestDispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Real-stack unit test for [ObserveSearchHistoryUseCase].
 *
 * SUT is the production [ObserveSearchHistoryUseCase]. Boundary repositories are
 * behavioral fakes backed by [MutableStateFlow]. Primary assertion is on the
 * user-visible partition: which searches land in the "pinned" list (those whose id
 * is in the favorite set) versus the "other" list — the two sections a user sees in
 * the search-history screen.
 *
 * FALSIFIABILITY REHEARSAL: inverted the partition predicate in the use case
 * (`pinned = searches.filter { it.id !in favorites }` /
 *  `other = searches.filter { it.id in favorites }`). The "partitions pinned and
 * other by favorite membership" test FAILED with:
 *   java.lang.AssertionError: expected:<[Search(id=1,...)]> but was:<[Search(id=2,...), Search(id=3,...)]>
 * Reverted; test passes.
 */
class ObserveSearchHistoryUseCaseTest {

    private class FakeSearchHistoryRepository(searches: List<Search>) : SearchHistoryRepository {
        private val flow = MutableStateFlow(searches)
        override fun observeAll(): Flow<List<Search>> = flow
        override suspend fun add(filter: Filter) = Unit
        override suspend fun remove(id: Int) {
            flow.value = flow.value.filterNot { it.id == id }
        }
        override suspend fun clear() {
            flow.value = emptyList()
        }
    }

    private class FakeFavoriteSearchRepository(ids: Set<Int>) : FavoriteSearchRepository {
        private val flow = MutableStateFlow(ids)
        override fun observeAll(): Flow<Set<Int>> = flow
        override suspend fun add(id: Int) {
            flow.value = flow.value + id
        }
        override suspend fun remove(id: Int) {
            flow.value = flow.value - id
        }
        override suspend fun clear() {
            flow.value = emptySet()
        }
    }

    private fun search(id: Int) = Search(id = id, filter = Filter(query = "q-$id"))

    @Test
    fun `partitions pinned and other by favorite membership`() = runTest {
        val useCase = ObserveSearchHistoryUseCase(
            searchHistoryRepository = FakeSearchHistoryRepository(
                listOf(search(1), search(2), search(3)),
            ),
            favoriteSearchRepository = FakeFavoriteSearchRepository(setOf(1)),
            dispatchers = TestDispatchers(UnconfinedTestDispatcher(testScheduler)),
        )

        val history = useCase().first()

        assertEquals(listOf(search(1)), history.pinned)
        assertEquals(listOf(search(2), search(3)), history.other)
    }

    @Test
    fun `no favorites puts everything in other`() = runTest {
        val useCase = ObserveSearchHistoryUseCase(
            searchHistoryRepository = FakeSearchHistoryRepository(
                listOf(search(10), search(20)),
            ),
            favoriteSearchRepository = FakeFavoriteSearchRepository(emptySet()),
            dispatchers = TestDispatchers(UnconfinedTestDispatcher(testScheduler)),
        )

        val history = useCase().first()

        assertEquals(emptyList<Search>(), history.pinned)
        assertEquals(listOf(search(10), search(20)), history.other)
    }

    @Test
    fun `all favorites puts everything in pinned`() = runTest {
        val useCase = ObserveSearchHistoryUseCase(
            searchHistoryRepository = FakeSearchHistoryRepository(
                listOf(search(5), search(6)),
            ),
            favoriteSearchRepository = FakeFavoriteSearchRepository(setOf(5, 6)),
            dispatchers = TestDispatchers(UnconfinedTestDispatcher(testScheduler)),
        )

        val history = useCase().first()

        assertEquals(listOf(search(5), search(6)), history.pinned)
        assertEquals(emptyList<Search>(), history.other)
    }

    @Test
    fun `favorite id with no matching search yields empty pinned`() = runTest {
        val useCase = ObserveSearchHistoryUseCase(
            searchHistoryRepository = FakeSearchHistoryRepository(listOf(search(1))),
            favoriteSearchRepository = FakeFavoriteSearchRepository(setOf(99)),
            dispatchers = TestDispatchers(UnconfinedTestDispatcher(testScheduler)),
        )

        val history = useCase().first()

        assertEquals(emptyList<Search>(), history.pinned)
        assertEquals(listOf(search(1)), history.other)
    }
}

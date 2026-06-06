package lava.testing.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import lava.data.api.repository.SearchHistoryRepository
import lava.models.search.Filter
import lava.models.search.Search

class TestSearchHistoryRepository : SearchHistoryRepository {
    private val searchFlow: MutableStateFlow<List<Search>> = MutableStateFlow(emptyList())

    override fun observeAll(): Flow<List<Search>> = searchFlow

    override suspend fun add(filter: Filter) {
        searchFlow.update { it.plus(Search(it.size, filter)) }
    }

    override suspend fun remove(id: Int) {
        // Third Law: mirror SearchHistoryRepositoryImpl.remove → DAO delete(id),
        // i.e. drop the matching row and keep the rest. The historical
        // `filter { it.id == id }` kept the matched row and dropped the others
        // (the inverse) — a bluff fake that would pass a "remove from history"
        // test while the real remove was broken.
        searchFlow.update { list -> list.filterNot { it.id == id } }
    }

    override suspend fun clear() {
        searchFlow.update { emptyList() }
    }
}

package lava.data.impl.repository

import lava.data.api.repository.SearchHistoryRepository
import lava.data.converters.toEntity
import lava.data.converters.toSearch
import lava.database.dao.SearchHistoryDao
import lava.database.entity.SearchHistoryEntity
import lava.models.search.Filter
import lava.models.search.Search
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class SearchHistoryRepositoryImpl @Inject constructor(
    private val searchHistoryDao: SearchHistoryDao,
) : SearchHistoryRepository {
    override fun observeAll(): Flow<List<Search>> {
        return searchHistoryDao.observerAll().map { entities ->
            entities.map(SearchHistoryEntity::toSearch)
        }
    }

    override suspend fun add(filter: Filter) {
        searchHistoryDao.insert(filter.toEntity())
    }

    override suspend fun remove(id: Int) {
        searchHistoryDao.delete(id)
    }

    override suspend fun clear() {
        searchHistoryDao.deleteAll()
    }
}

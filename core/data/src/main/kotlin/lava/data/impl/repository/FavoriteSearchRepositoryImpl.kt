package lava.data.impl.repository

import lava.data.api.repository.FavoriteSearchRepository
import lava.database.dao.FavoriteSearchDao
import lava.database.entity.FavoriteSearchEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class FavoriteSearchRepositoryImpl @Inject constructor(
    private val dao: FavoriteSearchDao,
) : FavoriteSearchRepository {
    override fun observeAll(): Flow<Set<Int>> {
        return dao.observerAll().map { it.map(FavoriteSearchEntity::id).toSet() }
    }

    override suspend fun add(id: Int) {
        dao.insert(FavoriteSearchEntity(id))
    }

    override suspend fun remove(id: Int) {
        dao.delete(FavoriteSearchEntity(id))
    }

    override suspend fun clear() {
        dao.deleteAll()
    }
}

package lava.data.impl.repository

import lava.data.api.repository.EndpointsRepository
import lava.data.converters.toEntity
import lava.data.converters.toModel
import lava.database.dao.EndpointDao
import lava.database.entity.EndpointEntity
import lava.models.settings.Endpoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject

class EndpointsRepositoryImpl @Inject constructor(
    private val endpointDao: EndpointDao,
) : EndpointsRepository {
    override suspend fun observeAll(): Flow<List<Endpoint>> {
        return endpointDao
            .observerAll()
            .onStart {
                runCatching {
                    if (endpointDao.isEmpty()) {
                        endpointDao.insertAll(defaultEndpoints)
                    }
                }
            }
            .mapLatest { entities ->
                entities.mapNotNull(EndpointEntity::toModel)
            }
    }

    override suspend fun add(endpoint: Endpoint) {
        endpointDao.insert(endpoint.toEntity())
    }

    override suspend fun remove(endpoint: Endpoint) {
        endpointDao.remove(endpoint.toEntity())
    }

    private companion object {
        val defaultEndpoints: List<EndpointEntity> by lazy {
            listOf(
                Endpoint.Proxy,
                Endpoint.Rutracker,
            ).map(Endpoint::toEntity)
        }
    }
}

package lava.data.impl.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import lava.data.api.repository.EndpointsRepository
import lava.data.converters.toEntity
import lava.data.converters.toModel
import lava.database.dao.EndpointDao
import lava.database.entity.EndpointEntity
import lava.models.settings.Endpoint
import javax.inject.Inject

class EndpointsRepositoryImpl @Inject constructor(
    private val endpointDao: EndpointDao,
) : EndpointsRepository {
    override suspend fun observeAll(): Flow<List<Endpoint>> {
        return endpointDao
            .observerAll()
            .onStart {
                runCatching {
                    purgeRutrackerLegacy()
                    if (endpointDao.isEmpty()) {
                        endpointDao.insertAll(defaultEndpoints)
                    }
                }
            }
            .mapLatest { entities ->
                entities
                    .mapNotNull(EndpointEntity::toModel)
                    .filterNot { it is Endpoint.Rutracker }
            }
    }

    override suspend fun add(endpoint: Endpoint) {
        if (endpoint is Endpoint.Rutracker) return
        endpointDao.insert(endpoint.toEntity())
    }

    override suspend fun remove(endpoint: Endpoint) {
        endpointDao.remove(endpoint.toEntity())
    }

    private suspend fun purgeRutrackerLegacy() {
        // Operator directive 2026-05-12: Endpoint.Rutracker (direct
        // rutracker.org) is no longer surfaced. Existing installs and
        // Android Auto Backup restores may carry the row in Room from
        // pre-1.2.15 builds; purge it on every observe() so users do
        // not see a stale Main server entry that they cannot dismiss.
        runCatching { endpointDao.remove(Endpoint.Rutracker.toEntity()) }
    }

    private companion object {
        // Operator directive 2026-05-12: communication is strictly through
        // Lava API. The historical direct-rutracker.org seed entry was
        // removed from the seeded set (no longer surfaced to the user).
        // Discovery populates the list with mDNS-found lava-api-go
        // instances; users may also add custom endpoints manually.
        val defaultEndpoints: List<EndpointEntity> = emptyList()
    }
}

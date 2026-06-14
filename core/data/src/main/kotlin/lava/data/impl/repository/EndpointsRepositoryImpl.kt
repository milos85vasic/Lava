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
                    .distinctBy(::serverIdentity)
            }
    }

    /**
     * Stable per-SERVER identity used to de-duplicate the Connections list.
     *
     * Operator-reported defect 2026-06-14: "the chosen online server appears
     * TWICE in the Server list." Root cause: the Room primary-key id for an
     * [Endpoint.GoApi] is `GoApi(${packHost()})` and `packHost()` appends the
     * additive `key`/`platform`/`storage` fields (see
     * `lava.data.converters.Endpoint.packHost`). The SAME physical server
     * (same host:port) persisted via two paths that differ only in those
     * fields — the cloud "Add server" flow writes a bare GoApi (key=null)
     * while the on-device / mDNS-discovered flow writes a GoApi carrying a
     * per-instance key and/or platform+storage TXT attributes — gets TWO
     * distinct primary keys (`OnConflictStrategy.REPLACE` de-dups only on a
     * matching id), so two rows reach this list. De-dup on the
     * transport-defining identity (host + port for GoApi; host for Mirror)
     * so the user sees one row per actual server regardless of which path
     * added it. The richer (keyed) entry wins because `distinctBy` keeps the
     * first occurrence and Room emits in insertion order — but auth still
     * resolves through the active endpoint's own persisted key in
     * `lava.securestorage.model.EndpointConverter`, which is unaffected here.
     */
    private fun serverIdentity(endpoint: Endpoint): String = when (endpoint) {
        is Endpoint.GoApi -> "GoApi(${endpoint.host}:${endpoint.port})"
        is Endpoint.Mirror -> "Mirror(${endpoint.host})"
        is Endpoint.Rutracker -> "Rutracker"
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

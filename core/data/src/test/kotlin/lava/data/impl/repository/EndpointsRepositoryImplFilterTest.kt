package lava.data.impl.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import lava.data.converters.toEntity
import lava.database.dao.EndpointDao
import lava.database.entity.EndpointEntity
import lava.models.settings.Endpoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Operator directive 2026-05-12: EndpointsRepositoryImpl.observeAll()
 * MUST hide Endpoint.Rutracker from the emitted list AND purge any
 * legacy Rutracker row from the DAO on observe(). This contract is
 * the §6.J/§6.L primary-on-state guarantee that the Server section
 * never shows "Main" / rutracker.org to the user.
 *
 * Falsifiability rehearsal (Sixth Law clause 2):
 *   Remove `.filterNot { it is Endpoint.Rutracker }` from observeAll() —
 *   the `excludes_rutracker_from_emitted_list` assertion fires because
 *   the Rutracker model leaks through.
 *
 *   Remove `purgeRutrackerLegacy()` from observeAll() — the
 *   `purges_legacy_rutracker_row_from_dao` assertion fires because the
 *   DAO retains the row.
 */
class EndpointsRepositoryImplFilterTest {

    private class FakeEndpointDao : EndpointDao {
        val rows = mutableListOf<EndpointEntity>()
        val flow = MutableStateFlow<List<EndpointEntity>>(emptyList())

        override fun observerAll() = flow

        override suspend fun isEmpty(): Boolean = rows.isEmpty()

        override suspend fun insert(entity: EndpointEntity) {
            rows.removeAll { it.id == entity.id }
            rows.add(entity)
            flow.value = rows.toList()
        }

        override suspend fun insertAll(entities: List<EndpointEntity>) {
            entities.forEach { insert(it) }
        }

        override suspend fun remove(entity: EndpointEntity) {
            rows.removeAll { it.id == entity.id }
            flow.value = rows.toList()
        }
    }

    @Test
    fun excludes_rutracker_from_emitted_list() = runBlocking {
        val dao = FakeEndpointDao()
        dao.insert(Endpoint.Rutracker.toEntity())
        dao.insert(Endpoint.GoApi(host = "lava-api.local").toEntity())
        val repo = EndpointsRepositoryImpl(dao)
        val emitted = repo.observeAll().first()
        assertFalse(
            "Rutracker MUST NOT appear in the emitted endpoint list",
            emitted.any { it is Endpoint.Rutracker },
        )
        assertTrue(
            "GoApi MUST still appear in the emitted endpoint list",
            emitted.any { it is Endpoint.GoApi },
        )
    }

    @Test
    fun purges_legacy_rutracker_row_from_dao() = runBlocking {
        val dao = FakeEndpointDao()
        dao.insert(Endpoint.Rutracker.toEntity())
        val repo = EndpointsRepositoryImpl(dao)
        repo.observeAll().first()
        assertFalse(
            "DAO MUST NOT retain the legacy Rutracker row after observeAll()",
            dao.rows.any { it.type == "Rutracker" },
        )
    }

    @Test
    fun add_rejects_rutracker_silently() = runBlocking {
        val dao = FakeEndpointDao()
        val repo = EndpointsRepositoryImpl(dao)
        repo.add(Endpoint.Rutracker)
        assertTrue(
            "Adding Rutracker MUST be a no-op — the DAO stays empty",
            dao.rows.isEmpty(),
        )
    }
}

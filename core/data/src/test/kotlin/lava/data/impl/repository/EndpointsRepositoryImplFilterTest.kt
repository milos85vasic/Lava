package lava.data.impl.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import lava.data.converters.toEntity
import lava.database.dao.EndpointDao
import lava.database.entity.EndpointEntity
import lava.models.settings.Endpoint
import org.junit.Assert.assertEquals
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

    /**
     * Operator-reported defect 2026-06-14: "When we open settings Server list,
     * the chosen online server appears TWICE in the list." The user onboarded
     * with an online/cloud API endpoint; that chosen endpoint is shown
     * duplicated in Settings → Server list.
     *
     * ROOT CAUSE (core/data/src/main/kotlin/lava/data/converters/Endpoint.kt:69):
     * the Room PRIMARY KEY id for an [Endpoint.GoApi] is `GoApi(${packHost()})`
     * and `packHost()` (lines 52-60) appends the additive `key`/`platform`/
     * `storage` fields after a `#` sentinel. So the SAME physical server
     * (same host:port) persisted via two paths that differ only in those
     * additive fields gets TWO distinct primary keys → two Room rows
     * (`OnConflictStrategy.REPLACE` only de-dups on matching id) →
     * [observeAll] emits the same server TWICE.
     *
     * Real reproduction of the user's path: the cloud "Add server" flow
     * (OnboardingViewModel.onAddCloudApi → CloudApiDefaults.parse) builds a
     * bare GoApi (key=null), while the on-device / mDNS path
     * (OnboardingViewModel.onOnDeviceApiReturned / startApiDiscovery) builds a
     * GoApi carrying a key and/or platform+storage TXT attributes. Both call
     * endpointsRepository.add() with the SAME host:port → two list rows.
     *
     * Falsifiability rehearsal (Sixth Law clause 2):
     *   Remove the distinct-by-server-identity step from
     *   EndpointsRepositoryImpl.observeAll() — this assertion fires because
     *   the same host:port is emitted twice.
     */
    @Test
    fun observeAll_deduplicates_same_server_added_via_two_paths() = runBlocking {
        val dao = FakeEndpointDao()
        // Same online server (host + port), added once bare (cloud "Add
        // server" path) and once carrying a per-instance key + TXT attributes
        // (on-device / mDNS-discovered path). Different additive fields →
        // different Room primary-key id → two rows in the DAO.
        val bare = Endpoint.GoApi(host = "lava.example", port = 7777)
        val keyed = Endpoint.GoApi(
            host = "lava.example",
            port = 7777,
            platform = Endpoint.GoApi.PLATFORM_ANDROID,
            storage = "sqlite",
            key = "instance-key-abc",
        )
        dao.insert(bare.toEntity())
        dao.insert(keyed.toEntity())
        assertEquals(
            "Pre-condition: the two endpoints have distinct Room ids (the bug's mechanism)",
            2,
            dao.rows.size,
        )

        val repo = EndpointsRepositoryImpl(dao)
        val emitted = repo.observeAll().first()

        val sameServerCount = emitted.count {
            it is Endpoint.GoApi && it.host == "lava.example" && it.port == 7777
        }
        assertEquals(
            "The chosen online server MUST appear exactly ONCE in the Server list, " +
                "not twice (operator defect 2026-06-14)",
            1,
            sameServerCount,
        )
    }

    /**
     * Guard the complement: two GENUINELY different servers (different
     * host:port) MUST both still appear — the de-dup is by server identity
     * (host+port), not a blanket "collapse all GoApi" that would hide a
     * second real server the user added.
     */
    @Test
    fun observeAll_keeps_distinct_servers() = runBlocking {
        val dao = FakeEndpointDao()
        dao.insert(Endpoint.GoApi(host = "lava.example", port = 7777).toEntity())
        dao.insert(Endpoint.GoApi(host = "other.example", port = 8443).toEntity())
        val repo = EndpointsRepositoryImpl(dao)
        val emitted = repo.observeAll().first()
        assertEquals(
            "Two genuinely different servers MUST both appear in the Server list",
            2,
            emitted.count { it is Endpoint.GoApi },
        )
    }
}

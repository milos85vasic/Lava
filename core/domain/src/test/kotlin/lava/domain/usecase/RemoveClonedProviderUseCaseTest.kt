package lava.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import lava.database.dao.ClonedProviderDao
import lava.database.entity.ClonedProviderEntity
import lava.database.entity.SyncOutboxEntity
import lava.sync.SyncOutbox
import lava.sync.SyncOutboxKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SP-4 Phase G.2 (2026-05-13) — soft-delete + outbox-enqueue contract
 * for [RemoveClonedProviderUseCase].
 *
 * Anti-Bluff (§6.J): assertions are on user-observable state —
 *
 *  - After invoke, the row's `deletedAt` timestamp is non-null in the
 *    raw table (preserved for Phase E sync upload + backup-restore).
 *  - The outbox carries a `CLONED_PROVIDER` entry whose payload
 *    contains the syntheticId AND `"deleted":true`. Phase E's
 *    uploader will propagate the removal via this event.
 *  - DAO's `observeAll()` / `getAll()` skip the soft-deleted row.
 *
 * Falsifiability rehearsal (§6.J / §6.N):
 *
 *   1. In RemoveClonedProviderUseCase.invoke(), remove the
 *      `outbox.enqueue(...)` call.
 *   2. Run this test.
 *   3. Expected failure: `expected exactly one outbox entry but got 0`
 *      assertion fails — the bluff signal is "removal isn't
 *      cross-device-propagatable".
 *   4. Revert; re-run; green.
 */
class RemoveClonedProviderUseCaseTest {

    /**
     * Behaviorally-equivalent fake of [ClonedProviderDao] (Third Law).
     * Mirrors Room's `WHERE deletedAt IS NULL` filter on observeAll +
     * getAll so soft-deleted rows are invisible to read paths, just
     * like the real DAO.
     */
    private class FakeClonedDao : ClonedProviderDao {
        private val raw = MutableStateFlow<List<ClonedProviderEntity>>(emptyList())
        private val visible = MutableStateFlow<List<ClonedProviderEntity>>(emptyList())

        private fun refresh() {
            visible.value = raw.value.filter { it.deletedAt == null }
        }

        override fun observeAll(): Flow<List<ClonedProviderEntity>> = visible

        override suspend fun getAll(): List<ClonedProviderEntity> =
            raw.value.filter { it.deletedAt == null }

        override suspend fun upsert(entity: ClonedProviderEntity) {
            raw.value = (raw.value.filterNot { it.syntheticId == entity.syntheticId } + entity)
            refresh()
        }

        override suspend fun softDelete(id: String, deletedAt: Long) {
            raw.value = raw.value.map { if (it.syntheticId == id) it.copy(deletedAt = deletedAt) else it }
            refresh()
        }

        override suspend fun delete(id: String) {
            raw.value = raw.value.filterNot { it.syntheticId == id }
            refresh()
        }

        fun getRaw(id: String): ClonedProviderEntity? = raw.value.firstOrNull { it.syntheticId == id }
    }

    private class FakeOutbox : SyncOutbox {
        data class Row(val kind: SyncOutboxKind, val payload: String)
        val rows = mutableListOf<Row>()
        override suspend fun enqueue(kind: SyncOutboxKind, payload: String): Long {
            rows.add(Row(kind, payload))
            return rows.size.toLong()
        }
        override fun observe(): Flow<List<SyncOutboxEntity>> =
            MutableStateFlow<List<SyncOutboxEntity>>(emptyList()).asStateFlow()
        override suspend fun ack(id: Long) { /* noop */ }
    }

    @Test
    fun `invoke soft-deletes the clone and enqueues a CLONED_PROVIDER outbox event`() = runTest {
        val dao = FakeClonedDao()
        val outbox = FakeOutbox()
        val useCase = RemoveClonedProviderUseCase(dao, outbox)

        // Seed a clone.
        val clone = ClonedProviderEntity(
            syntheticId = "rutracker.clone.eu",
            sourceTrackerId = "rutracker",
            displayName = "RuTracker EU",
            primaryUrl = "https://rutracker.eu",
        )
        dao.upsert(clone)
        assertEquals("seeded clone must be visible pre-removal", 1, dao.getAll().size)

        useCase.invoke("rutracker.clone.eu")

        // §6.J primary — clone is invisible to read paths.
        assertEquals("removed clone must disappear from getAll()", 0, dao.getAll().size)

        // §6.J secondary — the soft-delete marker is preserved in the raw table.
        val raw = dao.getRaw("rutracker.clone.eu")
        assertNotNull("soft-deleted clone must still be present in the raw table", raw)
        assertNotNull("deletedAt must be stamped", raw!!.deletedAt)

        // §6.J — outbox enqueue verified by payload content (not just count).
        assertEquals("expected exactly one outbox entry", 1, outbox.rows.size)
        val event = outbox.rows[0]
        assertEquals(SyncOutboxKind.CLONED_PROVIDER, event.kind)
        assertTrue(
            "outbox payload must carry the synthetic id; was: ${event.payload}",
            event.payload.contains("rutracker.clone.eu"),
        )
        assertTrue(
            "outbox payload must mark the event as a removal; was: ${event.payload}",
            event.payload.contains("\"deleted\":true"),
        )
    }

    @Test
    fun `invoke on a non-existent id is a no-op for the dao but still enqueues an outbox event`() = runTest {
        // Defensive — UI flow checks state.isClone before invoking, but a race
        // condition (e.g. two-device removal) could call invoke for an id that's
        // already gone. The use case must NOT crash.
        val dao = FakeClonedDao()
        val outbox = FakeOutbox()
        val useCase = RemoveClonedProviderUseCase(dao, outbox)

        useCase.invoke("never.existed")

        assertNull("dao stays empty when id is unknown", dao.getRaw("never.existed"))
        // Outbox still gets the event — Phase E's API handler tolerates a
        // remove-of-unknown-id (idempotent DELETE).
        assertEquals(1, outbox.rows.size)
    }
}

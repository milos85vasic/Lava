package lava.credentials

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import lava.credentials.crypto.CredentialsCrypto
import lava.credentials.model.CredentialSecret
import lava.credentials.model.CredentialsEntry
import lava.database.dao.CredentialsEntryDao
import lava.database.entity.CredentialsEntryEntity
import lava.database.entity.SyncOutboxEntity
import lava.sync.SyncOutbox
import lava.sync.SyncOutboxKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SP-4 Phase G (2026-05-13). Soft-delete + outbox-enqueue contract for
 * [CredentialsEntryRepositoryImpl.delete].
 *
 * Anti-Bluff (§6.J): every assertion is on a user-observable outcome —
 *
 *  - After delete, the row is invisible to `observe()` / `get()`
 *    (because read paths filter `WHERE deletedAt IS NULL`). User sees
 *    the credential as removed.
 *  - The row is STILL present in the underlying table (with
 *    `deletedAt != null`). Backup + Phase E sync uploader can read it.
 *  - The outbox carries a `CREDENTIALS` entry whose payload includes
 *    `"deleted":true` and the soft-deleted id. Phase E's uploader will
 *    propagate the removal to other devices via this entry.
 *
 * Falsifiability rehearsal protocol (§6.J / §6.N):
 *
 *   1. In CredentialsEntryRepositoryImpl.delete(), revert to a hard
 *      delete by calling `dao.delete(id)` instead of `dao.softDelete(id, now)`
 *      AND remove the `outbox.enqueue(...)` call.
 *   2. Run this test class.
 *   3. Expected failure: `getRaw(id)` returns null (because hard delete
 *      physically removed the row) → "expected non-null soft-deleted
 *      row" assertion fails. Outbox assertion ALSO fails — no enqueue
 *      happened.
 *   4. Revert; re-run; test passes.
 */
class CredentialsEntryRepositorySoftDeleteTest {

    /**
     * Behaviorally-equivalent fake of [CredentialsEntryDao] (Third Law).
     * Mirrors Room's `WHERE deletedAt IS NULL` filter on `observeAll()`
     * and `get()` so soft-deleted rows are invisible to read paths
     * just like the real DAO. The raw row is still in the backing
     * storage — exposed via [getRaw] for §6.J secondary assertions.
     */
    private class FakeDao : CredentialsEntryDao {
        private val raw = MutableStateFlow<List<CredentialsEntryEntity>>(emptyList())
        private val visible = MutableStateFlow<List<CredentialsEntryEntity>>(emptyList())

        private fun refreshVisible() {
            visible.value = raw.value.filter { it.deletedAt == null }
        }

        override fun observeAll() = visible
        override suspend fun get(id: String): CredentialsEntryEntity? =
            raw.value.firstOrNull { it.id == id && it.deletedAt == null }

        override suspend fun upsert(entity: CredentialsEntryEntity) {
            raw.value = (raw.value.filterNot { it.id == entity.id } + entity)
            refreshVisible()
        }
        override suspend fun softDelete(id: String, deletedAt: Long) {
            raw.value = raw.value.map { if (it.id == id) it.copy(deletedAt = deletedAt) else it }
            refreshVisible()
        }
        override suspend fun delete(id: String) {
            raw.value = raw.value.filterNot { it.id == id }
            refreshVisible()
        }

        /** Non-filtered read for assertions; mirrors the raw table state. */
        fun getRaw(id: String): CredentialsEntryEntity? = raw.value.firstOrNull { it.id == id }
    }

    private class FakeOutbox : SyncOutbox {
        data class Row(val kind: SyncOutboxKind, val payload: String)
        val rows = mutableListOf<Row>()
        override suspend fun enqueue(kind: SyncOutboxKind, payload: String): Long {
            rows.add(Row(kind, payload))
            return rows.size.toLong()
        }
        override fun observe(): Flow<List<SyncOutboxEntity>> = MutableStateFlow(emptyList())
        override suspend fun ack(id: Long) { /* noop */ }
    }

    private val salt = ByteArray(32) { it.toByte() }
    private val key = CredentialsCrypto.deriveKey("pass", salt)

    @Test
    fun `delete soft-deletes the row and enqueues a CREDENTIALS outbox event`() = runBlocking {
        val dao = FakeDao()
        val outbox = FakeOutbox()
        val repo = CredentialsEntryRepositoryImpl(dao, { key }, outbox)

        val entry = CredentialsEntry(
            id = "id-1",
            displayName = "My creds",
            secret = CredentialSecret.UsernamePassword("alice", "p"),
            createdAtUtc = 1,
            updatedAtUtc = 2,
        )
        repo.upsert(entry)
        assertNotNull("upsert should make the credential reachable", repo.get("id-1"))

        repo.delete("id-1")

        // §6.J primary assertions — user-observable state.
        assertNull("after delete, read paths must skip the row", repo.get("id-1"))
        assertEquals(0, repo.list().size)

        // §6.J secondary — the soft-delete marker survives in the raw table
        // (required for backup-restore + Phase E sync upload).
        val raw = dao.getRaw("id-1")
        assertNotNull("soft-deleted row must still be present in the raw table", raw)
        assertNotNull("deletedAt timestamp must be stamped", raw!!.deletedAt)

        // §6.J — outbox enqueue verified by content, not just count.
        assertEquals("expected exactly one outbox entry", 1, outbox.rows.size)
        val event = outbox.rows[0]
        assertEquals(SyncOutboxKind.CREDENTIALS, event.kind)
        assertTrue("outbox payload must carry the soft-deleted id", event.payload.contains("id-1"))
        assertTrue("outbox payload must mark the event as a removal", event.payload.contains("\"deleted\":true"))
    }
}

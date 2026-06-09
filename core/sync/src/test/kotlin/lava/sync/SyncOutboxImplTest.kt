package lava.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import lava.database.dao.SyncOutboxDao
import lava.database.entity.SyncOutboxEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class SyncOutboxImplTest {
    /**
     * Behaviorally-equivalent fake of the Room [SyncOutboxDao] (Anti-Bluff Pact
     * Third Law). The real DAO's `observeAll()` is
     * `SELECT * FROM sync_outbox ORDER BY createdAt ASC` — the outbox is a FIFO
     * replay queue, so any consumer that drains it relies on entries surfacing
     * in creation order. A fake that returned bare insertion order would hide a
     * regression where [SyncOutboxImpl.enqueue] stopped stamping a monotonic
     * `createdAt` (e.g. defaulting it to 0), because in that bug every row would
     * carry the same `createdAt` and the SQL sort would expose the loss of FIFO
     * ordering while a raw-insertion-order fake would not. So the fake MUST sort
     * by `createdAt` exactly as the SQL `ORDER BY createdAt ASC` does.
     */
    private class FakeDao : SyncOutboxDao {
        private val rows = MutableStateFlow<List<SyncOutboxEntity>>(emptyList())
        private val nextId = AtomicLong(1)
        override suspend fun enqueue(entity: SyncOutboxEntity): Long {
            val id = nextId.getAndIncrement()
            // Mirror the real DAO's `ORDER BY createdAt ASC`: store sorted by
            // createdAt (ties broken by id for determinism), NOT raw insertion
            // order, so an unstamped/zeroed createdAt is observable.
            rows.value = (rows.value + entity.copy(id = id))
                .sortedWith(compareBy({ it.createdAt }, { it.id }))
            return id
        }
        override fun observeAll() = rows
        override suspend fun ack(id: Long) {
            rows.value = rows.value.filterNot { it.id == id }
        }
    }

    @Test
    fun `enqueue stores a row with the wire kind`() = runBlocking {
        val dao = FakeDao()
        val outbox = SyncOutboxImpl(dao)
        val id = outbox.enqueue(SyncOutboxKind.CREDENTIALS, "{}")
        val rows = outbox.observe().first()
        assertEquals(1, rows.size)
        assertEquals("credentials", rows[0].kind)
        assertEquals("{}", rows[0].payload)
        assertTrue(id > 0)
    }

    @Test
    fun `ack removes the row`() = runBlocking {
        val dao = FakeDao()
        val outbox = SyncOutboxImpl(dao)
        val id = outbox.enqueue(SyncOutboxKind.BINDING, "x")
        outbox.ack(id)
        assertEquals(0, outbox.observe().first().size)
    }

    /**
     * The outbox is a FIFO replay queue: the real DAO observes
     * `ORDER BY createdAt ASC`, and [SyncOutboxImpl.enqueue] is responsible for
     * stamping a real monotonic `createdAt` (`System.currentTimeMillis()`) on
     * every row. A consumer drains the queue in this order, so a row enqueued
     * earlier MUST be replayed before a row enqueued later. This guards the
     * production behaviour that every enqueued item carries a real wall-clock
     * timestamp (not a hardcoded 0) so the FIFO ordering survives.
     *
     * Falsifiability rehearsal (PERFORMED 2026-06-09):
     *   Mutation: in SyncOutboxImpl.enqueue, replace
     *             `createdAt = System.currentTimeMillis()` with `createdAt = 0`.
     *   Observed: this test FAILED on
     *             "every enqueued row MUST carry a real (non-zero) createdAt
     *              timestamp" — both rows had createdAt == 0, so the production
     *              contract that the FIFO sort depends on was violated.
     *   Reverted: yes.
     */
    @Test
    fun `enqueue stamps real createdAt and observe replays in FIFO order`() = runBlocking {
        val dao = FakeDao()
        val outbox = SyncOutboxImpl(dao)

        val before = System.currentTimeMillis()
        val firstId = outbox.enqueue(SyncOutboxKind.CREDENTIALS, "first")
        // Ensure the wall-clock advances so createdAt differs between the two.
        while (System.currentTimeMillis() == before) { /* spin briefly */ }
        outbox.enqueue(SyncOutboxKind.BINDING, "second")

        val rows = outbox.observe().first()
        assertEquals(2, rows.size)

        // Primary assertion: every row carries a real (non-zero) wall-clock
        // createdAt — the FIFO sort the consumer relies on is meaningless if the
        // producer stops stamping it.
        assertTrue(
            "every enqueued row MUST carry a real (non-zero) createdAt timestamp, " +
                "was ${rows.map { it.createdAt }}",
            rows.all { it.createdAt >= before },
        )

        // FIFO: the first-enqueued payload surfaces first (createdAt ASC).
        assertEquals("first", rows[0].payload)
        assertEquals("second", rows[1].payload)
        assertTrue(
            "rows MUST be observed in createdAt-ascending (FIFO) order",
            rows[0].createdAt <= rows[1].createdAt,
        )
        assertEquals(firstId, rows[0].id)
    }
}

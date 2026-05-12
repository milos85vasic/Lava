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
    private class FakeDao : SyncOutboxDao {
        private val rows = MutableStateFlow<List<SyncOutboxEntity>>(emptyList())
        private val nextId = AtomicLong(1)
        override suspend fun enqueue(entity: SyncOutboxEntity): Long {
            val id = nextId.getAndIncrement()
            rows.value = rows.value + entity.copy(id = id)
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
}

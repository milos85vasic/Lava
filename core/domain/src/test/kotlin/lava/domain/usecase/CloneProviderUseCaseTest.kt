package lava.domain.usecase

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import lava.database.dao.ClonedProviderDao
import lava.database.entity.ClonedProviderEntity
import lava.database.entity.SyncOutboxEntity
import lava.sync.SyncOutbox
import lava.sync.SyncOutboxKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloneProviderUseCaseTest {
    private class FakeDao : ClonedProviderDao {
        val rows = mutableListOf<ClonedProviderEntity>()
        private val flow = MutableStateFlow<List<ClonedProviderEntity>>(emptyList())
        override fun observeAll() = flow
        override suspend fun getAll(): List<ClonedProviderEntity> = rows.toList()
        override suspend fun upsert(entity: ClonedProviderEntity) {
            rows.removeAll { it.syntheticId == entity.syntheticId }
            rows.add(entity)
            flow.value = rows.toList()
        }
        override suspend fun softDelete(id: String, deletedAt: Long) {
            rows.indices.toList().forEach { i ->
                if (rows[i].syntheticId == id) rows[i] = rows[i].copy(deletedAt = deletedAt)
            }
            flow.value = rows.filter { it.deletedAt == null }.toList()
        }
        override suspend fun delete(id: String) {
            rows.removeAll { it.syntheticId == id }
            flow.value = rows.toList()
        }
    }

    private class FakeOutbox : SyncOutbox {
        val rows = mutableListOf<Pair<SyncOutboxKind, String>>()
        override suspend fun enqueue(kind: SyncOutboxKind, payload: String): Long {
            rows.add(kind to payload)
            return rows.size.toLong()
        }
        override fun observe() = MutableStateFlow<List<SyncOutboxEntity>>(emptyList())
        override suspend fun ack(id: Long) {}
    }

    @Test
    fun `clone writes dao row and queues outbox`() = runBlocking {
        val dao = FakeDao()
        val outbox = FakeOutbox()
        val u = CloneProviderUseCase(dao, outbox)
        val id = u.invoke("rutracker", "RuTracker EU", "https://rutracker.eu")
        assertTrue(id.startsWith("rutracker.clone."))
        assertEquals(1, dao.rows.size)
        assertEquals("RuTracker EU", dao.rows[0].displayName)
        assertEquals(1, outbox.rows.size)
        assertEquals(SyncOutboxKind.CLONED_PROVIDER, outbox.rows[0].first)
    }
}

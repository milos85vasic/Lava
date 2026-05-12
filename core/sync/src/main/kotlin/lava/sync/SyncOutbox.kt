package lava.sync

import kotlinx.coroutines.flow.Flow
import lava.database.entity.SyncOutboxEntity

interface SyncOutbox {
    suspend fun enqueue(kind: SyncOutboxKind, payload: String): Long
    fun observe(): Flow<List<SyncOutboxEntity>>
    suspend fun ack(id: Long)
}

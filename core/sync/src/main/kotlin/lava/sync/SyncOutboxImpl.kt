package lava.sync

import kotlinx.coroutines.flow.Flow
import lava.database.dao.SyncOutboxDao
import lava.database.entity.SyncOutboxEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncOutboxImpl @Inject constructor(
    private val dao: SyncOutboxDao,
) : SyncOutbox {
    override suspend fun enqueue(kind: SyncOutboxKind, payload: String): Long =
        dao.enqueue(
            SyncOutboxEntity(
                kind = kind.wire,
                payload = payload,
                createdAt = System.currentTimeMillis(),
            ),
        )

    override fun observe(): Flow<List<SyncOutboxEntity>> = dao.observeAll()

    override suspend fun ack(id: Long) = dao.ack(id)
}

package lava.domain.usecase

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import lava.database.dao.ClonedProviderDao
import lava.database.entity.ClonedProviderEntity
import lava.sync.SyncOutbox
import lava.sync.SyncOutboxKind
import java.util.UUID
import javax.inject.Inject

class CloneProviderUseCase @Inject constructor(
    private val dao: ClonedProviderDao,
    private val outbox: SyncOutbox,
) {
    @Serializable
    private data class WireClone(
        val syntheticId: String,
        val sourceTrackerId: String,
        val displayName: String,
        val primaryUrl: String,
    )

    suspend operator fun invoke(
        sourceTrackerId: String,
        displayName: String,
        primaryUrl: String,
    ): String {
        val syntheticId = "$sourceTrackerId.clone.${UUID.randomUUID()}"
        val entity = ClonedProviderEntity(
            syntheticId = syntheticId,
            sourceTrackerId = sourceTrackerId,
            displayName = displayName,
            primaryUrl = primaryUrl,
        )
        dao.upsert(entity)
        outbox.enqueue(
            SyncOutboxKind.CLONED_PROVIDER,
            json.encodeToString(
                WireClone(syntheticId, sourceTrackerId, displayName, primaryUrl),
            ),
        )
        return syntheticId
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
}

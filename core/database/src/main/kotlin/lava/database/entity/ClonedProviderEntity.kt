package lava.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cloned_provider")
data class ClonedProviderEntity(
    @PrimaryKey val syntheticId: String,
    val sourceTrackerId: String,
    val displayName: String,
    val primaryUrl: String,
    /**
     * SP-4 Phase G (2026-05-13). When non-null, the clone is soft-deleted
     * at the recorded epoch-ms timestamp. Read paths filter on
     * `deletedAt IS NULL`. Phase E propagates the removal via the sync
     * outbox.
     */
    val deletedAt: Long? = null,
)

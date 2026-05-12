package lava.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cloned_provider")
data class ClonedProviderEntity(
    @PrimaryKey val syntheticId: String,
    val sourceTrackerId: String,
    val displayName: String,
    val primaryUrl: String,
)

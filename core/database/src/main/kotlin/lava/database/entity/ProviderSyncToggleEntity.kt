package lava.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "provider_sync_toggle")
data class ProviderSyncToggleEntity(
    @PrimaryKey val providerId: String,
    val enabled: Boolean,
)

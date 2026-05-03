package lava.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-provider user configuration (timeouts, preferred mirrors,
 * capability toggles).
 *
 * Added in Multi-Provider Extension (Task 6.2).
 */
@Entity(tableName = "provider_configs")
data class ProviderConfigEntity(
    @PrimaryKey
    @ColumnInfo("provider_id")
    val providerId: String,

    @ColumnInfo("timeout_ms")
    val timeoutMs: Int = 10_000,

    @ColumnInfo("preferred_mirror_url")
    val preferredMirrorUrl: String?,

    @ColumnInfo("is_enabled")
    val isEnabled: Boolean = true,

    @ColumnInfo("search_enabled")
    val searchEnabled: Boolean = true,

    @ColumnInfo("browse_enabled")
    val browseEnabled: Boolean = true,

    @ColumnInfo("download_enabled")
    val downloadEnabled: Boolean = true,

    @ColumnInfo("sort_preference")
    val sortPreference: String?,

    @ColumnInfo("updated_at")
    val updatedAt: Long,
)

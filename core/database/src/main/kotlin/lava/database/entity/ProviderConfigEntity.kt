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

    /**
     * Sweep Finding #1 (2026-05-17, §6.L 59th invocation).
     *
     * Per-provider opt-in flag for anonymous mode. The ProviderConfigScreen
     * `AnonymousSection` switch reads + writes this column via
     * [lava.credentials.ProviderConfigRepository]. The §6.J anti-bluff
     * mandate: the switch's checked state MUST persist across a process
     * restart — the pre-fix handler did `state.copy(anonymous = ...)` in
     * memory only and a follow-up onCreate read the default `false`.
     *
     * Migration 10→11 backfills `use_anonymous = 0` (false) for every
     * existing row so previously-saved configs keep their prior behavior
     * (credentials path). Users can toggle anonymous on at any time and
     * the flag survives across restarts.
     */
    @ColumnInfo("use_anonymous")
    val useAnonymous: Boolean = false,

    @ColumnInfo("sort_preference")
    val sortPreference: String?,

    @ColumnInfo("updated_at")
    val updatedAt: Long,
)

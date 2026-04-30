package lava.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * Persisted snapshot of one tracker mirror's last-known health state.
 *
 * Composite primary key (tracker_id, mirror_url) so the same URL can appear
 * for two trackers without collision and a single tracker cannot register
 * the same URL twice. Added in SP-3a Phase 4 (Task 4.1).
 */
@Entity(
    tableName = "tracker_mirror_health",
    primaryKeys = ["tracker_id", "mirror_url"],
)
data class MirrorHealthEntity(
    @ColumnInfo("tracker_id") val trackerId: String,
    @ColumnInfo("mirror_url") val mirrorUrl: String,
    @ColumnInfo("state") val state: String,
    @ColumnInfo("last_check_at") val lastCheckAt: Long?,
    @ColumnInfo("consecutive_failures") val consecutiveFailures: Int,
)

package lava.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * User-supplied custom mirror for a tracker. Layered on top of the bundled
 * mirrors.json by [lava.tracker.client.persistence.MirrorConfigLoader] —
 * user entries supersede bundled entries that share the same URL.
 *
 * Added in SP-3a Phase 4 (Task 4.1).
 */
@Entity(
    tableName = "tracker_mirror_user",
    primaryKeys = ["tracker_id", "url"],
)
data class UserMirrorEntity(
    @ColumnInfo("tracker_id") val trackerId: String,
    @ColumnInfo("url") val url: String,
    @ColumnInfo("priority") val priority: Int,
    @ColumnInfo("protocol") val protocol: String,
    @ColumnInfo("added_at") val addedAt: Long,
)

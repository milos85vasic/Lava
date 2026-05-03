package lava.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-forum-category provider selection state.
 *
 * Remembers which provider the user browsed a category with,
 * so reopening the category restores the same provider.
 *
 * Added in Multi-Provider Extension (Task 7.2).
 */
@Entity(tableName = "forum_provider_selections")
data class ForumProviderSelectionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo("category_id")
    val categoryId: String,

    @ColumnInfo("provider_id")
    val providerId: String,

    @ColumnInfo("created_at")
    val createdAt: Long,
)

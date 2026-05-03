package lava.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-search-query provider selection state.
 *
 * Remembers which providers the user had enabled for a given search,
 * so re-issuing the search restores the same selection.
 *
 * Added in Multi-Provider Extension (Task 7.1).
 */
@Entity(tableName = "search_provider_selections")
data class SearchProviderSelectionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo("query_hash")
    val queryHash: String,

    @ColumnInfo("provider_id")
    val providerId: String,

    @ColumnInfo("is_selected")
    val isSelected: Boolean = true,

    @ColumnInfo("created_at")
    val createdAt: Long,
)

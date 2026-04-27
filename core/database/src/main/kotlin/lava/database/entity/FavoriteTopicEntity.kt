package lava.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import lava.models.forum.Category
import lava.models.topic.Author
import lava.models.topic.TorrentStatus

@Entity(tableName = "FavoriteTopic")
data class FavoriteTopicEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val title: String,
    val author: Author?,
    val category: Category?,
    val tags: String? = null,
    val status: TorrentStatus? = null,
    val date: Long? = null,
    val size: String? = null,
    val seeds: Int? = null,
    val leeches: Int? = null,
    val magnetLink: String? = null,
    val hasUpdate: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        return other is FavoriteTopicEntity && other.id == this.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}

package lava.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import lava.models.forum.Category
import lava.models.topic.Author
import lava.models.topic.TorrentStatus

@Entity(tableName = "HistoryTopic")
data class VisitedTopicEntity(
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
)

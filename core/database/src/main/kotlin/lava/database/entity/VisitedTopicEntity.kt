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
    // LVA-067 — source-provider id of the tracker this topic came from. Nullable
    // for back-compat: existing rows read NULL ⇒ the topic-screen download branch
    // falls back to the active tracker. When populated it lets a visited-history
    // tap reopen the topic with `?p=<providerId>` so HTTP_DOWNLOAD resolves.
    val providerId: String? = null,
)

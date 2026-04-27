package lava.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import lava.models.forum.Category
import lava.models.search.Order
import lava.models.search.Period
import lava.models.search.Sort
import lava.models.topic.Author

@Entity(tableName = "Search")
data class SearchHistoryEntity(
    @PrimaryKey val id: Int,
    val timestamp: Long,
    val query: String?,
    val sort: Sort,
    val order: Order,
    val period: Period,
    val author: Author?,
    val categories: List<Category>?,
)

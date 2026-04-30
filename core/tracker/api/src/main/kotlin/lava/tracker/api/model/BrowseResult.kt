package lava.tracker.api.model

import kotlinx.serialization.Serializable

@Serializable
data class BrowseResult(
    val items: List<TorrentItem>,
    val totalPages: Int,
    val currentPage: Int,
    val category: ForumCategory? = null,
)

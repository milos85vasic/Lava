package lava.tracker.api.model

import kotlinx.serialization.Serializable

@Serializable
data class SearchResult(
    val items: List<TorrentItem>,
    val totalPages: Int,
    val currentPage: Int,
)

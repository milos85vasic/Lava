package lava.network.dto.search

import kotlinx.serialization.Serializable
import lava.network.dto.topic.TorrentDto

@Serializable
data class SearchPageDto(
    val page: Int,
    val pages: Int,
    val torrents: List<TorrentDto>,
)

package lava.tracker.api.model

import kotlinx.serialization.Serializable

@Serializable
data class TopicDetail(
    val torrent: TorrentItem,
    val description: String? = null,
    val files: List<TorrentFile> = emptyList(),
)

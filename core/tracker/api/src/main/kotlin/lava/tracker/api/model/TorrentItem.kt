package lava.tracker.api.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class TorrentItem(
    val trackerId: String,
    val torrentId: String,
    val title: String,
    val sizeBytes: Long? = null,
    val seeders: Int? = null,
    val leechers: Int? = null,
    val infoHash: String? = null,
    val magnetUri: String? = null,
    val downloadUrl: String? = null,
    val detailUrl: String? = null,
    val category: String? = null,
    val publishDate: Instant? = null,
    val metadata: Map<String, String> = emptyMap(),
)

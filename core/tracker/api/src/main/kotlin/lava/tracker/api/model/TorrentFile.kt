package lava.tracker.api.model

import kotlinx.serialization.Serializable

@Serializable
data class TorrentFile(val name: String, val sizeBytes: Long?)

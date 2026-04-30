package lava.tracker.testing

import lava.tracker.api.model.TorrentItem

class TorrentItemBuilder {
    var trackerId: String = "test"
    var torrentId: String = "1"
    var title: String = "Sample Torrent"
    var sizeBytes: Long? = null
    var seeders: Int? = 0
    var leechers: Int? = 0
    var infoHash: String? = null
    var magnetUri: String? = null
    var downloadUrl: String? = null
    var detailUrl: String? = null
    var category: String? = null
    var publishDate: kotlinx.datetime.Instant? = null
    var metadata: Map<String, String> = emptyMap()

    fun build() = TorrentItem(
        trackerId, torrentId, title, sizeBytes, seeders, leechers,
        infoHash, magnetUri, downloadUrl, detailUrl, category, publishDate, metadata,
    )
}

fun torrent(block: TorrentItemBuilder.() -> Unit) = TorrentItemBuilder().apply(block).build()

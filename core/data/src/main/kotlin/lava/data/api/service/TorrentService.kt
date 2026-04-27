package lava.data.api.service

import lava.models.topic.Torrent

interface TorrentService {
    suspend fun getTorrent(id: String): Torrent
}

package lava.data.impl.service

import lava.auth.api.TokenProvider
import lava.data.api.service.TorrentService
import lava.data.converters.toTorrent
import lava.models.topic.Torrent
import lava.network.api.NetworkApi
import javax.inject.Inject

class TorrentServiceImpl @Inject constructor(
    private val networkApi: NetworkApi,
    private val tokenProvider: TokenProvider,
) : TorrentService {
    override suspend fun getTorrent(id: String): Torrent {
        return networkApi.getTorrent(tokenProvider.getToken(), id).toTorrent()
    }
}

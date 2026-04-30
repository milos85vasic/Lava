package lava.tracker.rutracker.feature

import lava.auth.api.TokenProvider
import lava.tracker.api.feature.DownloadableTracker
import lava.tracker.rutracker.domain.GetMagnetLinkUseCase
import lava.tracker.rutracker.domain.GetTorrentFileUseCase
import javax.inject.Inject

/**
 * RuTracker implementation of [DownloadableTracker].
 *
 * downloadTorrentFile() unwraps the bytes from the legacy [FileDto].
 * getMagnetLink() returns null synchronously — the magnet URI requires a
 * topic-page fetch (see [GetMagnetLinkUseCase] for the upgrade path).
 */
class RuTrackerDownload @Inject constructor(
    private val downloadFile: GetTorrentFileUseCase,
    private val getMagnetLinkUseCase: GetMagnetLinkUseCase,
    private val tokenProvider: TokenProvider,
) : DownloadableTracker {

    override suspend fun downloadTorrentFile(id: String): ByteArray {
        val token = tokenProvider.getToken()
        val fileDto = downloadFile(token, id)
        return fileDto.bytes
    }

    override fun getMagnetLink(id: String): String? = getMagnetLinkUseCase(id)
}

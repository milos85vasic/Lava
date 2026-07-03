package lava.domain.usecase

import kotlinx.coroutines.withContext
import lava.dispatchers.api.Dispatchers
import lava.downloads.api.DownloadRequest
import lava.downloads.api.DownloadService
import lava.network.api.TorrentDownloadSource
import javax.inject.Inject

/**
 * Downloads a provider's `.torrent` artifact and writes it to the user's public
 * Downloads collection.
 *
 * 2026-07-03 reroute + provider-aware fix: the bytes are fetched IN-APP over the
 * app's trusted OkHttp client (which validates the goapi cert exactly as
 * search/topic do), then handed to the download service to persist — the same
 * trusted path [DownloadHttpFileUseCase] uses. Critically, the fetch is now
 * PROVIDER-AWARE via [TorrentDownloadSource] ([LavaTrackerSdk.downloadTorrentFile]
 * → `/v1/{trackerId}/download/{id}`), so a Kinozal / NNMClub / RuTor download
 * reaches ITS provider with ITS session — not the endpoint's rutracker-only root
 * `/download/{id}`, which is the download-stall incident this fixes
 * (`.lava-ci-evidence/sixth-law-incidents/2026-07-03-rutracker-kinozal-torrent-download-stall.json`).
 *
 * This is the `.torrent` sibling of [DownloadHttpFileUseCase]; both take a
 * [trackerId] and both return null on any failure (fetch failed OR persist
 * failed), which the topic screen renders as DownloadState.Error.
 */
class DownloadTorrentUseCase @Inject constructor(
    private val torrentDownloadSource: TorrentDownloadSource,
    private val downloadService: DownloadService,
    private val dispatchers: Dispatchers,
) {
    /**
     * @param trackerId the provider whose `.torrent` is being fetched; blank means
     *   "use the active tracker" (legacy deep-link / favorites path).
     * @param id provider-specific topic/artifact id.
     * @param title user-facing title; the saved file is named "<title>.torrent".
     * @return the saved file URI string, or null on any failure.
     */
    suspend operator fun invoke(trackerId: String, id: String, title: String): String? {
        return withContext(dispatchers.default) {
            val bytes = torrentDownloadSource.downloadTorrentFile(trackerId, id) ?: return@withContext null
            downloadService.downloadTorrentFile(
                DownloadRequest(
                    id = id,
                    title = title,
                    bytes = bytes,
                ),
            )
        }
    }
}

package lava.downloads.api

interface DownloadService {
    suspend fun downloadTorrentFile(downloadRequest: DownloadRequest): String?
}

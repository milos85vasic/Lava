package lava.downloads.api

interface DownloadService {
    suspend fun downloadTorrentFile(downloadRequest: DownloadRequest): String?

    /**
     * LVA-052 — writes the already-fetched HTTP artifact bytes in
     * [downloadRequest] to the public Downloads collection and returns the
     * saved file URI string, or null on failure.
     *
     * Unlike [downloadTorrentFile], the bytes are already in hand (fetched by
     * the Tracker SDK over HTTP), so this does NOT enqueue a `DownloadManager`
     * URL fetch — it persists the bytes directly. On API 29+ it writes via
     * MediaStore (scoped storage); on older APIs it writes to the public
     * Downloads directory.
     */
    suspend fun downloadHttpFile(downloadRequest: HttpFileDownloadRequest): String?
}

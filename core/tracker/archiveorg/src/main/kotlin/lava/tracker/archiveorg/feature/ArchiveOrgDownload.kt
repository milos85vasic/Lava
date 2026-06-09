package lava.tracker.archiveorg.feature

import lava.tracker.api.feature.DownloadableTracker
import lava.tracker.api.feature.HttpDownloadResult
import lava.tracker.api.feature.HttpDownloadableTracker
import lava.tracker.archiveorg.http.ArchiveOrgHttpClient
import javax.inject.Inject

/**
 * Internet Archive download impl.
 *
 * Unlike torrent trackers, Internet Archive serves files over plain HTTPS.
 * The canonical user-visible surface is [HttpDownloadableTracker] (gated by
 * [lava.tracker.api.TrackerCapability.HTTP_DOWNLOAD]): [downloadHttpFile]
 * fetches a single file from
 *   GET /download/{identifier}/{filename}
 * and returns the bytes plus the resolved URL + filename so the consumer can
 * name the saved artifact.
 *
 * The [id] parameter is expected to be in the composite form
 * "{identifier}/{filename}" so the caller can specify which file inside the
 * item to retrieve.
 *
 * [DownloadableTracker] is also implemented (legacy `.torrent` surface) but is
 * NOT exposed via [ArchiveOrgClient.getFeature] — Internet Archive produces no
 * `.torrent`/magnet, so [getMagnetLink] is null and [downloadTorrentFile]
 * delegates to the HTTP path only for source compatibility; the descriptor
 * declares HTTP_DOWNLOAD, never TORRENT_DOWNLOAD (clause 6.E).
 */
class ArchiveOrgDownload @Inject constructor(
    private val http: ArchiveOrgHttpClient,
) : DownloadableTracker, HttpDownloadableTracker {

    internal constructor(http: ArchiveOrgHttpClient, baseUrl: String) : this(http) {
        this.baseUrlOverride = baseUrl
    }

    private var baseUrlOverride: String? = null
    private val baseUrl: String get() = baseUrlOverride ?: DEFAULT_BASE_URL

    override suspend fun downloadHttpFile(id: String): HttpDownloadResult {
        val parts = id.split("/", limit = 2)
        require(parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            "Archive.org download id must be 'identifier/filename', got '$id'"
        }
        val (identifier, filename) = parts
        val url = "$baseUrl/download/$identifier/$filename"
        val bytes = http.download(url)
        return HttpDownloadResult(
            bytes = bytes,
            sourceUrl = url,
            fileName = url.substringAfterLast('/').ifBlank { filename },
        )
    }

    override suspend fun downloadTorrentFile(id: String): ByteArray = downloadHttpFile(id).bytes

    override fun getMagnetLink(id: String): String? = null

    companion object {
        const val DEFAULT_BASE_URL: String = "https://archive.org"
    }
}

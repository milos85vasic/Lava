package lava.tracker.archiveorg.feature

import lava.tracker.api.feature.DownloadableTracker
import lava.tracker.archiveorg.http.ArchiveOrgHttpClient
import javax.inject.Inject

/**
 * Internet Archive implementation of [DownloadableTracker].
 *
 * Unlike torrent trackers, Internet Archive serves files over plain HTTPS.
 * [downloadTorrentFile] downloads a single file from
 *   GET /download/{identifier}/{filename}
 *
 * The [id] parameter is expected to be in the composite form
 * "{identifier}/{filename}" so the caller can specify which file inside
 * the item to retrieve.
 *
 * [getMagnetLink] always returns null — Internet Archive has no magnet
 * URIs.
 *
 * Note: this feature is NOT wired in [ArchiveOrgClient.getFeature] because
 * the Android [TrackerCapability] enum does not yet have an HTTP_DOWNLOAD
 * value. It is kept here for future capability expansion.
 */
class ArchiveOrgDownload @Inject constructor(
    private val http: ArchiveOrgHttpClient,
) : DownloadableTracker {

    internal constructor(http: ArchiveOrgHttpClient, baseUrl: String) : this(http) {
        this.baseUrlOverride = baseUrl
    }

    private var baseUrlOverride: String? = null
    private val baseUrl: String get() = baseUrlOverride ?: DEFAULT_BASE_URL

    override suspend fun downloadTorrentFile(id: String): ByteArray {
        val parts = id.split("/", limit = 2)
        require(parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            "Archive.org download id must be 'identifier/filename', got '$id'"
        }
        val (identifier, filename) = parts
        return http.download("$baseUrl/download/$identifier/$filename")
    }

    override fun getMagnetLink(id: String): String? = null

    companion object {
        const val DEFAULT_BASE_URL: String = "https://archive.org"
    }
}

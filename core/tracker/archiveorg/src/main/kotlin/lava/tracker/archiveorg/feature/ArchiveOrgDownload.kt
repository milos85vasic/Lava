package lava.tracker.archiveorg.feature

import kotlinx.serialization.Serializable
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
        // LVA-070 — the [id] is EITHER the composite "{identifier}/{filename}"
        // (explicit file) OR the BARE "{identifier}" — which is what a topic
        // opened from a search result carries (the search result id is the
        // archive.org identifier, with no file component). Previously a bare
        // identifier failed a strict `require`, so EVERY archiveorg download
        // from search threw → the topic screen's download button surfaced a
        // download Error and never a completed file (the §6.L on-device finding,
        // 2026-06-30 keystone). When no filename is supplied, resolve a
        // representative downloadable file from the item metadata.
        val parts = id.split("/", limit = 2)
        val identifier = parts[0]
        require(identifier.isNotBlank()) {
            "Archive.org download id must start with an identifier, got '$id'"
        }
        val filename = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: resolveDownloadFilename(identifier)
        val url = "$baseUrl/download/$identifier/$filename"
        val bytes = http.download(url)
        return HttpDownloadResult(
            bytes = bytes,
            sourceUrl = url,
            fileName = filename,
        )
    }

    /**
     * Resolves a representative downloadable file for a bare archive.org
     * identifier by reading `/metadata/{identifier}`. Prefers the auto-generated
     * `<identifier>_archive.torrent` (small, and the natural artifact for a
     * torrent client), else the smallest listed file (so a tap never hands the
     * user a multi-GB media payload). Throws when the item exposes no files.
     */
    private suspend fun resolveDownloadFilename(identifier: String): String {
        val response = http.get("$baseUrl/metadata/$identifier")
        val body = response.use { it.body?.string() ?: "" }
        val envelope = http.json.decodeFromString(FilesEnvelopeDto.serializer(), body)
        require(envelope.files.isNotEmpty()) {
            "Archive.org item '$identifier' exposes no downloadable files"
        }
        envelope.files.firstOrNull { it.name.endsWith(".torrent") }?.let { return it.name }
        return envelope.files.minByOrNull { it.size?.toLongOrNull() ?: Long.MAX_VALUE }!!.name
    }

    override suspend fun downloadTorrentFile(id: String): ByteArray = downloadHttpFile(id).bytes

    override fun getMagnetLink(id: String): String? = null

    companion object {
        const val DEFAULT_BASE_URL: String = "https://archive.org"
    }
}

@Serializable
private data class FilesEnvelopeDto(
    val files: List<DownloadFileDto> = emptyList(),
)

@Serializable
private data class DownloadFileDto(
    val name: String,
    val size: String? = null,
)

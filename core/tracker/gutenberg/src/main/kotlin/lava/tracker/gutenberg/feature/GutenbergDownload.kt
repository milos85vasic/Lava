package lava.tracker.gutenberg.feature

import lava.tracker.api.feature.DownloadableTracker
import lava.tracker.api.feature.HttpDownloadResult
import lava.tracker.api.feature.HttpDownloadableTracker
import lava.tracker.gutenberg.http.GutenbergHttpClient
import lava.tracker.gutenberg.model.Book
import lava.tracker.gutenberg.model.pickBestDownloadUrl
import javax.inject.Inject

/**
 * Project Gutenberg download impl.
 *
 * Fetches the book metadata to discover available formats, then downloads
 * the best one (EPUB > plain text > HTML). The canonical user-visible surface
 * is [HttpDownloadableTracker] (gated by
 * [lava.tracker.api.TrackerCapability.HTTP_DOWNLOAD]): [downloadHttpFile]
 * resolves the best format URL and returns the bytes plus that URL + a
 * suggested filename.
 *
 * [DownloadableTracker] is also implemented (legacy `.torrent` surface) but is
 * NOT exposed via [GutenbergClient.getFeature] — Gutenberg serves EPUB / text
 * / HTML over HTTP, never `.torrent`/magnet, so [getMagnetLink] is null and
 * the descriptor declares HTTP_DOWNLOAD, never TORRENT_DOWNLOAD (clause 6.E).
 */
class GutenbergDownload @Inject constructor(
    private val http: GutenbergHttpClient,
) : DownloadableTracker, HttpDownloadableTracker {

    internal constructor(http: GutenbergHttpClient, baseUrl: String) : this(http) {
        this.baseUrlOverride = baseUrl
    }

    private var baseUrlOverride: String? = null
    private val baseUrl: String get() = baseUrlOverride ?: DEFAULT_BASE_URL

    override suspend fun downloadHttpFile(id: String): HttpDownloadResult {
        val metaUrl = "$baseUrl/books/$id/"
        val response = http.get(metaUrl)
        val body = response.use { it.body?.string() ?: "" }
        val book = http.decodeFromString(Book.serializer(), body)

        val url = pickBestDownloadUrl(book.formats)
            ?: error("Gutenberg book $id has no downloadable formats")

        val bytes = http.download(url)
        return HttpDownloadResult(
            bytes = bytes,
            sourceUrl = url,
            fileName = url.substringAfterLast('/').ifBlank { "gutenberg-$id" },
        )
    }

    override suspend fun downloadTorrentFile(id: String): ByteArray = downloadHttpFile(id).bytes

    override fun getMagnetLink(id: String): String? = null

    companion object {
        const val DEFAULT_BASE_URL: String = "https://gutendex.com"
    }
}

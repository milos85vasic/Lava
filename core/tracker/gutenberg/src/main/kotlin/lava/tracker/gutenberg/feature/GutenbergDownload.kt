package lava.tracker.gutenberg.feature

import lava.tracker.api.feature.DownloadableTracker
import lava.tracker.gutenberg.http.GutenbergHttpClient
import lava.tracker.gutenberg.model.Book
import lava.tracker.gutenberg.model.pickBestDownloadUrl
import javax.inject.Inject

/**
 * Project Gutenberg implementation of [DownloadableTracker].
 *
 * Fetches the book metadata to discover available formats, then downloads
 * the best one (EPUB > plain text > HTML).
 *
 * [getMagnetLink] returns null because Project Gutenberg does not use
 * magnet links.
 */
class GutenbergDownload @Inject constructor(
    private val http: GutenbergHttpClient,
) : DownloadableTracker {

    internal constructor(http: GutenbergHttpClient, baseUrl: String) : this(http) {
        this.baseUrlOverride = baseUrl
    }

    private var baseUrlOverride: String? = null
    private val baseUrl: String get() = baseUrlOverride ?: DEFAULT_BASE_URL

    override suspend fun downloadTorrentFile(id: String): ByteArray {
        val metaUrl = "$baseUrl/books/$id/"
        val response = http.get(metaUrl)
        val body = response.use { it.body?.string() ?: "" }
        val book = http.decodeFromString(Book.serializer(), body)

        val url = pickBestDownloadUrl(book.formats)
            ?: error("Gutenberg book $id has no downloadable formats")

        return http.download(url)
    }

    override fun getMagnetLink(id: String): String? = null

    companion object {
        const val DEFAULT_BASE_URL: String = "https://gutendex.com"
    }
}

package lava.tracker.nnmclub.feature

import lava.tracker.api.feature.DownloadableTracker
import lava.tracker.nnmclub.http.NnmclubHttpClient
import javax.inject.Inject

/**
 * NNM-Club implementation of [DownloadableTracker].
 *
 * URL contract: `<baseUrl>/forum/download.php?id=<id>`.
 */
class NnmclubDownload @Inject constructor(
    private val http: NnmclubHttpClient,
) : DownloadableTracker {

    internal constructor(http: NnmclubHttpClient, baseUrl: String) : this(http) {
        this.baseUrlOverride = baseUrl
    }

    private var baseUrlOverride: String? = null
    private val baseUrl: String get() = baseUrlOverride ?: DEFAULT_BASE_URL

    override suspend fun downloadTorrentFile(id: String): ByteArray =
        http.download("$baseUrl/forum/download.php?id=$id")

    override fun getMagnetLink(id: String): String? = null

    companion object {
        const val DEFAULT_BASE_URL: String = "https://nnmclub.to"
    }
}

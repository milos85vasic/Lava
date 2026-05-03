package lava.tracker.kinozal.feature

import lava.tracker.api.feature.DownloadableTracker
import lava.tracker.kinozal.http.KinozalHttpClient
import javax.inject.Inject

/**
 * Kinozal implementation of [DownloadableTracker].
 *
 * URL contract: `<baseUrl>/download.php?id=<id>`.
 */
class KinozalDownload @Inject constructor(
    private val http: KinozalHttpClient,
) : DownloadableTracker {

    internal constructor(http: KinozalHttpClient, baseUrl: String) : this(http) {
        this.baseUrlOverride = baseUrl
    }

    private var baseUrlOverride: String? = null
    private val baseUrl: String get() = baseUrlOverride ?: DEFAULT_DOWNLOAD_BASE_URL

    override suspend fun downloadTorrentFile(id: String): ByteArray =
        http.download("$baseUrl/download.php?id=$id")

    override fun getMagnetLink(id: String): String? = null

    companion object {
        const val DEFAULT_DOWNLOAD_BASE_URL: String = "https://kinozal.tv"
    }
}

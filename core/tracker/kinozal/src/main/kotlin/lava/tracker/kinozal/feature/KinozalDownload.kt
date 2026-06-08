package lava.tracker.kinozal.feature

import lava.tracker.api.feature.DownloadableTracker
import lava.tracker.kinozal.http.KinozalHttpClient
import lava.tracker.kinozal.magnet.KinozalMagnetCache
import javax.inject.Inject

/**
 * Kinozal implementation of [DownloadableTracker].
 *
 * URL contract: `<baseUrl>/download.php?id=<id>`.
 *
 * [getMagnetLink] honours [DownloadableTracker]'s "no hidden HTTP fetch"
 * contract: the magnet lives on the topic page, so it is read from
 * [KinozalMagnetCache], which `KinozalTopic.getTopic` populates when the user
 * opens a topic. This makes the descriptor's declared MAGNET_LINK capability
 * honest (§6.E) — a real magnet reaches the user via the synchronous lookup
 * once the topic has been viewed, instead of the previous hardcoded `null`.
 */
class KinozalDownload @Inject constructor(
    private val http: KinozalHttpClient,
    private val magnetCache: KinozalMagnetCache,
) : DownloadableTracker {

    internal constructor(
        http: KinozalHttpClient,
        magnetCache: KinozalMagnetCache,
        baseUrl: String,
    ) : this(http, magnetCache) {
        this.baseUrlOverride = baseUrl
    }

    private var baseUrlOverride: String? = null
    private val baseUrl: String get() = baseUrlOverride ?: DEFAULT_DOWNLOAD_BASE_URL

    override suspend fun downloadTorrentFile(id: String): ByteArray =
        http.download("$baseUrl/download.php?id=$id")

    override fun getMagnetLink(id: String): String? = magnetCache.get(id)

    companion object {
        const val DEFAULT_DOWNLOAD_BASE_URL: String = "https://kinozal.tv"
    }
}

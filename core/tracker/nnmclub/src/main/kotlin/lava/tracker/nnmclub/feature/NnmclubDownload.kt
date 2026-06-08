package lava.tracker.nnmclub.feature

import lava.tracker.api.feature.DownloadableTracker
import lava.tracker.nnmclub.http.NnmclubHttpClient
import lava.tracker.nnmclub.http.NnmclubMagnetCache
import javax.inject.Inject

/**
 * NNM-Club implementation of [DownloadableTracker].
 *
 * URL contract: `<baseUrl>/forum/download.php?id=<id>`.
 *
 * §6.E Capability Honesty: NnmclubDescriptor declares `MAGNET_LINK`. NNM-Club
 * embeds the magnet in the topic page and in search rows; the production
 * parsers extract it. [getMagnetLink] surfaces that genuinely-parsed magnet via
 * [NnmclubMagnetCache], which [NnmclubTopic] / [NnmclubSearch] populate on
 * fetch. When an id was never surfaced the cache is empty and [getMagnetLink]
 * returns null — an honest absence per the synchronous-only contract, never a
 * fabricated string.
 */
class NnmclubDownload @Inject constructor(
    private val http: NnmclubHttpClient,
    private val magnetCache: NnmclubMagnetCache,
) : DownloadableTracker {

    internal constructor(
        http: NnmclubHttpClient,
        magnetCache: NnmclubMagnetCache,
        baseUrl: String,
    ) : this(http, magnetCache) {
        this.baseUrlOverride = baseUrl
    }

    private var baseUrlOverride: String? = null
    private val baseUrl: String get() = baseUrlOverride ?: DEFAULT_BASE_URL

    override suspend fun downloadTorrentFile(id: String): ByteArray =
        http.download("$baseUrl/forum/download.php?id=$id")

    override fun getMagnetLink(id: String): String? = magnetCache.get(id)

    companion object {
        const val DEFAULT_BASE_URL: String = "https://nnmclub.to"
    }
}

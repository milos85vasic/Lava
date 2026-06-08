package lava.tracker.rutor.feature

import lava.tracker.api.feature.DownloadableTracker
import lava.tracker.rutor.http.RuTorHttpClient
import lava.tracker.rutor.magnet.RuTorMagnetCache
import javax.inject.Inject

/**
 * RuTor implementation of [DownloadableTracker] (SP-3a Task 3.39, Section I).
 *
 * URL contract: `https://d.rutor.info/download/<id>` — the same domain rutor's
 * own "Скачать" anchor links to (verified by inspecting topic-page HTML; the
 * `#download a[href*=/download/]` element resolves to `//d.rutor.info/download/<id>`).
 *
 * §6.E Capability Honesty: [lava.tracker.rutor.RuTorDescriptor] declares
 * `MAGNET_LINK`. RuTor embeds the magnet in the topic page and in search rows;
 * the production parsers extract it into `magnetUri`. [getMagnetLink] surfaces
 * that genuinely-parsed magnet via [RuTorMagnetCache], which [RuTorTopic] /
 * [RuTorSearch] populate on fetch. When an id was never surfaced the cache is
 * empty and [getMagnetLink] returns null — an honest absence per the
 * synchronous-only `DownloadableTracker` contract ("not synchronously available
 * without an HTTP fetch"), never a fabricated string.
 */
class RuTorDownload @Inject constructor(
    private val http: RuTorHttpClient,
    private val magnetCache: RuTorMagnetCache,
) : DownloadableTracker {

    internal constructor(
        http: RuTorHttpClient,
        magnetCache: RuTorMagnetCache,
        baseUrl: String,
    ) : this(http, magnetCache) {
        this.baseUrlOverride = baseUrl
    }

    private var baseUrlOverride: String? = null
    private val baseUrl: String get() = baseUrlOverride ?: DEFAULT_DOWNLOAD_BASE_URL

    override suspend fun downloadTorrentFile(id: String): ByteArray =
        http.download("$baseUrl/download/$id")

    override fun getMagnetLink(id: String): String? = magnetCache.get(id)

    companion object {
        const val DEFAULT_DOWNLOAD_BASE_URL: String = "https://d.rutor.info"
    }
}

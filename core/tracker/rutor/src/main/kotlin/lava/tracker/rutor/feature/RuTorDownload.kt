package lava.tracker.rutor.feature

import lava.tracker.api.feature.DownloadableTracker
import lava.tracker.rutor.http.RuTorHttpClient
import javax.inject.Inject

/**
 * RuTor implementation of [DownloadableTracker] (SP-3a Task 3.39, Section I).
 *
 * URL contract: `https://d.rutor.info/download/<id>` — the same domain rutor's
 * own "Скачать" anchor links to (verified by inspecting topic-page HTML; the
 * `#download a[href*=/download/]` element resolves to `//d.rutor.info/download/<id>`).
 *
 * [getMagnetLink] returns null per [DownloadableTracker]'s contract — the
 * magnet URI is not synchronously resolvable on rutor without first fetching
 * the topic page. Consumers that need a magnet should call
 * [TopicTracker.getTopic] and read `topic.torrent.magnetUri` from the result.
 * Returning null here is the documented "fetch detail first" signal; clause
 * 6.E (Capability Honesty) — no fabrication.
 */
class RuTorDownload @Inject constructor(
    private val http: RuTorHttpClient,
) : DownloadableTracker {

    internal constructor(http: RuTorHttpClient, baseUrl: String) : this(http) {
        this.baseUrlOverride = baseUrl
    }

    private var baseUrlOverride: String? = null
    private val baseUrl: String get() = baseUrlOverride ?: DEFAULT_DOWNLOAD_BASE_URL

    override suspend fun downloadTorrentFile(id: String): ByteArray =
        http.download("$baseUrl/download/$id")

    override fun getMagnetLink(id: String): String? = null

    companion object {
        const val DEFAULT_DOWNLOAD_BASE_URL: String = "https://d.rutor.info"
    }
}

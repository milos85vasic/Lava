package lava.tracker.kinozal.feature

import lava.tracker.api.feature.TopicTracker
import lava.tracker.api.model.TopicDetail
import lava.tracker.api.model.TopicPage
import lava.tracker.kinozal.http.KinozalHttpClient
import lava.tracker.kinozal.magnet.KinozalMagnetCache
import lava.tracker.kinozal.parser.KinozalTopicParser
import javax.inject.Inject

/**
 * Kinozal implementation of [TopicTracker].
 *
 * URL contract: `<baseUrl>/details.php?id=<id>`.
 *
 * On every successful topic fetch the parsed magnet is recorded into
 * [KinozalMagnetCache] so the synchronous `KinozalDownload.getMagnetLink`
 * lookup can later return the real value (§6.E Capability Honesty — see
 * [KinozalMagnetCache]).
 */
class KinozalTopic @Inject constructor(
    private val http: KinozalHttpClient,
    private val parser: KinozalTopicParser,
    private val magnetCache: KinozalMagnetCache,
) : TopicTracker {

    internal constructor(
        http: KinozalHttpClient,
        parser: KinozalTopicParser,
        magnetCache: KinozalMagnetCache,
        baseUrl: String,
    ) : this(http, parser, magnetCache) {
        this.baseUrlOverride = baseUrl
    }

    private var baseUrlOverride: String? = null
    private val baseUrl: String get() = baseUrlOverride ?: DEFAULT_BASE_URL

    override suspend fun getTopic(id: String): TopicDetail {
        val url = "$baseUrl/details.php?id=$id"
        val response = http.get(url)
        val body = response.use { http.bodyString(it) }
        val detail = parser.parse(body, topicIdHint = id)
        detail.torrent.magnetUri?.let { magnetCache.put(id, it) }
        return detail
    }

    override suspend fun getTopicPage(id: String, page: Int): TopicPage =
        TopicPage(topic = getTopic(id), totalPages = 1, currentPage = 0)

    companion object {
        const val DEFAULT_BASE_URL: String = "https://kinozal.tv"
    }
}

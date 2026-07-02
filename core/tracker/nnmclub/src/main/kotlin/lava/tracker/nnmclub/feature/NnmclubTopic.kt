package lava.tracker.nnmclub.feature

import lava.tracker.api.feature.TopicTracker
import lava.tracker.api.model.TopicDetail
import lava.tracker.api.model.TopicPage
import lava.tracker.nnmclub.http.NnmclubHttpClient
import lava.tracker.nnmclub.http.NnmclubMagnetCache
import lava.tracker.nnmclub.parser.NnmclubTopicParser
import javax.inject.Inject

/**
 * NNM-Club implementation of [TopicTracker].
 *
 * URL contract: `<baseUrl>/forum/viewtopic.php?t=<id>`.
 * NNM-Club does not paginate topic pages; comments are on the same page.
 *
 * §6.E: when a topic page yields a magnet it is recorded in
 * [NnmclubMagnetCache] so the synchronous [NnmclubDownload.getMagnetLink] can
 * surface it for the same topic id.
 */
class NnmclubTopic @Inject constructor(
    private val http: NnmclubHttpClient,
    private val parser: NnmclubTopicParser,
    private val magnetCache: NnmclubMagnetCache,
) : TopicTracker {

    internal constructor(
        http: NnmclubHttpClient,
        parser: NnmclubTopicParser,
        magnetCache: NnmclubMagnetCache,
        baseUrl: String,
    ) : this(http, parser, magnetCache) {
        this.baseUrlOverride = baseUrl
    }

    private var baseUrlOverride: String? = null
    private val baseUrl: String get() = baseUrlOverride ?: DEFAULT_BASE_URL

    override suspend fun getTopic(id: String): TopicDetail {
        val url = "$baseUrl/forum/viewtopic.php?t=$id"
        val html = http.get(url).use { http.bodyString(it) }
        val detail = parser.parse(html, topicIdHint = id)
        magnetCache.put(id, detail.torrent.magnetUri)
        return detail
    }

    override suspend fun getTopicPage(id: String, page: Int): TopicPage =
        TopicPage(topic = getTopic(id), totalPages = 1, currentPage = 0)

    companion object {
        const val DEFAULT_BASE_URL: String = "https://nnmclub.to"
    }
}

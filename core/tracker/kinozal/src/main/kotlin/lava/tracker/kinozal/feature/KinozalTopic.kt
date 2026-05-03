package lava.tracker.kinozal.feature

import lava.tracker.api.feature.TopicTracker
import lava.tracker.api.model.TopicDetail
import lava.tracker.api.model.TopicPage
import lava.tracker.kinozal.http.KinozalHttpClient
import lava.tracker.kinozal.parser.KinozalTopicParser
import javax.inject.Inject

/**
 * Kinozal implementation of [TopicTracker].
 *
 * URL contract: `<baseUrl>/details.php?id=<id>`.
 */
class KinozalTopic @Inject constructor(
    private val http: KinozalHttpClient,
    private val parser: KinozalTopicParser,
) : TopicTracker {

    internal constructor(
        http: KinozalHttpClient,
        parser: KinozalTopicParser,
        baseUrl: String,
    ) : this(http, parser) {
        this.baseUrlOverride = baseUrl
    }

    private var baseUrlOverride: String? = null
    private val baseUrl: String get() = baseUrlOverride ?: DEFAULT_BASE_URL

    override suspend fun getTopic(id: String): TopicDetail {
        val url = "$baseUrl/details.php?id=$id"
        val response = http.get(url)
        val body = response.use { http.bodyString(it) }
        return parser.parse(body, topicIdHint = id)
    }

    override suspend fun getTopicPage(id: String, page: Int): TopicPage =
        TopicPage(topic = getTopic(id), totalPages = 1, currentPage = 0)

    companion object {
        const val DEFAULT_BASE_URL: String = "https://kinozal.tv"
    }
}

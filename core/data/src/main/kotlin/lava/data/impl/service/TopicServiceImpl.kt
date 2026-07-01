package lava.data.impl.service

import lava.auth.api.TokenProvider
import lava.data.api.service.TopicService
import lava.data.converters.toCommentsPage
import lava.data.converters.toTopic
import lava.data.converters.toTopicPage
import lava.models.Page
import lava.models.topic.Post
import lava.models.topic.Topic
import lava.models.topic.TopicPage
import lava.network.api.NetworkApi
import lava.network.api.TopicPageSource
import javax.inject.Inject

class TopicServiceImpl @Inject constructor(
    private val networkApi: NetworkApi,
    private val tokenProvider: TokenProvider,
    private val topicPageSource: TopicPageSource,
) : TopicService {
    override suspend fun getTopic(id: String): Topic {
        return networkApi.getTopic(tokenProvider.getToken(), id, null).toTopic()
    }

    override suspend fun getTopicPage(id: String, page: Int?, providerId: String?): TopicPage {
        // LVA-070 — provider-aware topic fetch. When the topic was opened from a
        // multi-provider search result it carries a source [providerId]; route
        // the fetch to THAT provider's SDK client (the same per-provider client
        // search used) so archiveorg / gutenberg topics resolve against their
        // own upstream instead of the legacy proxy `…/topic2/{id}` endpoint
        // (which, with a LAN / GoApi endpoint configured, does not serve those
        // providers — the topic-detail "Something went wrong" failure). A null
        // result (unknown provider / no TOPIC capability / fetch threw) falls
        // back to the legacy active-endpoint path, preserving prior behaviour.
        if (!providerId.isNullOrBlank()) {
            topicPageSource.getTopicPage(providerId, id, page ?: 0)?.let { dto ->
                return dto.toTopicPage()
            }
        }
        return networkApi.getTopicPage(tokenProvider.getToken(), id, page).toTopicPage()
    }

    override suspend fun getCommentsPage(id: String, page: Int): Page<Post> {
        return networkApi.getTopicPage(tokenProvider.getToken(), id, page).toCommentsPage()
    }

    override suspend fun addComment(topicId: String, message: String): Boolean {
        return networkApi.addComment(tokenProvider.getToken(), topicId, message)
    }
}

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
import javax.inject.Inject

class TopicServiceImpl @Inject constructor(
    private val networkApi: NetworkApi,
    private val tokenProvider: TokenProvider,
) : TopicService {
    override suspend fun getTopic(id: String): Topic {
        return networkApi.getTopic(tokenProvider.getToken(), id, null).toTopic()
    }

    override suspend fun getTopicPage(id: String, page: Int?): TopicPage {
        return networkApi.getTopicPage(tokenProvider.getToken(), id, page).toTopicPage()
    }

    override suspend fun getCommentsPage(id: String, page: Int): Page<Post> {
        return networkApi.getTopicPage(tokenProvider.getToken(), id, page).toCommentsPage()
    }

    override suspend fun addComment(topicId: String, message: String): Boolean {
        return networkApi.addComment(tokenProvider.getToken(), topicId, message)
    }
}

package lava.data.api.service

import lava.models.Page
import lava.models.topic.Post
import lava.models.topic.Topic
import lava.models.topic.TopicPage

interface TopicService {
    suspend fun getTopic(id: String): Topic

    /**
     * Fetches a topic page. When [providerId] is a non-blank registered
     * provider id (threaded from the multi-provider search result the user
     * tapped), the topic is fetched from THAT provider's SDK client; otherwise
     * the legacy active-endpoint path is used. LVA-070.
     */
    suspend fun getTopicPage(id: String, page: Int? = null, providerId: String? = null): TopicPage
    suspend fun getCommentsPage(id: String, page: Int): Page<Post>
    suspend fun addComment(topicId: String, message: String): Boolean
}

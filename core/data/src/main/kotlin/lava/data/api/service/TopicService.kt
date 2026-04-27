package lava.data.api.service

import lava.models.Page
import lava.models.topic.Post
import lava.models.topic.Topic
import lava.models.topic.TopicPage

interface TopicService {
    suspend fun getTopic(id: String): Topic
    suspend fun getTopicPage(id: String, page: Int? = null): TopicPage
    suspend fun getCommentsPage(id: String, page: Int): Page<Post>
    suspend fun addComment(topicId: String, message: String): Boolean
}

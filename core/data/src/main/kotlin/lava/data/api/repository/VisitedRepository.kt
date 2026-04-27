package lava.data.api.repository

import lava.models.topic.Topic
import lava.models.topic.TopicPage
import kotlinx.coroutines.flow.Flow

interface VisitedRepository {
    fun observeTopics(): Flow<List<Topic>>
    fun observeIds(): Flow<List<String>>
    suspend fun add(topic: TopicPage)
    suspend fun clear()
}

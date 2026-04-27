package lava.data.api.repository

import kotlinx.coroutines.flow.Flow
import lava.models.topic.Topic
import lava.models.topic.TopicPage

interface VisitedRepository {
    fun observeTopics(): Flow<List<Topic>>
    fun observeIds(): Flow<List<String>>
    suspend fun add(topic: TopicPage)
    suspend fun clear()
}

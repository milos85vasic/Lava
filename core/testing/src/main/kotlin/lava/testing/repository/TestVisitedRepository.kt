package lava.testing.repository

import lava.data.api.repository.VisitedRepository
import lava.models.topic.Topic
import lava.models.topic.TopicPage
import kotlinx.coroutines.flow.Flow

class TestVisitedRepository : VisitedRepository {
    override fun observeTopics(): Flow<List<Topic>> {
        TODO("Not yet implemented")
    }

    override fun observeIds(): Flow<List<String>> {
        TODO("Not yet implemented")
    }

    override suspend fun add(topic: TopicPage) {
        TODO("Not yet implemented")
    }

    override suspend fun clear() {
    }
}

package lava.data.impl.repository

import lava.data.api.repository.VisitedRepository
import lava.data.converters.toTopic
import lava.data.converters.toVisitedEntity
import lava.database.dao.VisitedTopicDao
import lava.database.entity.VisitedTopicEntity
import lava.models.topic.Topic
import lava.models.topic.TopicPage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class VisitedRepositoryImpl @Inject constructor(
    private val visitedTopicDao: VisitedTopicDao,
) : VisitedRepository {
    override fun observeTopics(): Flow<List<Topic>> {
        return visitedTopicDao.observerAll().map { entities ->
            entities.map(VisitedTopicEntity::toTopic)
        }
    }

    override fun observeIds(): Flow<List<String>> {
        return visitedTopicDao.observerAllIds()
    }

    override suspend fun add(topic: TopicPage) {
        visitedTopicDao.insert(topic.toVisitedEntity())
    }

    override suspend fun clear() {
        visitedTopicDao.deleteAll()
    }
}

package lava.data.impl.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import lava.data.api.repository.VisitedRepository
import lava.data.converters.toTopic
import lava.data.converters.toVisitedEntity
import lava.database.dao.VisitedTopicDao
import lava.database.entity.VisitedTopicEntity
import lava.models.topic.Topic
import lava.models.topic.TopicPage
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

    override fun observeProviderIds(): Flow<Map<String, String?>> {
        // LVA-070 — derive the id→providerId map from the same rows observeTopics
        // reads. associate keeps the newest row's provider id on a duplicate id.
        return visitedTopicDao.observerAll().map { entities ->
            entities.associate { it.id to it.providerId }
        }
    }

    override suspend fun add(topic: TopicPage, providerId: String?) {
        visitedTopicDao.insert(topic.toVisitedEntity(providerId))
    }

    override suspend fun clear() {
        visitedTopicDao.deleteAll()
    }
}

package lava.testing.repository

import kotlinx.coroutines.flow.Flow
import lava.data.api.repository.FavoritesRepository
import lava.models.topic.Topic
import lava.models.topic.TopicModel
import lava.models.topic.Torrent

class TestFavoritesRepository : FavoritesRepository {
    override fun observeTopics(): Flow<List<TopicModel<out Topic>>> {
        TODO("Not yet implemented")
    }

    override fun observeIds(): Flow<List<String>> {
        TODO("Not yet implemented")
    }

    override fun observeUpdatedIds(): Flow<List<String>> {
        TODO("Not yet implemented")
    }

    override suspend fun getIds(): List<String> {
        TODO("Not yet implemented")
    }

    override suspend fun getTorrents(): List<Torrent> {
        TODO("Not yet implemented")
    }

    override suspend fun contains(id: String): Boolean {
        TODO("Not yet implemented")
    }

    override suspend fun add(topic: Topic) {
        TODO("Not yet implemented")
    }

    override suspend fun add(topics: List<Topic>) {
        TODO("Not yet implemented")
    }

    override suspend fun remove(topic: Topic) {
        TODO("Not yet implemented")
    }

    override suspend fun remove(topics: List<Topic>) {
        TODO("Not yet implemented")
    }

    override suspend fun removeById(id: String) {
        TODO("Not yet implemented")
    }

    override suspend fun removeById(ids: List<String>) {
        TODO("Not yet implemented")
    }

    override suspend fun updateTorrent(torrent: Torrent, hasUpdate: Boolean) {
        TODO("Not yet implemented")
    }

    override suspend fun markVisited(id: String) {
        TODO("Not yet implemented")
    }

    override suspend fun clear() {
    }
}

package lava.data.api.repository

import kotlinx.coroutines.flow.Flow
import lava.models.topic.Topic
import lava.models.topic.TopicModel
import lava.models.topic.Torrent

interface FavoritesRepository {
    fun observeTopics(): Flow<List<TopicModel<out Topic>>>
    fun observeIds(): Flow<List<String>>
    fun observeUpdatedIds(): Flow<List<String>>
    suspend fun getIds(): List<String>
    suspend fun getTorrents(): List<Torrent>
    suspend fun contains(id: String): Boolean

    /**
     * LVA-070 — [providerId] is the id of the source tracker/provider the topic
     * came from; persisted on the favorite row so the topic screen can route an
     * archiveorg/gutenberg favorite to HTTP_DOWNLOAD instead of falling back to
     * the active tracker. Null (the default) keeps every existing caller
     * compiling and persists NULL ⇒ active-tracker fallback (legacy behaviour).
     */
    suspend fun add(topic: Topic, providerId: String? = null)
    suspend fun add(topics: List<Topic>)
    suspend fun remove(topic: Topic)
    suspend fun remove(topics: List<Topic>)
    suspend fun removeById(id: String)
    suspend fun removeById(ids: List<String>)
    suspend fun updateTorrent(torrent: Torrent, hasUpdate: Boolean)
    suspend fun markVisited(id: String)
    suspend fun clear()
}

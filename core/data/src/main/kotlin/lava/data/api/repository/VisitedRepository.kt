package lava.data.api.repository

import kotlinx.coroutines.flow.Flow
import lava.models.topic.Topic
import lava.models.topic.TopicPage

interface VisitedRepository {
    fun observeTopics(): Flow<List<Topic>>
    fun observeIds(): Flow<List<String>>

    /**
     * LVA-070 — observe each visited topic id mapped to its persisted source
     * provider id (null when stored with no provider). The visited list overlays
     * this onto its [lava.models.topic.TopicModel]s so a visited-history tap
     * reopens the topic with `?p=<providerId>` and routes to HTTP_DOWNLOAD.
     */
    fun observeProviderIds(): Flow<Map<String, String?>>

    /**
     * LVA-070 — [providerId] is the id of the source tracker/provider the topic
     * page came from; persisted on the visited row so the topic screen can route
     * an archiveorg/gutenberg visited topic to HTTP_DOWNLOAD instead of falling
     * back to the active tracker. Null (the default) keeps every existing caller
     * compiling and persists NULL ⇒ active-tracker fallback (legacy behaviour).
     */
    suspend fun add(topic: TopicPage, providerId: String? = null)
    suspend fun clear()
}

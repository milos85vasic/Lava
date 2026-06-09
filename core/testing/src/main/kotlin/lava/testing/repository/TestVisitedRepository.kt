package lava.testing.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import lava.data.api.repository.VisitedRepository
import lava.models.topic.BaseTopic
import lava.models.topic.Topic
import lava.models.topic.TopicPage
import lava.models.topic.Torrent

/**
 * Behaviorally equivalent in-memory fake of `VisitedRepositoryImpl` (LVA-012,
 * 2026-06-09). Anti-Bluff Pact Third Law: every branch of the real impl MUST
 * have a matching branch in the fake.
 *
 * Real counterpart: `lava.data.impl.repository.VisitedRepositoryImpl`, backed by
 * Room `VisitedTopicDao`. Behavioural contract this fake mirrors:
 *
 *  - `add(topic)` → `visitedTopicDao.insert(topic.toVisitedEntity())`. The DAO
 *    insert uses `OnConflictStrategy.REPLACE`, so adding the same id twice UPSERTS
 *    (no duplicate row, no exception) — unlike Favorites/Bookmarks there is no
 *    PRIMARY-KEY-rejection semantic here. A re-add moves the topic to the newest
 *    position (its timestamp is refreshed by `toVisitedEntity`).
 *  - `observeTopics()` → `observerAll()` ordered `timestamp DESC` (newest first)
 *    mapped to `Topic`. The `VisitedTopicEntity.toTopic` converter yields a
 *    `Torrent` when any torrent field is present, else a `BaseTopic`; the fake
 *    reproduces that branch from the `TopicPage.torrentData` it stored.
 *  - `observeIds()` → `observerAllIds()` (the DAO query for ids has no explicit
 *    ORDER BY, but emits the same id set; the fake keeps newest-first for a
 *    deterministic, equivalent set).
 *  - Both observers emit LIVE updates on every mutation (Room Flow semantics).
 *  - `clear()` → `deleteAll()`.
 *
 * The previous form of this fake threw `TODO("Not yet implemented")` from
 * observeTopics/observeIds/add — a stub bluff fake (Third-Law violation): feature
 * tests could not wire it and silently rolled their own in-memory doubles.
 */
class TestVisitedRepository : VisitedRepository {

    /** Newest-first list of stored topics, mirroring `timestamp DESC` ordering. */
    private val topicsFlow = MutableStateFlow<List<Topic>>(emptyList())

    override fun observeTopics(): Flow<List<Topic>> = topicsFlow

    override fun observeIds(): Flow<List<String>> = topicsFlow.map { list -> list.map(Topic::id) }

    override suspend fun add(topic: TopicPage) {
        val converted = topic.toVisitedTopic()
        topicsFlow.value = listOf(converted) + topicsFlow.value.filterNot { it.id == converted.id }
    }

    override suspend fun clear() {
        topicsFlow.value = emptyList()
    }

    /**
     * Mirrors `VisitedTopicEntity.toTopic`'s branch: a [Torrent] when any torrent
     * field is present, otherwise a [BaseTopic]. The fake derives those fields
     * from [TopicPage.torrentData], exactly as `TopicPage.toVisitedEntity` →
     * `VisitedTopicEntity.toTopic` does end to end in production.
     */
    private fun TopicPage.toVisitedTopic(): Topic {
        val data = torrentData
        val hasTorrentFields = data != null &&
            (
                data.tags != null || data.status != null || data.size != null ||
                    data.seeds != null || data.leeches != null
                )
        return if (!hasTorrentFields) {
            BaseTopic(id = id, title = title, author = author, category = category)
        } else {
            Torrent(
                id = id,
                title = title,
                author = author,
                category = category,
                tags = data!!.tags,
                status = data.status,
                date = null,
                size = data.size,
                seeds = data.seeds,
                leeches = data.leeches,
                magnetLink = data.magnetLink,
            )
        }
    }
}

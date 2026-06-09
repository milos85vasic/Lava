package lava.testing.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import lava.data.api.repository.FavoritesRepository
import lava.models.topic.BaseTopic
import lava.models.topic.Topic
import lava.models.topic.TopicModel
import lava.models.topic.Torrent

/**
 * Behaviorally equivalent in-memory fake of `FavoritesRepositoryImpl` (LVA-012,
 * 2026-06-09). Anti-Bluff Pact Third Law: every branch of the real impl MUST
 * have a matching branch in the fake.
 *
 * Real counterpart: `lava.data.impl.repository.FavoritesRepositoryImpl`, backed by
 * Room `FavoriteTopicDao` whose insert uses `OnConflictStrategy.REPLACE` (UPSERT
 * by id) and whose queries are ordered `timestamp DESC` (newest first). Contract
 * this fake mirrors:
 *
 *  - `add(topic)` → DAO `insert(entity)` (UPSERT). Re-adding the same id replaces
 *    it and refreshes its position to newest; the `hasUpdate` flag resets to false
 *    (a plain `toFavoriteEntity()` carries no hasUpdate).
 *  - `add(topics)` → preserves the incoming order via descending per-index
 *    timestamps, INSERTS only the not-yet-present ids, and UPDATES already-present
 *    ids in place (keeping their original position) by merging non-null fields and
 *    recomputing `hasUpdate` from a changed magnet link — exactly the real impl's
 *    new/updated split (FavoritesRepositoryImpl.add(List)).
 *  - `getIds()` → ids newest-first. `contains(id)` → membership of the id set.
 *  - `getTorrents()` → all stored topics filtered to `Torrent`.
 *  - `remove(topic)` / `remove(topics)` / `removeById(id)` / `removeById(ids)` →
 *    delete the matching id(s), keep the rest.
 *  - `updateTorrent(torrent, hasUpdate)` → UPSERT: insert when absent, else merge
 *    fields over the existing row and set the supplied `hasUpdate`.
 *  - `markVisited(id)` → clear `hasUpdate` on the matching row (DAO clearHasUpdates).
 *  - `observeTopics()` → `TopicModel(isFavorite = true, hasUpdate = …)` newest-first.
 *  - `observeIds()` → ids newest-first. `observeUpdatedIds()` → ids where hasUpdate.
 *  - Every observer emits LIVE updates on every mutation (Room Flow semantics).
 *  - `clear()` → deleteAll.
 *
 * The previous form of this fake threw `TODO("Not yet implemented")` from nearly
 * every method — a stub bluff fake (Third-Law violation): feature tests could not
 * wire it and silently rolled their own in-memory doubles.
 */
class TestFavoritesRepository : FavoritesRepository {

    /**
     * One stored favorite row. Mirrors the relevant columns of
     * `FavoriteTopicEntity`: the topic payload, a monotonically-decreasing
     * timestamp used only for newest-first ordering, and the `hasUpdate` flag.
     */
    private data class Row(
        val topic: Topic,
        val timestamp: Long,
        val hasUpdate: Boolean,
        // LVA-070 — mirrors FavoriteTopicEntity.providerId: the source provider id
        // persisted with the favorite so the topic screen can route to
        // HTTP_DOWNLOAD. Null ⇒ active-tracker fallback (legacy behaviour).
        val providerId: String? = null,
    )

    // Stored newest-first (Room `ORDER BY timestamp DESC`).
    private val rowsFlow = MutableStateFlow<List<Row>>(emptyList())

    // Strictly-increasing timestamp source so the most-recently-added row has the
    // LARGEST timestamp and therefore sorts FIRST under `timestamp DESC`, matching
    // the real DAO's newest-first ordering without relying on
    // System.currentTimeMillis() resolution inside fast tests.
    private var nextTimestamp = 0L

    private fun sorted(rows: List<Row>): List<Row> = rows.sortedByDescending { it.timestamp }

    override fun observeTopics(): Flow<List<TopicModel<out Topic>>> = rowsFlow.map { rows ->
        rows.map { row ->
            TopicModel(
                topic = row.topic,
                isFavorite = true,
                hasUpdate = row.hasUpdate,
                // LVA-070 — surface the persisted provider id on the list item so
                // the favorites list can route a topic-open to HTTP_DOWNLOAD.
                providerId = row.providerId,
            )
        }
    }

    override fun observeIds(): Flow<List<String>> = rowsFlow.map { rows -> rows.map { it.topic.id } }

    override fun observeUpdatedIds(): Flow<List<String>> =
        rowsFlow.map { rows -> rows.filter { it.hasUpdate }.map { it.topic.id } }

    override suspend fun getIds(): List<String> = rowsFlow.value.map { it.topic.id }

    override suspend fun getTorrents(): List<Torrent> =
        rowsFlow.value.mapNotNull { it.topic as? Torrent }

    override suspend fun contains(id: String): Boolean = rowsFlow.value.any { it.topic.id == id }

    override suspend fun add(topic: Topic, providerId: String?) {
        // DAO insert is UPSERT-by-id with a fresh timestamp → moves to newest and
        // resets hasUpdate (a plain toFavoriteEntity carries hasUpdate = false).
        // LVA-070 — the providerId is persisted on the row, exactly as
        // FavoritesRepositoryImpl.add forwards it to toFavoriteEntity(providerId).
        val withoutExisting = rowsFlow.value.filterNot { it.topic.id == topic.id }
        val row = Row(topic = topic, timestamp = nextTimestamp++, hasUpdate = false, providerId = providerId)
        rowsFlow.value = sorted(withoutExisting + row)
    }

    override suspend fun add(topics: List<Topic>) {
        // Mirror FavoritesRepositoryImpl.add(List): preserve incoming order (the
        // real impl uses `timestamp - index` so EARLIER list elements are NEWER),
        // insert the new ids, update the existing in place.
        val existing = rowsFlow.value.associateBy { it.topic.id }
        val baseTimestamp = nextTimestamp
        val updated = existing.toMutableMap()
        topics.forEachIndexed { index, topic ->
            val old = existing[topic.id]
            if (old == null) {
                updated[topic.id] = Row(
                    topic = topic,
                    // Earlier list element → larger timestamp → sorts newer, matching
                    // the real impl's `timestamp - index` order preservation.
                    timestamp = baseTimestamp + (topics.size - 1 - index),
                    hasUpdate = false,
                )
            } else {
                updated[topic.id] = old.copy(
                    topic = mergeForUpdate(old.topic, topic),
                    hasUpdate = magnetChanged(old.topic, topic),
                )
            }
        }
        // Advance past the timestamp range we consumed.
        nextTimestamp = baseTimestamp + topics.size
        rowsFlow.value = sorted(updated.values.toList())
    }

    override suspend fun remove(topic: Topic) = removeById(topic.id)

    override suspend fun remove(topics: List<Topic>) = removeById(topics.map(Topic::id))

    override suspend fun removeById(id: String) {
        rowsFlow.value = rowsFlow.value.filterNot { it.topic.id == id }
    }

    override suspend fun removeById(ids: List<String>) {
        val drop = ids.toSet()
        rowsFlow.value = rowsFlow.value.filterNot { it.topic.id in drop }
    }

    override suspend fun updateTorrent(torrent: Torrent, hasUpdate: Boolean) {
        val existing = rowsFlow.value.firstOrNull { it.topic.id == torrent.id }
        if (existing == null) {
            // DAO insert when absent.
            val row = Row(topic = torrent, timestamp = nextTimestamp++, hasUpdate = hasUpdate)
            rowsFlow.value = sorted(rowsFlow.value + row)
        } else {
            // DAO update-in-place: merge fields, keep position, set hasUpdate.
            val merged = existing.copy(topic = torrent, hasUpdate = hasUpdate)
            rowsFlow.value = sorted(rowsFlow.value.map { if (it.topic.id == torrent.id) merged else it })
        }
    }

    override suspend fun markVisited(id: String) {
        rowsFlow.value = rowsFlow.value.map {
            if (it.topic.id == id) it.copy(hasUpdate = false) else it
        }
    }

    override suspend fun clear() {
        rowsFlow.value = emptyList()
    }

    /** Synchronous snapshot for assertions that don't collect the flow. */
    fun currentTopics(): List<Topic> = rowsFlow.value.map { it.topic }

    /**
     * LVA-070 — synchronous read-back of the persisted provider id for a stored
     * favorite, or null when the id is absent or was stored with no provider.
     * Mirrors reading FavoriteTopicEntity.providerId back out of Room.
     */
    fun providerIdOf(id: String): String? =
        rowsFlow.value.firstOrNull { it.topic.id == id }?.providerId

    private fun magnetChanged(old: Topic, update: Topic): Boolean {
        val oldMagnet = (old as? Torrent)?.magnetLink
        val newMagnet = (update as? Torrent)?.magnetLink
        return oldMagnet != null && newMagnet != null && oldMagnet != newMagnet
    }

    /**
     * Mirrors the field-by-field merge in `FavoritesRepositoryImpl.add(List)`'s
     * update branch: keep the old value where the update is null. Only the
     * `Torrent` branch carries torrent-specific fields; `BaseTopic` updates merge
     * the shared identity fields.
     */
    private fun mergeForUpdate(old: Topic, update: Topic): Topic {
        if (old is Torrent || update is Torrent) {
            val oldT = old as? Torrent
            val updT = update as? Torrent
            return Torrent(
                id = update.id,
                title = update.title,
                author = update.author ?: old.author,
                category = update.category ?: old.category,
                tags = updT?.tags ?: oldT?.tags,
                status = updT?.status ?: oldT?.status,
                date = updT?.date ?: oldT?.date,
                size = updT?.size ?: oldT?.size,
                seeds = updT?.seeds ?: oldT?.seeds,
                leeches = updT?.leeches ?: oldT?.leeches,
                magnetLink = updT?.magnetLink ?: oldT?.magnetLink,
            )
        }
        return BaseTopic(
            id = update.id,
            title = update.title,
            author = update.author ?: old.author,
            category = update.category ?: old.category,
        )
    }
}

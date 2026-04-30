package lava.tracker.client.persistence

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import lava.database.dao.UserMirrorDao
import lava.database.entity.UserMirrorEntity
import lava.sdk.api.MirrorUrl
import lava.sdk.api.Protocol
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistence wrapper around [UserMirrorDao]. Holds user-supplied custom
 * mirror URLs that layer on top of the bundled mirrors.json — see
 * [MirrorConfigLoader]. Added in SP-3a Phase 4 (Task 4.2).
 *
 * Composite primary key (tracker_id, url) on the underlying entity rejects
 * duplicate URLs within a tracker (Room throws SQLiteConstraintException).
 * [add] catches this and surfaces it as a domain-level boolean.
 */
@Singleton
class UserMirrorRepository @Inject constructor(
    private val dao: UserMirrorDao,
) {

    suspend fun loadAll(trackerId: String): List<UserMirrorEntity> = dao.loadAll(trackerId)

    fun observe(trackerId: String): Flow<List<UserMirrorEntity>> = dao.observe(trackerId)

    suspend fun loadAsMirrorUrls(trackerId: String): List<MirrorUrl> =
        dao.loadAll(trackerId).map { it.toMirrorUrl() }

    fun observeAsMirrorUrls(trackerId: String): Flow<List<MirrorUrl>> =
        dao.observe(trackerId).map { rows -> rows.map { it.toMirrorUrl() } }

    /**
     * Adds a custom mirror. Returns false when the (trackerId, url) row
     * already exists (Room would either replace it on REPLACE conflict
     * or throw on ABORT — we use REPLACE here to preserve the latest
     * priority/protocol the user typed, but signal the duplicate via
     * a separate existence check).
     */
    suspend fun add(
        trackerId: String,
        url: String,
        priority: Int,
        protocol: Protocol,
        addedAt: Long = System.currentTimeMillis(),
    ): Boolean {
        val existing = dao.loadAll(trackerId).any { it.url == url }
        dao.upsert(
            UserMirrorEntity(
                trackerId = trackerId,
                url = url,
                priority = priority,
                protocol = protocol.name,
                addedAt = addedAt,
            ),
        )
        return !existing
    }

    suspend fun remove(trackerId: String, url: String) {
        dao.delete(trackerId, url)
    }

    suspend fun clear(trackerId: String) {
        dao.clear(trackerId)
    }

    private fun UserMirrorEntity.toMirrorUrl(): MirrorUrl = MirrorUrl(
        url = url,
        isPrimary = false,
        priority = priority,
        protocol = Protocol.valueOf(protocol),
    )
}

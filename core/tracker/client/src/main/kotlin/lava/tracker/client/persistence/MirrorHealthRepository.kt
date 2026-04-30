package lava.tracker.client.persistence

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant
import lava.database.dao.MirrorHealthDao
import lava.database.entity.MirrorHealthEntity
import lava.sdk.api.HealthState
import lava.sdk.api.MirrorState
import lava.sdk.api.MirrorUrl
import lava.sdk.api.Protocol
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistence wrapper around [MirrorHealthDao] that maps Room entities to
 * SDK [MirrorState] values. Added in SP-3a Phase 4 (Task 4.2).
 *
 * Stores ONLY the per-mirror health state — the underlying [MirrorUrl]
 * priority/protocol metadata comes from the bundled `mirrors.json` plus
 * the user-supplied [UserMirrorRepository] entries (merged by
 * [MirrorConfigLoader]). When loading state for a tracker we hydrate the
 * URL/priority/protocol from the supplied [knownMirrors] map.
 */
@Singleton
class MirrorHealthRepository @Inject constructor(
    private val dao: MirrorHealthDao,
) {

    suspend fun loadAll(trackerId: String): List<MirrorHealthEntity> = dao.loadAll(trackerId)

    fun observe(trackerId: String): Flow<List<MirrorHealthEntity>> = dao.observe(trackerId)

    /**
     * Loads persisted [MirrorState]s for [trackerId], reconstructing the
     * [MirrorUrl] from [knownMirrors] when present. Mirrors with no entry
     * in [knownMirrors] are dropped (the user removed them since the
     * snapshot was taken).
     */
    suspend fun loadStates(trackerId: String, knownMirrors: List<MirrorUrl>): List<MirrorState> {
        val byUrl = knownMirrors.associateBy { it.url }
        return dao.loadAll(trackerId).mapNotNull { row ->
            val mirror = byUrl[row.mirrorUrl] ?: return@mapNotNull null
            MirrorState(
                mirror = mirror,
                health = HealthState.valueOf(row.state),
                lastCheck = row.lastCheckAt?.let { Instant.fromEpochMilliseconds(it) },
                consecutiveFailures = row.consecutiveFailures,
            )
        }
    }

    fun observeStates(
        trackerId: String,
        knownMirrors: List<MirrorUrl>,
    ): Flow<List<MirrorState>> {
        val byUrl = knownMirrors.associateBy { it.url }
        return dao.observe(trackerId).map { rows ->
            rows.mapNotNull { row ->
                val mirror = byUrl[row.mirrorUrl] ?: return@mapNotNull null
                MirrorState(
                    mirror = mirror,
                    health = HealthState.valueOf(row.state),
                    lastCheck = row.lastCheckAt?.let { Instant.fromEpochMilliseconds(it) },
                    consecutiveFailures = row.consecutiveFailures,
                )
            }
        }
    }

    suspend fun upsertAll(trackerId: String, states: List<MirrorState>) {
        if (states.isEmpty()) return
        dao.upsertAll(states.map { it.toEntity(trackerId) })
    }

    suspend fun upsert(trackerId: String, state: MirrorState) {
        dao.upsert(state.toEntity(trackerId))
    }

    suspend fun clear(trackerId: String) {
        dao.clear(trackerId)
    }

    private fun MirrorState.toEntity(trackerId: String): MirrorHealthEntity =
        MirrorHealthEntity(
            trackerId = trackerId,
            mirrorUrl = mirror.url,
            state = health.name,
            lastCheckAt = lastCheck?.toEpochMilliseconds(),
            consecutiveFailures = consecutiveFailures,
        )

    companion object {
        fun mirrorUrlFromProtocol(name: String): Protocol = Protocol.valueOf(name)
    }
}

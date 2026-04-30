package lava.tracker.client.persistence

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds
import lava.sdk.api.MirrorState
import lava.sdk.mirror.DefaultHealthProbe
import lava.sdk.mirror.DefaultMirrorManager
import lava.sdk.mirror.HealthProbe
import lava.sdk.mirror.MirrorGroup
import lava.sdk.mirror.MirrorManager
import lava.tracker.registry.TrackerRegistry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds one [MirrorManager] per registered tracker. Lazy: the manager for
 * tracker `X` is built on first use, populated from the merged
 * [MirrorConfigLoader] view (bundled + user) and a [DefaultHealthProbe]
 * tuned to the tracker's `expectedHealthMarker`.
 *
 * Added in SP-3a Phase 4 (Task 4.4). Inserted as a separate Hilt
 * @Singleton so the Worker, the SDK facade, and the Settings ViewModel all
 * see the same in-memory state.
 */
@Singleton
class LavaMirrorManagerHolder @Inject constructor(
    private val registry: TrackerRegistry,
    private val configLoader: MirrorConfigLoader,
) {
    /**
     * Test-only constructor that swaps the default network-backed
     * [DefaultHealthProbe] for a stub. Hilt uses the @Inject constructor
     * above, which always picks up [DefaultHealthProbeFactory].
     */
    constructor(
        registry: TrackerRegistry,
        configLoader: MirrorConfigLoader,
        probeFactory: HealthProbeFactory,
    ) : this(registry, configLoader) {
        this.probeFactory = probeFactory
    }

    private var probeFactory: HealthProbeFactory = DefaultHealthProbeFactory

    private val managers = HashMap<String, MirrorManager>()
    private val mutex = Mutex()

    suspend fun managerFor(trackerId: String): MirrorManager {
        managers[trackerId]?.let { return it }
        return mutex.withLock {
            managers[trackerId]?.let { return@withLock it }
            val descriptor = registry.list().firstOrNull { it.trackerId == trackerId }
                ?: error("Unknown tracker id: '$trackerId' (registered: ${registry.list().map { it.trackerId }})")
            val mirrors = configLoader.loadFor(trackerId).ifEmpty { descriptor.baseUrls }
            val marker = configLoader.bundledMarkerFor(trackerId) ?: descriptor.expectedHealthMarker
            val probe = probeFactory.create(marker)
            val manager: MirrorManager = DefaultMirrorManager(
                initialGroups = listOf(MirrorGroup(groupId = trackerId, mirrors = mirrors, expectedMarker = marker)),
                healthProbe = probe,
            )
            managers[trackerId] = manager
            manager
        }
    }

    /** Returns null when the manager has not been initialised yet. */
    fun managerForOrNull(trackerId: String): MirrorManager? = managers[trackerId]

    /** Convenience: probeAll across the manager for [trackerId]. */
    suspend fun probeAll(trackerId: String) {
        managerFor(trackerId).probeAll(trackerId)
    }

    /** Convenience: observeHealth across the manager for [trackerId]. */
    suspend fun observeHealth(trackerId: String): Flow<List<MirrorState>> =
        managerFor(trackerId).observeHealth(trackerId)

    /**
     * Synchronous variant used by callers that already know the manager has
     * been initialised. Returns an empty Flow when not yet initialised — the
     * caller MUST treat this as "manager not initialised yet" and fall back
     * to repository-only state.
     */
    fun observeHealthOrEmpty(trackerId: String): Flow<List<MirrorState>> {
        val mgr = managers[trackerId] ?: return flowOf(emptyList())
        return mgr.observeHealth(trackerId)
    }

    fun interface HealthProbeFactory {
        fun create(expectedMarker: String): HealthProbe
    }

    private object DefaultHealthProbeFactory : HealthProbeFactory {
        override fun create(expectedMarker: String): HealthProbe =
            DefaultHealthProbe(expectedMarker = expectedMarker, timeout = 8.seconds)
    }
}

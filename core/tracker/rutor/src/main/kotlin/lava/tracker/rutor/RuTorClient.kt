package lava.tracker.rutor

import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.api.TrackerFeature
import javax.inject.Inject
import kotlin.reflect.KClass

/**
 * Skeleton — full implementation in Task 3.40 (Section J).
 *
 * Compile is blocked until [RuTorDescriptor] is added in Task 3.6 (Section B).
 * That blocking is intentional: it forces Section A's RuTorClient placeholder
 * to be completed by Section B before the module turns green again, preventing
 * a stray no-op skeleton from drifting into a release.
 */
class RuTorClient @Inject constructor() : TrackerClient {
    override val descriptor: TrackerDescriptor = RuTorDescriptor

    override suspend fun healthCheck(): Boolean = false

    override fun <T : TrackerFeature> getFeature(featureClass: KClass<T>): T? = null

    override fun close() {
        // No HTTP resources owned directly here; Section J wires the real client.
    }
}

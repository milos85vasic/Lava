package lava.tracker.client

import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.api.TrackerFeature
import kotlin.reflect.KClass

/**
 * SP-4 Phase F.1 (2026-05-13). Wrapper that lets a synthetic
 * cloned-provider id reach the SDK's search/browse/favorites paths
 * without crashing on `registry.get(syntheticId)`.
 *
 * Strategy: delegate ALL operations to the source [TrackerClient]
 * (same parser, same auth, same mirrors), but surface the
 * [cloneDescriptor] as `descriptor` so:
 *
 *  - `LavaTrackerSdk.search(...)` reads `client.descriptor.trackerId`
 *    into [SearchOutcome.Success.viaTracker], which becomes the clone's
 *    synthetic id (not the source's id) — the only user-observable
 *    leak of "which tracker actually responded" in the single-provider
 *    path.
 *  - `LavaTrackerSdk.multiSearch(...)` keys per-provider statuses by
 *    the explicit `id` passed into the loop (which is already the
 *    clone's synthetic id), so the descriptor override there is
 *    informational.
 *
 * Initial implementation also re-tagged each returned [TorrentItem]
 * with `trackerId = cloneDescriptor.trackerId`. The 2026-05-13
 * Phase F.1 falsifiability rehearsal proved that re-tag had no
 * observable downstream consumer: every UI surface keys on the
 * explicit per-provider id passed through the SDK seam (the
 * `providerId` field on `TopicModel`, the map key in
 * `DeduplicationEngine`, the loop variable in `streamMultiSearch`),
 * not on `TorrentItem.trackerId`. The re-tag was removed as dead code
 * per §6.J ("don't add code for hypothetical future requirements").
 *
 * Out of scope (Phase F.2 owed): per-clone `MirrorManager` so the
 * actual HTTP traffic hits the clone's `primaryUrl`. F.1 ships the
 * "clone is reachable, descriptor identifies it correctly" surface;
 * the URL routing is disclosed at clone-creation time via the
 * ProviderConfig Toast copy ("URL routing pending — searches use
 * source URLs").
 */
internal class ClonedRoutingTrackerClient(
    private val sourceClient: TrackerClient,
    private val cloneDescriptor: TrackerDescriptor,
) : TrackerClient {

    override val descriptor: TrackerDescriptor = cloneDescriptor

    override suspend fun healthCheck(): Boolean = sourceClient.healthCheck()

    override fun close() = sourceClient.close()

    override fun <T : TrackerFeature> getFeature(featureClass: KClass<T>): T? =
        sourceClient.getFeature(featureClass)
}

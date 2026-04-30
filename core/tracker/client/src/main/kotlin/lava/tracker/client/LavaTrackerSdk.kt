package lava.tracker.client

import javax.inject.Inject
import javax.inject.Singleton
import lava.sdk.api.MapPluginConfig
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.api.feature.SearchableTracker
import lava.tracker.api.model.SearchRequest
import lava.tracker.api.model.TopicDetail
import lava.tracker.registry.TrackerRegistry

/**
 * Public SDK facade — the entry point feature ViewModels use to talk to the
 * tracker layer (added in SP-3a Section F, Task 2.30).
 *
 * Today the registry holds a single tracker (`rutracker`); Section H will
 * register additional trackers and the [activeTrackerId] knob becomes
 * meaningful. Until then, [search] / [browse] / [getTopic] / [downloadTorrent]
 * always go through the rutracker plugin.
 *
 * Capability Honesty (clause 6.E): every call site checks `client.getFeature(...)`
 * for null and returns a `Failure` outcome — never throws — when the active
 * tracker does not declare the requested capability. Callers MUST handle the
 * sealed-outcome cases exhaustively.
 *
 * Visibility note for Section G: [getActiveClient] is `internal` so
 * `:core:network:impl`'s `SwitchingNetworkApi` (which lives in the same
 * Gradle build but a different Kotlin module) can NOT reach it — that's
 * intentional. Section G will need to either introduce a typed accessor on
 * this class or add `:core:network:impl` to the same module-friendship via
 * a dedicated `internal` API surface (TBD in Section G's design).
 */
@Singleton
class LavaTrackerSdk @Inject constructor(
    private val registry: TrackerRegistry,
) {
    private var activeTrackerId: String = DEFAULT_TRACKER_ID

    /** Returns descriptors of all registered trackers. Order is unspecified. */
    fun listAvailableTrackers(): List<TrackerDescriptor> = registry.list()

    /** Switches the active tracker. Throws [IllegalArgumentException] if [trackerId] is unknown. */
    fun switchTracker(trackerId: String) {
        require(registry.isRegistered(trackerId)) {
            "Unknown tracker id: '$trackerId' (registered: ${registry.list().map { it.trackerId }})"
        }
        activeTrackerId = trackerId
    }

    /** Currently-active tracker id. Defaults to "rutracker". */
    fun activeTrackerId(): String = activeTrackerId

    /**
     * Runs a search against the active tracker. Returns:
     *  - [SearchOutcome.Success] on a clean response.
     *  - [SearchOutcome.Failure] when the active tracker doesn't support SEARCH
     *    (Capability Honesty), or when the underlying call throws.
     */
    suspend fun search(request: SearchRequest, page: Int = 0): SearchOutcome {
        val client = getActiveClient()
        val trackerId = client.descriptor.trackerId
        val feature = client.getFeature(SearchableTracker::class)
            ?: return SearchOutcome.Failure(
                reason = "tracker '$trackerId' does not support SEARCH",
                triedTrackers = listOf(trackerId),
            )
        return try {
            SearchOutcome.Success(result = feature.search(request, page), viaTracker = trackerId)
        } catch (t: Throwable) {
            SearchOutcome.Failure(
                reason = t.message ?: "search failed",
                triedTrackers = listOf(trackerId),
                cause = t,
            )
        }
    }

    /** TODO(Task 2.31): browse, with the same Capability-Honesty pattern as [search]. */
    suspend fun browse(@Suppress("UNUSED_PARAMETER") category: String?, @Suppress("UNUSED_PARAMETER") page: Int = 0): BrowseOutcome =
        TODO("Task 2.31")

    /** TODO(Task 2.31): topic detail. */
    suspend fun getTopic(@Suppress("UNUSED_PARAMETER") topicId: String): TopicDetail? =
        TODO("Task 2.31")

    /** TODO(Task 2.31): magnet link (synchronous, may return null). */
    fun getMagnetLink(@Suppress("UNUSED_PARAMETER") topicId: String): String? =
        TODO("Task 2.31")

    /** TODO(Task 2.31): torrent file download. */
    suspend fun downloadTorrent(@Suppress("UNUSED_PARAMETER") topicId: String): ByteArray? =
        TODO("Task 2.31")

    /**
     * Resolves the active [TrackerClient] from the registry. `internal` so
     * Section G's `SwitchingNetworkApi` rewire can opt into reaching this
     * via a sibling-module surface to be designed in Section G.
     */
    internal fun getActiveClient(): TrackerClient =
        registry.get(activeTrackerId, MapPluginConfig())

    companion object {
        const val DEFAULT_TRACKER_ID: String = "rutracker"
    }
}

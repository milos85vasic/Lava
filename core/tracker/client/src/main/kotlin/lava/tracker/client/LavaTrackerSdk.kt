package lava.tracker.client

import javax.inject.Inject
import javax.inject.Singleton
import lava.sdk.api.MapPluginConfig
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.api.feature.BrowsableTracker
import lava.tracker.api.feature.DownloadableTracker
import lava.tracker.api.feature.SearchableTracker
import lava.tracker.api.feature.TopicTracker
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

    /**
     * Browses a category on the active tracker. Same Capability-Honesty pattern
     * as [search]: returns [BrowseOutcome.Failure] when the tracker doesn't
     * support BROWSE rather than throwing.
     */
    suspend fun browse(category: String?, page: Int = 0): BrowseOutcome {
        val client = getActiveClient()
        val trackerId = client.descriptor.trackerId
        val feature = client.getFeature(BrowsableTracker::class)
            ?: return BrowseOutcome.Failure(
                reason = "tracker '$trackerId' does not support BROWSE",
                triedTrackers = listOf(trackerId),
            )
        return try {
            BrowseOutcome.Success(result = feature.browse(category, page), viaTracker = trackerId)
        } catch (t: Throwable) {
            BrowseOutcome.Failure(
                reason = t.message ?: "browse failed",
                triedTrackers = listOf(trackerId),
                cause = t,
            )
        }
    }

    /**
     * Fetches topic detail for [topicId]. Returns null when the active tracker
     * doesn't support TOPIC, or when the underlying call throws — callers that
     * need a failure reason should use the per-feature interface directly.
     */
    suspend fun getTopic(topicId: String): TopicDetail? {
        val feature = getActiveClient().getFeature(TopicTracker::class) ?: return null
        return try {
            feature.getTopic(topicId)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Returns the magnet URI for [topicId] if the active tracker supports
     * synchronous magnet retrieval. RuTracker's current impl is null-only
     * (a topic-page fetch is required); this is preserved here.
     */
    fun getMagnetLink(topicId: String): String? {
        val feature = getActiveClient().getFeature(DownloadableTracker::class) ?: return null
        return feature.getMagnetLink(topicId)
    }

    /**
     * Downloads the .torrent file bytes for [topicId]. Returns null when the
     * active tracker doesn't support TORRENT_DOWNLOAD, or when the underlying
     * call throws.
     */
    suspend fun downloadTorrent(topicId: String): ByteArray? {
        val feature = getActiveClient().getFeature(DownloadableTracker::class) ?: return null
        return try {
            feature.downloadTorrentFile(topicId)
        } catch (_: Throwable) {
            null
        }
    }

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

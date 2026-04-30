package lava.tracker.client

import lava.sdk.api.MapPluginConfig
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.api.feature.AuthenticatableTracker
import lava.tracker.api.feature.BrowsableTracker
import lava.tracker.api.feature.CommentsTracker
import lava.tracker.api.feature.DownloadableTracker
import lava.tracker.api.feature.FavoritesTracker
import lava.tracker.api.feature.SearchableTracker
import lava.tracker.api.feature.TopicTracker
import lava.tracker.api.model.AuthState
import lava.tracker.api.model.CommentsPage
import lava.tracker.api.model.ForumTree
import lava.tracker.api.model.LoginRequest
import lava.tracker.api.model.LoginResult
import lava.tracker.api.model.SearchRequest
import lava.tracker.api.model.TopicDetail
import lava.tracker.api.model.TopicPage
import lava.tracker.api.model.TorrentItem
import lava.tracker.registry.TrackerRegistry
import javax.inject.Inject
import javax.inject.Singleton

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
 * Visibility note (resolved in SP-3a Section G, Task 2.32): rather than
 * expose [getActiveClient] across the module boundary, the facade is
 * expanded with method-level wrappers covering every NetworkApi operation
 * Section G's [SwitchingNetworkApi] needs. The pattern is the same Capability
 * Honesty contract used by [search]/[browse]/[getTopic]: feature lookups
 * return null when the active tracker doesn't declare the capability,
 * underlying-call exceptions are caught and surfaced as null/Failure rather
 * than thrown. [getActiveClient] stays `internal` so consumers can't smuggle
 * tracker-specific behaviour past the Lava-domain seam.
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
     * Fetches a single page of a topic (a torrent's primary post + meta
     * sidebar) from the active tracker. Returns null when the tracker
     * doesn't support TOPIC, or when the underlying call throws.
     *
     * Section G uses this for [SwitchingNetworkApi.getTopicPage]; feature
     * ViewModels that need detailed errors should use [TopicTracker] directly.
     */
    suspend fun getTopicPage(topicId: String, page: Int): TopicPage? {
        val feature = getActiveClient().getFeature(TopicTracker::class) ?: return null
        return try {
            feature.getTopicPage(topicId, page)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Fetches a comments page for [topicId] from the active tracker. Returns
     * null when the tracker doesn't support COMMENTS, or when the underlying
     * call throws. The legacy NetworkApi also accepts a page; we propagate it.
     */
    suspend fun getCommentsPage(topicId: String, page: Int): CommentsPage? {
        val feature = getActiveClient().getFeature(CommentsTracker::class) ?: return null
        return try {
            feature.getComments(topicId, page)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Posts a comment to [topicId] on the active tracker. Returns false when
     * the tracker doesn't support COMMENTS or when the underlying call throws.
     * Boolean is the user-visible signal feature ViewModels render
     * ("posted" / "failed"), so wrapping in an Outcome would be over-engineered
     * (per SP-3a Section G, Task 2.32 Pre-authorized adaptation B).
     */
    suspend fun addComment(topicId: String, message: String): Boolean {
        val feature = getActiveClient().getFeature(CommentsTracker::class) ?: return false
        return try {
            feature.addComment(topicId, message)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Returns the active tracker's full forum tree, or null when the tracker
     * doesn't support BROWSE or doesn't surface a tree (e.g. RuTor).
     */
    suspend fun getForumTree(): ForumTree? {
        val feature = getActiveClient().getFeature(BrowsableTracker::class) ?: return null
        return try {
            feature.getForumTree()
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Returns the user's favorites list on the active tracker, or null when
     * the tracker doesn't support FAVORITES, or when the underlying call
     * throws. Distinct from an empty list (= authenticated, but no favorites).
     */
    suspend fun getFavorites(): List<TorrentItem>? {
        val feature = getActiveClient().getFeature(FavoritesTracker::class) ?: return null
        return try {
            feature.list()
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Adds [topicId] to the user's favorites on the active tracker. Returns
     * false when the tracker doesn't support FAVORITES or the call throws.
     */
    suspend fun addFavorite(topicId: String): Boolean {
        val feature = getActiveClient().getFeature(FavoritesTracker::class) ?: return false
        return try {
            feature.add(topicId)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Removes [topicId] from the user's favorites on the active tracker.
     * Returns false when the tracker doesn't support FAVORITES or the call
     * throws.
     */
    suspend fun removeFavorite(topicId: String): Boolean {
        val feature = getActiveClient().getFeature(FavoritesTracker::class) ?: return false
        return try {
            feature.remove(topicId)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Performs a login on the active tracker. Returns null when the tracker
     * doesn't support AUTH; the caller MUST treat null as a hard "tracker
     * cannot authenticate" signal (the legacy NetworkApi.login throws on
     * non-authenticatable trackers, but trackers without AUTH_REQUIRED are
     * a Phase 3+ concern; rutracker is always authenticatable).
     */
    suspend fun login(request: LoginRequest): LoginResult? {
        val feature = getActiveClient().getFeature(AuthenticatableTracker::class) ?: return null
        return feature.login(request)
    }

    /**
     * Returns the active tracker's auth state, or null when the tracker
     * doesn't support AUTH (in which case "not authenticatable" is the
     * appropriate user-visible behaviour).
     */
    suspend fun checkAuth(): AuthState? {
        val feature = getActiveClient().getFeature(AuthenticatableTracker::class) ?: return null
        return try {
            feature.checkAuth()
        } catch (_: Throwable) {
            AuthState.Unauthenticated
        }
    }

    /**
     * Logs out of the active tracker. No-op when the tracker doesn't support
     * AUTH. Mirrors AuthenticatableTracker.logout().
     */
    suspend fun logout() {
        val feature = getActiveClient().getFeature(AuthenticatableTracker::class) ?: return
        try {
            feature.logout()
        } catch (_: Throwable) {
            // logout failures are not user-visible — we already invalidated the local state.
        }
    }

    /**
     * Resolves the active [TrackerClient] from the registry. `internal` so
     * the new SDK seam stays the only public surface — the SDK's wrapper
     * methods (search/browse/getTopic/getTopicPage/getCommentsPage/addComment/
     * getForumTree/getFavorites/addFavorite/removeFavorite/login/checkAuth/
     * logout/getMagnetLink/downloadTorrent) cover every Section G need.
     */
    internal fun getActiveClient(): TrackerClient =
        registry.get(activeTrackerId, MapPluginConfig())

    companion object {
        const val DEFAULT_TRACKER_ID: String = "rutracker"
    }
}

package lava.tracker.client

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import lava.sdk.api.MapPluginConfig
import lava.sdk.api.MirrorState
import lava.sdk.api.MirrorUnavailableException
import lava.tracker.api.TrackerCapability
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
import lava.tracker.client.persistence.LavaMirrorManagerHolder
import lava.tracker.client.persistence.MirrorConfigLoader
import lava.tracker.client.persistence.MirrorHealthRepository
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
    /**
     * Optional collaborators introduced in SP-3a Phase 4 (Tasks 4.4–4.5).
     * They are nullable so unit tests that construct the SDK with only a
     * registry (Phase 1–3 surface) keep compiling. Production wiring via
     * Hilt always supplies them.
     */
    private val mirrorManagerHolder: LavaMirrorManagerHolder? = null,
    private val mirrorHealthRepository: MirrorHealthRepository? = null,
    private val mirrorConfigLoader: MirrorConfigLoader? = null,
    private val crossTrackerFallback: CrossTrackerFallbackPolicy? = null,
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
        return runWithMirrorFallback(
            trackerId = trackerId,
            capability = TrackerCapability.SEARCH,
            op = { feature.search(request, page) },
            wrapSuccess = { SearchOutcome.Success(it, viaTracker = trackerId) },
            wrapFailure = { reason, cause ->
                SearchOutcome.Failure(
                    reason = reason,
                    triedTrackers = listOf(trackerId),
                    cause = cause,
                )
            },
            wrapFallback = { proposed ->
                SearchOutcome.CrossTrackerFallbackProposed(
                    failedTrackerId = trackerId,
                    proposedTrackerId = proposed.trackerId,
                    capability = TrackerCapability.SEARCH,
                    resumeWith = {
                        val altClient = registry.get(proposed.trackerId, MapPluginConfig())
                        val altFeature = altClient.getFeature(SearchableTracker::class)
                            ?: return@CrossTrackerFallbackProposed SearchOutcome.Failure(
                                reason = "alternate tracker '${proposed.trackerId}' does not support SEARCH",
                                triedTrackers = listOf(trackerId, proposed.trackerId),
                            )
                        try {
                            SearchOutcome.Success(altFeature.search(request, page), viaTracker = proposed.trackerId)
                        } catch (t: Throwable) {
                            SearchOutcome.Failure(
                                reason = t.message ?: "alternate search failed",
                                triedTrackers = listOf(trackerId, proposed.trackerId),
                                cause = t,
                            )
                        }
                    },
                )
            },
        )
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
        return runWithMirrorFallback(
            trackerId = trackerId,
            capability = TrackerCapability.BROWSE,
            op = { feature.browse(category, page) },
            wrapSuccess = { BrowseOutcome.Success(it, viaTracker = trackerId) },
            wrapFailure = { reason, cause ->
                BrowseOutcome.Failure(
                    reason = reason,
                    triedTrackers = listOf(trackerId),
                    cause = cause,
                )
            },
            wrapFallback = { proposed ->
                BrowseOutcome.CrossTrackerFallbackProposed(
                    failedTrackerId = trackerId,
                    proposedTrackerId = proposed.trackerId,
                    capability = TrackerCapability.BROWSE,
                    resumeWith = {
                        val altClient = registry.get(proposed.trackerId, MapPluginConfig())
                        val altFeature = altClient.getFeature(BrowsableTracker::class)
                            ?: return@CrossTrackerFallbackProposed BrowseOutcome.Failure(
                                reason = "alternate tracker '${proposed.trackerId}' does not support BROWSE",
                                triedTrackers = listOf(trackerId, proposed.trackerId),
                            )
                        try {
                            BrowseOutcome.Success(altFeature.browse(category, page), viaTracker = proposed.trackerId)
                        } catch (t: Throwable) {
                            BrowseOutcome.Failure(
                                reason = t.message ?: "alternate browse failed",
                                triedTrackers = listOf(trackerId, proposed.trackerId),
                                cause = t,
                            )
                        }
                    },
                )
            },
        )
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

    // -------------------------------------------------------------------
    // SP-3a Phase 4 (Task 4.7): cross-tracker fallback wrapper.
    // -------------------------------------------------------------------

    /**
     * Common wrapper for outcome-returning SDK methods that need:
     *  1. Mirror-fallback through [LavaMirrorManagerHolder.managerFor]
     *     (if a holder is wired).
     *  2. Cross-tracker fallback proposal via [CrossTrackerFallbackPolicy]
     *     when the active tracker exhausts every mirror.
     *
     * When neither collaborator is wired (legacy unit-test path), the op
     * runs directly and any throw becomes [wrapFailure].
     */
    private suspend inline fun <Result, Outcome> runWithMirrorFallback(
        trackerId: String,
        capability: TrackerCapability,
        crossinline op: suspend () -> Result,
        crossinline wrapSuccess: (Result) -> Outcome,
        crossinline wrapFailure: (reason: String, cause: Throwable?) -> Outcome,
        crossinline wrapFallback: (proposed: TrackerDescriptor) -> Outcome,
    ): Outcome {
        val holder = mirrorManagerHolder
        // Path A: legacy — no holder; run op directly.
        if (holder == null) {
            return try {
                wrapSuccess(op())
            } catch (t: Throwable) {
                wrapFailure(t.message ?: "operation failed", t)
            }
        }
        // Path B: holder present — execute via the per-tracker MirrorManager.
        return try {
            val manager = holder.managerFor(trackerId)
            val result = manager.executeWithFallback(trackerId) { _ -> op() }
            wrapSuccess(result)
        } catch (e: MirrorUnavailableException) {
            persistMirrorHealthSnapshot(trackerId)
            val proposal = crossTrackerFallback?.proposeFallback(trackerId, capability)
            if (proposal != null) {
                wrapFallback(proposal)
            } else {
                wrapFailure(e.message ?: "all mirrors unavailable", e)
            }
        } catch (t: Throwable) {
            wrapFailure(t.message ?: "operation failed", t)
        }
    }

    // -------------------------------------------------------------------
    // SP-3a Phase 4 (Tasks 4.4 / 4.5): mirror health surface.
    // -------------------------------------------------------------------

    /**
     * Probes every known mirror for [trackerId] and persists the result.
     * No-op when the SDK was constructed without mirror collaborators
     * (i.e. legacy unit-test path). Called by:
     *  - [MirrorHealthCheckWorker] on the periodic 15-minute schedule
     *  - [TrackerSettingsViewModel] when the user taps "Probe now"
     */
    suspend fun probeMirrorsFor(trackerId: String) {
        val holder = mirrorManagerHolder ?: return
        holder.probeAll(trackerId)
        val states = holder.observeHealth(trackerId).firstOrEmpty()
        mirrorHealthRepository?.upsertAll(trackerId, states)
    }

    /**
     * Stream of current [MirrorState] entries for [trackerId]. Returns an
     * empty Flow when the SDK was constructed without a holder. The Flow
     * is sourced from the in-memory MirrorManager so consumers see live
     * health transitions as the worker probes mirrors.
     */
    fun observeMirrorHealth(trackerId: String): Flow<List<MirrorState>> {
        val holder = mirrorManagerHolder ?: return flowOf(emptyList())
        return holder.observeHealthOrEmpty(trackerId)
    }

    /**
     * Rehydrates the in-memory MirrorManager state for every registered
     * tracker from [MirrorHealthRepository] persisted snapshots. Idempotent.
     * No-op when collaborators are missing (legacy test path).
     *
     * Call from the Application onCreate (or first ViewModel init) so
     * `observeMirrorHealth` returns the previous-session snapshot before
     * the first periodic probe runs.
     */
    suspend fun initialize() {
        val holder = mirrorManagerHolder ?: return
        val repo = mirrorHealthRepository ?: return
        val loader = mirrorConfigLoader
        for (descriptor in registry.list()) {
            val trackerId = descriptor.trackerId
            // Build the manager (warms the cache so observeHealth has an immediate
            // emission).
            val manager = holder.managerFor(trackerId)
            val knownMirrors = loader?.loadFor(trackerId) ?: descriptor.baseUrls
            val persisted = repo.loadStates(trackerId, knownMirrors)
            for (state in persisted) {
                if (state.consecutiveFailures > 0) {
                    repeat(state.consecutiveFailures) {
                        manager.reportFailure(state.mirror, RehydratedFailure)
                    }
                } else if (state.health == lava.sdk.api.HealthState.HEALTHY) {
                    manager.reportSuccess(state.mirror)
                }
            }
        }
    }

    private suspend fun Flow<List<MirrorState>>.firstOrEmpty(): List<MirrorState> {
        return try {
            first()
        } catch (_: NoSuchElementException) {
            emptyList()
        }
    }

    /**
     * Persists the current in-memory MirrorState snapshot to
     * [MirrorHealthRepository]. Public-internal so the inline
     * [runWithMirrorFallback] helper can invoke it from feature
     * call-sites without violating private visibility.
     */
    @PublishedApi
    internal suspend fun persistMirrorHealthSnapshot(trackerId: String) {
        val holder = mirrorManagerHolder ?: return
        val repo = mirrorHealthRepository ?: return
        try {
            val states = holder.observeHealthOrEmpty(trackerId).firstOrEmpty()
            repo.upsertAll(trackerId, states)
        } catch (_: Throwable) {
            // Snapshot persistence failure is not user-visible here —
            // the periodic worker will pick up the next probe cycle.
        }
    }

    private object RehydratedFailure : Throwable("rehydrated-from-persistence")

    companion object {
        const val DEFAULT_TRACKER_ID: String = "rutracker"
    }
}

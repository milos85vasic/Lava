package lava.tracker.rutracker

import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.api.TrackerFeature
import lava.tracker.api.feature.AuthenticatableTracker
import lava.tracker.api.feature.BrowsableTracker
import lava.tracker.api.feature.CommentsTracker
import lava.tracker.api.feature.DownloadableTracker
import lava.tracker.api.feature.FavoritesTracker
import lava.tracker.api.feature.SearchableTracker
import lava.tracker.api.feature.TopicTracker
import lava.tracker.rutracker.feature.RuTrackerAuth
import lava.tracker.rutracker.feature.RuTrackerBrowse
import lava.tracker.rutracker.feature.RuTrackerComments
import lava.tracker.rutracker.feature.RuTrackerDownload
import lava.tracker.rutracker.feature.RuTrackerFavorites
import lava.tracker.rutracker.feature.RuTrackerSearch
import lava.tracker.rutracker.feature.RuTrackerTopic
import javax.inject.Inject
import kotlin.reflect.KClass

/**
 * Lava-domain RuTracker client. Wraps the 7 feature impls and exposes them via
 * [TrackerClient.getFeature]. Implements Capability Honesty (clause 6.E): every
 * capability declared in [RuTrackerDescriptor.capabilities] that has a matching
 * feature interface MUST resolve to a non-null impl here.
 */
class RuTrackerClient @Inject constructor(
    private val search: RuTrackerSearch,
    private val browse: RuTrackerBrowse,
    private val topic: RuTrackerTopic,
    private val comments: RuTrackerComments,
    private val favorites: RuTrackerFavorites,
    private val auth: RuTrackerAuth,
    private val download: RuTrackerDownload,
) : TrackerClient {

    override val descriptor: TrackerDescriptor = RuTrackerDescriptor

    override suspend fun healthCheck(): Boolean = auth.checkAuthAlive()

    @Suppress("UNCHECKED_CAST")
    override fun <T : TrackerFeature> getFeature(featureClass: KClass<T>): T? {
        // Capability Honesty (clause 6.E): a feature impl is exposed only if the
        // matching capability is in descriptor.capabilities. The gate is no-op
        // today because RuTrackerDescriptor declares all 7 capabilities, but it
        // is load-bearing for future descriptor mutations (e.g. disabling
        // FAVORITES via descriptor change should make getFeature(FavoritesTracker::class)
        // return null without touching this code).
        val caps = descriptor.capabilities
        return when (featureClass) {
            SearchableTracker::class -> if (TrackerCapability.SEARCH in caps) search as T else null
            BrowsableTracker::class -> if (TrackerCapability.BROWSE in caps) browse as T else null
            TopicTracker::class -> if (TrackerCapability.TOPIC in caps) topic as T else null
            CommentsTracker::class -> if (TrackerCapability.COMMENTS in caps) comments as T else null
            FavoritesTracker::class -> if (TrackerCapability.FAVORITES in caps) favorites as T else null
            AuthenticatableTracker::class -> if (TrackerCapability.AUTH_REQUIRED in caps) auth as T else null
            DownloadableTracker::class -> if (TrackerCapability.TORRENT_DOWNLOAD in caps) download as T else null
            else -> null
        }
    }

    override fun close() {
        // No HTTP resources owned directly here; underlying RuTrackerInnerApi
        // owns the OkHttp client and is closed at module-scope tear-down.
    }
}

package lava.tracker.rutracker

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
    override fun <T : TrackerFeature> getFeature(featureClass: KClass<T>): T? = when (featureClass) {
        SearchableTracker::class -> search as T
        BrowsableTracker::class -> browse as T
        TopicTracker::class -> topic as T
        CommentsTracker::class -> comments as T
        FavoritesTracker::class -> favorites as T
        AuthenticatableTracker::class -> auth as T
        DownloadableTracker::class -> download as T
        else -> null
    }

    override fun close() {
        // No HTTP resources owned directly here; underlying RuTrackerInnerApi
        // owns the OkHttp client and is closed at module-scope tear-down.
    }
}

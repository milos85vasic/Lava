package lava.tracker.archiveorg

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
import lava.tracker.archiveorg.feature.ArchiveOrgBrowse
import lava.tracker.archiveorg.feature.ArchiveOrgDownload
import lava.tracker.archiveorg.feature.ArchiveOrgSearch
import lava.tracker.archiveorg.feature.ArchiveOrgTopic
import lava.tracker.archiveorg.http.ArchiveOrgHttpClient
import javax.inject.Inject
import kotlin.reflect.KClass

/**
 * Lava-domain Internet Archive client.
 *
 * Wraps the feature impls that match the capabilities [ArchiveOrgDescriptor]
 * declares (SEARCH, BROWSE, FORUM, TOPIC) and exposes them via
 * [TrackerClient.getFeature].
 *
 * Capability Honesty (clause 6.E): every capability declared in
 * [ArchiveOrgDescriptor.capabilities] that maps to a [TrackerFeature]
 * interface MUST resolve to a non-null impl here.
 *
 * [healthCheck] performs a fetch of the primary mirror's home page to verify
 * the provider is reachable.
 */
class ArchiveOrgClient @Inject constructor(
    private val http: ArchiveOrgHttpClient,
    private val search: ArchiveOrgSearch,
    private val browse: ArchiveOrgBrowse,
    private val topic: ArchiveOrgTopic,
    private val download: ArchiveOrgDownload,
) : TrackerClient {

    override val descriptor: TrackerDescriptor = ArchiveOrgDescriptor

    override suspend fun healthCheck(): Boolean = try {
        http.get("https://archive.org/").use { response ->
            response.isSuccessful
        }
    } catch (_: Throwable) {
        false
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : TrackerFeature> getFeature(featureClass: KClass<T>): T? {
        val caps = descriptor.capabilities
        return when (featureClass) {
            SearchableTracker::class -> if (TrackerCapability.SEARCH in caps) search as T else null
            BrowsableTracker::class -> if (TrackerCapability.BROWSE in caps) browse as T else null
            TopicTracker::class -> if (TrackerCapability.TOPIC in caps) topic as T else null
            // Internet Archive has no auth, comments, or favorites.
            AuthenticatableTracker::class -> null
            CommentsTracker::class -> null
            FavoritesTracker::class -> null
            // HTTP download is implemented but not declared as a capability
            // because TrackerCapability lacks HTTP_DOWNLOAD today.
            DownloadableTracker::class -> null
            else -> null
        }
    }

    override fun close() {
        // OkHttp client is a singleton closed at process shutdown.
    }
}

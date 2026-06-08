package lava.tracker.iptorrents

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
import lava.tracker.iptorrents.feature.IPTorrentsDownload
import lava.tracker.iptorrents.feature.IPTorrentsSearch
import javax.inject.Inject
import kotlin.reflect.KClass

/**
 * Lava-domain IPTorrents client — a thin Jackett-delegating provider
 * (`docs/design/iptorrents-support.md` option 3b).
 *
 * Wires exactly the two feature impls that match the capabilities
 * [IPTorrentsDescriptor] declares:
 *   - SEARCH                       → [IPTorrentsSearch]
 *   - TORRENT_DOWNLOAD + MAGNET_LINK → [IPTorrentsDownload] (one feature backs both)
 *
 * Capability Honesty (clause 6.E): every declared capability that maps to a
 * [TrackerFeature] interface resolves to a non-null impl here. BROWSE / TOPIC /
 * COMMENTS / FAVORITES / AUTH are NOT declared and NOT resolved — there is no
 * `/jackett/search`-equivalent route for them, so returning an impl would be a
 * declared-but-empty bluff.
 *
 * [healthCheck] runs a real search probe against the configured sidecar; if the
 * route is unreachable / unconfigured it surfaces as `false` (never throws).
 */
class IPTorrentsClient @Inject constructor(
    private val search: IPTorrentsSearch,
    private val download: IPTorrentsDownload,
) : TrackerClient {

    override val descriptor: TrackerDescriptor = IPTorrentsDescriptor

    override suspend fun healthCheck(): Boolean = try {
        // A reachable sidecar returns a (possibly empty) result set without error.
        search.search(lava.tracker.api.model.SearchRequest(query = HEALTH_PROBE_QUERY), page = 0)
        true
    } catch (_: Throwable) {
        false
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : TrackerFeature> getFeature(featureClass: KClass<T>): T? {
        val caps = descriptor.capabilities
        return when (featureClass) {
            SearchableTracker::class -> if (TrackerCapability.SEARCH in caps) search as T else null
            DownloadableTracker::class ->
                if (TrackerCapability.TORRENT_DOWNLOAD in caps || TrackerCapability.MAGNET_LINK in caps) {
                    download as T
                } else {
                    null
                }
            // §6.E: IPTorrents-via-Jackett exposes no browse/topic/comments/
            // favorites/auth route — these capabilities are NOT declared, so
            // their features intentionally do not resolve.
            BrowsableTracker::class -> null
            TopicTracker::class -> null
            CommentsTracker::class -> null
            FavoritesTracker::class -> null
            AuthenticatableTracker::class -> null
            else -> null
        }
    }

    override fun close() {
        // No HTTP resources owned directly here; IPTorrentsJackettApi is a
        // singleton and its OkHttpClient is closed at process shutdown.
    }

    private companion object {
        // A benign, non-empty probe term for the liveness check.
        const val HEALTH_PROBE_QUERY = "test"
    }
}

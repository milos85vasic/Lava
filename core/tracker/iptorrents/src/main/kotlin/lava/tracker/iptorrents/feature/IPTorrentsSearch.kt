package lava.tracker.iptorrents.feature

import lava.tracker.api.feature.SearchableTracker
import lava.tracker.api.model.SearchRequest
import lava.tracker.api.model.SearchResult
import lava.tracker.iptorrents.IPTorrentsConfig
import lava.tracker.iptorrents.IPTorrentsDescriptor
import lava.tracker.iptorrents.http.IPTorrentsJackettApi
import lava.tracker.iptorrents.model.IPTorrentsResultCache
import lava.tracker.iptorrents.model.JackettResultMapper
import javax.inject.Inject

/**
 * IPTorrents [SearchableTracker] — delegates to lava-api-go's
 * `GET /jackett/search?indexer=iptorrents&q=<query>` route, maps the JSON
 * SearchResult into the SDK domain [SearchResult], and records each row's
 * magnet + .torrent URL in [IPTorrentsResultCache] so the synchronous
 * [IPTorrentsDownload] surfaces can resolve them without a second fetch.
 *
 * Sixth Law clause 1: this is the same route the user's IPTorrents search action
 * triggers — there is no native HTML path; the route IS the surface.
 *
 * The [baseUrlOverride] constructor parameter lets the MockWebServer-backed
 * delegation test swap in a `http://localhost:<port>` origin without patching
 * any live host. Production resolves the origin via [IPTorrentsConfig].
 */
class IPTorrentsSearch @Inject constructor(
    private val api: IPTorrentsJackettApi,
    private val mapper: JackettResultMapper,
    private val cache: IPTorrentsResultCache,
) : SearchableTracker {

    /** Test-only constructor; production callers use the @Inject one. */
    internal constructor(
        api: IPTorrentsJackettApi,
        mapper: JackettResultMapper,
        cache: IPTorrentsResultCache,
        baseUrl: String,
    ) : this(api, mapper, cache) {
        this.baseUrlOverride = baseUrl
    }

    private var baseUrlOverride: String? = null

    override suspend fun search(request: SearchRequest, page: Int): SearchResult {
        val baseUrl = IPTorrentsConfig.resolve(baseUrlOverride)
            ?: error(
                "IPTorrents sidecar base URL not configured " +
                    "(set ${IPTorrentsConfig.ENV_VAR} / -D${IPTorrentsConfig.SYSTEM_PROPERTY}); §6.R forbids a hardcoded default.",
            )
        val body = api.searchJson(baseUrl, request.query)
        val result = mapper.map(IPTorrentsDescriptor.trackerId, body)
        result.items.forEach { cache.put(it.torrentId, it.magnetUri, it.downloadUrl) }
        return result
    }
}

package lava.tracker.nnmclub.feature

import lava.tracker.api.feature.SearchableTracker
import lava.tracker.api.model.SearchRequest
import lava.tracker.api.model.SearchResult
import lava.tracker.nnmclub.http.NnmclubHttpClient
import lava.tracker.nnmclub.http.NnmclubMagnetCache
import lava.tracker.nnmclub.parser.NnmclubSearchParser
import java.net.URLEncoder
import javax.inject.Inject

/**
 * NNM-Club implementation of [SearchableTracker].
 *
 * URL contract: `<baseUrl>/forum/tracker.php?nm=<query>&start=<offset>`.
 * Page size is 50 results; `start` = 50 * page (page is 0-based).
 *
 * §6.E: each result row that carries a magnet is recorded in
 * [NnmclubMagnetCache] keyed by its topic id, so the synchronous
 * [NnmclubDownload.getMagnetLink] can surface it without a further fetch.
 */
class NnmclubSearch @Inject constructor(
    private val http: NnmclubHttpClient,
    private val parser: NnmclubSearchParser,
    private val magnetCache: NnmclubMagnetCache,
) : SearchableTracker {

    internal constructor(
        http: NnmclubHttpClient,
        parser: NnmclubSearchParser,
        magnetCache: NnmclubMagnetCache,
        baseUrl: String,
    ) : this(http, parser, magnetCache) {
        this.baseUrlOverride = baseUrl
    }

    private var baseUrlOverride: String? = null
    private val baseUrl: String get() = baseUrlOverride ?: DEFAULT_BASE_URL

    override suspend fun search(request: SearchRequest, page: Int): SearchResult {
        val url = buildSearchUrl(request.query, page)
        val response = http.get(url)
        val body = response.use { http.bodyString(it) }
        val result = parser.parse(body, pageHint = page)
        result.items.forEach { magnetCache.put(it.torrentId, it.magnetUri) }
        return result
    }

    internal fun buildSearchUrl(query: String, page: Int): String {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8).replace("+", "%20")
        val start = page * 50
        return if (start > 0) {
            "$baseUrl/forum/tracker.php?nm=$encoded&start=$start"
        } else {
            "$baseUrl/forum/tracker.php?nm=$encoded"
        }
    }

    companion object {
        const val DEFAULT_BASE_URL: String = "https://nnmclub.to"
    }
}

package lava.tracker.kinozal.feature

import lava.tracker.api.feature.SearchableTracker
import lava.tracker.api.model.SearchRequest
import lava.tracker.api.model.SearchResult
import lava.tracker.kinozal.http.KinozalHttpClient
import lava.tracker.kinozal.parser.KinozalSearchParser
import java.net.URLEncoder
import javax.inject.Inject

/**
 * Kinozal implementation of [SearchableTracker].
 *
 * URL contract: `<baseUrl>/browse.php?s=<query>&page=<page>`.
 */
class KinozalSearch @Inject constructor(
    private val http: KinozalHttpClient,
    private val parser: KinozalSearchParser,
) : SearchableTracker {

    internal constructor(
        http: KinozalHttpClient,
        parser: KinozalSearchParser,
        baseUrl: String,
    ) : this(http, parser) {
        this.baseUrlOverride = baseUrl
    }

    private var baseUrlOverride: String? = null
    private val baseUrl: String get() = baseUrlOverride ?: DEFAULT_BASE_URL

    override suspend fun search(request: SearchRequest, page: Int): SearchResult {
        val url = buildSearchUrl(request.query, page)
        val response = http.get(url)
        val body = response.use { http.bodyString(it) }
        return parser.parse(body, pageHint = page)
    }

    internal fun buildSearchUrl(query: String, page: Int): String {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8)
            .replace("+", "%20")
        return "$baseUrl/browse.php?s=$encoded&page=$page"
    }

    companion object {
        const val DEFAULT_BASE_URL: String = "https://kinozal.tv"
    }
}

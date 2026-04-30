package lava.tracker.rutor.feature

import lava.tracker.api.feature.SearchableTracker
import lava.tracker.api.model.SearchRequest
import lava.tracker.api.model.SearchResult
import lava.tracker.rutor.http.RuTorHttpClient
import lava.tracker.rutor.parser.RuTorSearchParser
import java.net.URLEncoder
import javax.inject.Inject

/**
 * RuTor implementation of [SearchableTracker] (SP-3a Task 3.36, Section I).
 *
 * URL contract: `<baseUrl>/search/<page>/0/000/0/<urlEncodedQuery>`.
 *  - `<page>` is the zero-based page index (rutor's first page is 0).
 *  - The first `0` is the category id (`0` = all categories — search across the whole tracker).
 *  - `000` is the option-flags triplet rutor's UI emits unconditionally for vanilla
 *    search (download-format / period / preview disabled). Calibrated against
 *    real `search/0/0/000/0/...` URLs the rutor.info search box generates.
 *  - The trailing `0` is the sort field; `0` is "by relevance" which matches the
 *    descriptor's default and the `search-*-2026-04-30.html` fixture set.
 *  - `<urlEncodedQuery>` is the user's [SearchRequest.query], URL-encoded so
 *    Cyrillic and spaces survive the wire (rutor expects `%20` for whitespace,
 *    not `+`).
 *
 * Sixth Law clause 1: this is the same URL the user's search action triggers
 * — verified by inspecting rutor.info's own search form fixture and a manual
 * curl rehearsal that returned a results page with magnet hashes.
 *
 * The [baseUrl] constructor parameter exists so the MockWebServer-backed tests
 * (Sixth Law clause 3) can swap in a `http://localhost:<port>` URL without
 * patching the live rutor.info host. Production use takes the default.
 */
class RuTorSearch @Inject constructor(
    private val http: RuTorHttpClient,
    private val parser: RuTorSearchParser,
) : SearchableTracker {

    /** Test-only constructor; production callers use the @Inject one. */
    internal constructor(
        http: RuTorHttpClient,
        parser: RuTorSearchParser,
        baseUrl: String,
    ) : this(http, parser) {
        this.baseUrlOverride = baseUrl
    }

    private var baseUrlOverride: String? = null

    private val baseUrl: String get() = baseUrlOverride ?: DEFAULT_BASE_URL

    override suspend fun search(request: SearchRequest, page: Int): SearchResult {
        val url = buildSearchUrl(request.query, page)
        val response = http.get(url)
        val body = response.use { it.body?.string() ?: "" }
        return parser.parse(body, pageHint = page)
    }

    /** Visible for tests so they can assert on the exact URL the client constructs. */
    internal fun buildSearchUrl(query: String, page: Int): String {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8)
            // URLEncoder uses + for space; rutor expects %20 in the path segment.
            .replace("+", "%20")
        return "$baseUrl/search/$page/0/000/0/$encoded"
    }

    companion object {
        const val DEFAULT_BASE_URL: String = "https://rutor.info"
    }
}

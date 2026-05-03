package lava.tracker.archiveorg.feature

import lava.tracker.api.feature.SearchableTracker
import lava.tracker.api.model.SearchRequest
import lava.tracker.api.model.SearchResult
import lava.tracker.archiveorg.http.ArchiveOrgHttpClient
import javax.inject.Inject

/**
 * Internet Archive implementation of [SearchableTracker].
 *
 * Consumes archive.org's advancedsearch.php JSON API:
 *   GET /advancedsearch.php?q={query}&output=json&rows=50&page={page}&sort[]=downloads+desc
 *
 * Sixth Law clause 1: this is the same URL the user's search action triggers.
 */
class ArchiveOrgSearch @Inject constructor(
    private val http: ArchiveOrgHttpClient,
) : SearchableTracker {

    /** Test-only constructor; production callers use the @Inject one. */
    internal constructor(http: ArchiveOrgHttpClient, baseUrl: String) : this(http) {
        this.baseUrlOverride = baseUrl
    }

    private var baseUrlOverride: String? = null
    private val baseUrl: String get() = baseUrlOverride ?: DEFAULT_BASE_URL

    override suspend fun search(request: SearchRequest, page: Int): SearchResult {
        val url = buildSearchUrl(request.query, page)
        val response = http.get(url)
        val body = response.use { it.body?.string() ?: "" }
        val dto = http.json.decodeFromString(SearchResponseDto.serializer(), body)
        return dto.toDomain(page)
    }

    internal fun buildSearchUrl(query: String, page: Int): String {
        val p = if (page < 1) 1 else page + 1 // API is 1-based; our pager is 0-based
        return "$baseUrl/advancedsearch.php?q=${encode(query)}&output=json&rows=50&page=$p&sort[]=downloads+desc"
    }

    private fun encode(query: String): String =
        java.net.URLEncoder.encode(query, Charsets.UTF_8)

    companion object {
        const val DEFAULT_BASE_URL: String = "https://archive.org"
    }
}

package lava.tracker.gutenberg.feature

import lava.tracker.api.feature.SearchableTracker
import lava.tracker.api.model.SearchRequest
import lava.tracker.api.model.SearchResult
import lava.tracker.gutenberg.http.GutenbergHttpClient
import lava.tracker.gutenberg.model.BookList
import lava.tracker.gutenberg.model.estimateTotalPages
import lava.tracker.gutenberg.model.toTorrentItem
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject

/**
 * Project Gutenberg implementation of [SearchableTracker].
 *
 * URL contract: `https://gutendex.com/books/?search={query}&page={page}`.
 */
class GutenbergSearch @Inject constructor(
    private val http: GutenbergHttpClient,
) : SearchableTracker {

    internal constructor(http: GutenbergHttpClient, baseUrl: String) : this(http) {
        this.baseUrlOverride = baseUrl
    }

    private var baseUrlOverride: String? = null
    private val baseUrl: String get() = baseUrlOverride ?: DEFAULT_BASE_URL

    override suspend fun search(request: SearchRequest, page: Int): SearchResult {
        val url = buildSearchUrl(request.query, page)
        val response = http.get(url)
        val body = response.use { it.body?.string() ?: "" }
        val bookList = http.decodeFromString(BookList.serializer(), body)
        return SearchResult(
            items = bookList.results.map { it.toTorrentItem() },
            totalPages = estimateTotalPages(bookList.count),
            currentPage = page,
        )
    }

    internal fun buildSearchUrl(query: String, page: Int): String {
        val builder = baseUrl.toHttpUrlOrNull()?.newBuilder()
            ?: throw IllegalStateException("Invalid base URL")
        builder.addPathSegment("books")
        if (query.isNotBlank()) {
            builder.addQueryParameter("search", query)
        }
        if (page > 0) {
            builder.addQueryParameter("page", page.toString())
        }
        return builder.build().toString()
    }

    companion object {
        const val DEFAULT_BASE_URL: String = "https://gutendex.com"
    }
}

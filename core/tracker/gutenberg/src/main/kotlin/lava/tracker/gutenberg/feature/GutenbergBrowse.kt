package lava.tracker.gutenberg.feature

import lava.tracker.api.feature.BrowsableTracker
import lava.tracker.api.model.BrowseResult
import lava.tracker.api.model.ForumCategory
import lava.tracker.api.model.ForumTree
import lava.tracker.gutenberg.http.GutenbergHttpClient
import lava.tracker.gutenberg.model.BookList
import lava.tracker.gutenberg.model.estimateTotalPages
import lava.tracker.gutenberg.model.toTorrentItem
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject

/**
 * Project Gutenberg implementation of [BrowsableTracker].
 *
 * URL contract: `https://gutendex.com/books/?topic={topic}&page={page}`.
 *
 * [getForumTree] returns a static subject tree because Gutendex does not
 * expose a hierarchical category endpoint.
 */
class GutenbergBrowse @Inject constructor(
    private val http: GutenbergHttpClient,
) : BrowsableTracker {

    internal constructor(http: GutenbergHttpClient, baseUrl: String) : this(http) {
        this.baseUrlOverride = baseUrl
    }

    private var baseUrlOverride: String? = null
    private val baseUrl: String get() = baseUrlOverride ?: DEFAULT_BASE_URL

    override suspend fun browse(category: String?, page: Int): BrowseResult {
        val url = buildBrowseUrl(category, page)
        val response = http.get(url)
        val body = response.use { it.body?.string() ?: "" }
        val bookList = http.decodeFromString(BookList.serializer(), body)
        return BrowseResult(
            items = bookList.results.map { it.toTorrentItem() },
            totalPages = estimateTotalPages(bookList.count),
            currentPage = page,
            category = category?.let {
                ForumCategory(
                    id = it,
                    name = it.replaceFirstChar { c -> c.uppercase() },
                )
            },
        )
    }

    override suspend fun getForumTree(): ForumTree {
        return ForumTree(
            rootCategories = listOf(
                ForumCategory(id = "fiction", name = "Fiction"),
                ForumCategory(id = "science", name = "Science"),
                ForumCategory(id = "history", name = "History"),
                ForumCategory(id = "philosophy", name = "Philosophy"),
                ForumCategory(id = "poetry", name = "Poetry"),
                ForumCategory(id = "drama", name = "Drama"),
            ),
        )
    }

    internal fun buildBrowseUrl(topic: String?, page: Int): String {
        val builder = baseUrl.toHttpUrlOrNull()?.newBuilder()
            ?: throw IllegalStateException("Invalid base URL")
        builder.addPathSegment("books")
        if (!topic.isNullOrBlank()) {
            builder.addQueryParameter("topic", topic)
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

package lava.tracker.nnmclub.feature

import lava.tracker.api.feature.CommentsTracker
import lava.tracker.api.model.Comment
import lava.tracker.api.model.CommentsPage
import lava.tracker.nnmclub.http.NnmclubHttpClient
import org.jsoup.Jsoup
import javax.inject.Inject

/**
 * NNM-Club implementation of [CommentsTracker].
 *
 * Parses comments from the topic page (`/forum/viewtopic.php?t=<id>`).
 * Each comment row is extracted from `table.forumline` with `.postbody` and `.name`.
 */
class NnmclubComments @Inject constructor(
    private val http: NnmclubHttpClient,
) : CommentsTracker {

    internal constructor(http: NnmclubHttpClient, baseUrl: String) : this(http) {
        this.baseUrlOverride = baseUrl
    }

    private var baseUrlOverride: String? = null
    private val baseUrl: String get() = baseUrlOverride ?: DEFAULT_BASE_URL

    override suspend fun getComments(topicId: String, page: Int): CommentsPage {
        val url = "$baseUrl/forum/viewtopic.php?t=$topicId"
        val html = http.get(url).use { it.body?.string() ?: "" }
        val doc = Jsoup.parse(html)

        val items = doc.select("table.forumline tr").mapNotNull { row ->
            val author = row.selectFirst(".name, b.postauthor")?.text()?.trim()
            val body = row.selectFirst(".postbody")?.text()?.trim()
            if (author != null && body != null) {
                Comment(author = author, body = body)
            } else {
                null
            }
        }

        return CommentsPage(
            items = items,
            totalPages = 1,
            currentPage = page,
        )
    }

    override suspend fun addComment(topicId: String, message: String): Boolean {
        // Not supported by the scraping surface.
        return false
    }

    companion object {
        const val DEFAULT_BASE_URL: String = "https://nnmclub.to"
    }
}

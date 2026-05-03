package lava.tracker.kinozal.feature

import lava.tracker.api.feature.BrowsableTracker
import lava.tracker.api.model.BrowseResult
import lava.tracker.api.model.ForumTree
import lava.tracker.kinozal.http.KinozalHttpClient
import lava.tracker.kinozal.parser.KinozalSearchParser
import javax.inject.Inject

/**
 * Kinozal implementation of [BrowsableTracker].
 *
 * URL contract: `<baseUrl>/browse.php?c=<category>&page=<page>`.
 * The row shape is identical to search, so parsing is delegated to
 * [KinozalSearchParser].
 */
class KinozalBrowse @Inject constructor(
    private val http: KinozalHttpClient,
    private val parser: KinozalSearchParser,
) : BrowsableTracker {

    internal constructor(
        http: KinozalHttpClient,
        parser: KinozalSearchParser,
        baseUrl: String,
    ) : this(http, parser) {
        this.baseUrlOverride = baseUrl
    }

    private var baseUrlOverride: String? = null
    private val baseUrl: String get() = baseUrlOverride ?: DEFAULT_BASE_URL

    override suspend fun browse(category: String?, page: Int): BrowseResult {
        val cat = category?.takeIf { it.isNotBlank() } ?: "0"
        val url = "$baseUrl/browse.php?c=$cat&page=$page"
        val response = http.get(url)
        val body = response.use { http.bodyString(it) }
        val searchResult = parser.parse(body, pageHint = page)
        return BrowseResult(
            items = searchResult.items,
            totalPages = searchResult.totalPages,
            currentPage = page,
            category = null,
        )
    }

    override suspend fun getForumTree(): ForumTree? = null

    companion object {
        const val DEFAULT_BASE_URL: String = "https://kinozal.tv"
    }
}

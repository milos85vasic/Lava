package lava.tracker.nnmclub.feature

import lava.tracker.api.feature.BrowsableTracker
import lava.tracker.api.model.BrowseResult
import lava.tracker.api.model.ForumTree
import lava.tracker.nnmclub.http.NnmclubHttpClient
import lava.tracker.nnmclub.parser.NnmclubBrowseParser
import javax.inject.Inject

/**
 * NNM-Club implementation of [BrowsableTracker].
 *
 * URL contract: `<baseUrl>/forum/viewforum.php?f=<category>&start=<offset>`.
 */
class NnmclubBrowse @Inject constructor(
    private val http: NnmclubHttpClient,
    private val parser: NnmclubBrowseParser,
) : BrowsableTracker {

    internal constructor(
        http: NnmclubHttpClient,
        parser: NnmclubBrowseParser,
        baseUrl: String,
    ) : this(http, parser) {
        this.baseUrlOverride = baseUrl
    }

    private var baseUrlOverride: String? = null
    private val baseUrl: String get() = baseUrlOverride ?: DEFAULT_BASE_URL

    override suspend fun browse(category: String?, page: Int): BrowseResult {
        val url = buildBrowseUrl(category, page)
        val response = http.get(url)
        val body = response.use { it.body?.string() ?: "" }
        return parser.parse(body, pageHint = page)
    }

    override suspend fun getForumTree(): ForumTree? = null

    internal fun buildBrowseUrl(category: String?, page: Int): String {
        val cat = category?.takeIf { it.isNotBlank() } ?: "0"
        val start = page * 50
        return if (start > 0) {
            "$baseUrl/forum/viewforum.php?f=$cat&start=$start"
        } else {
            "$baseUrl/forum/viewforum.php?f=$cat"
        }
    }

    companion object {
        const val DEFAULT_BASE_URL: String = "https://nnmclub.to"
    }
}

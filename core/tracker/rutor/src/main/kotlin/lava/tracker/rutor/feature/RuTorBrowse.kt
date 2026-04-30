package lava.tracker.rutor.feature

import lava.tracker.api.feature.BrowsableTracker
import lava.tracker.api.model.BrowseResult
import lava.tracker.api.model.ForumTree
import lava.tracker.rutor.http.RuTorHttpClient
import lava.tracker.rutor.parser.RuTorBrowseParser
import javax.inject.Inject

/**
 * RuTor implementation of [BrowsableTracker] (SP-3a Task 3.37, Section I).
 *
 * URL contract: `<baseUrl>/browse/<page>/<categoryOrZero>/000/0`.
 *  - `<page>` is 0-based.
 *  - `<categoryOrZero>` — null/blank `category` argument is mapped to `0`
 *    (rutor's "all categories" sentinel). Otherwise the raw category id is
 *    placed in this slot.
 *  - `000` and `0` are the same option/sort placeholders described on
 *    [RuTorSearch].
 *
 * Capability Honesty (clause 6.E): RuTor has no nested forum tree comparable
 * to RuTracker's, hence [getForumTree] returns null. The descriptor's
 * [TrackerCapability] set deliberately omits FORUM, but this method must still
 * be implemented because [BrowsableTracker] declares it on the interface; the
 * null return is the documented "no forum tree" signal callers contract on.
 */
class RuTorBrowse @Inject constructor(
    private val http: RuTorHttpClient,
    private val parser: RuTorBrowseParser,
) : BrowsableTracker {

    internal constructor(
        http: RuTorHttpClient,
        parser: RuTorBrowseParser,
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
        return "$baseUrl/browse/$page/$cat/000/0"
    }

    companion object {
        const val DEFAULT_BASE_URL: String = "https://rutor.info"
    }
}

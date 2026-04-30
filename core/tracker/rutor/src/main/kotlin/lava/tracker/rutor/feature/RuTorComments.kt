package lava.tracker.rutor.feature

import lava.tracker.api.feature.CommentsTracker
import lava.tracker.api.model.CommentsPage
import lava.tracker.rutor.http.RuTorHttpClient
import lava.tracker.rutor.parser.RuTorCommentsParser
import javax.inject.Inject

/**
 * RuTor implementation of [CommentsTracker] (SP-3a Task 3.37, Section I).
 *
 * URL contract: `<baseUrl>/comment/<topicId>`. Manual rehearsal on
 * 2026-04-30 confirmed that rutor.info responds to anonymous GETs of this
 * path with `302 Location: /users.php` — i.e. comments are gated behind the
 * authenticated session. Pre-authorized adaptation A in the SP-3a plan
 * acknowledges this; for Phase 3 the feature is shippable in skeleton form:
 *   - getComments fetches the URL and runs the response through
 *     [RuTorCommentsParser]. When the session redirected to /users.php the
 *     parser returns an empty CommentsPage (the login form has no comment
 *     elements), which is the honest "no comments visible to this caller"
 *     signal.
 *   - addComment cannot be implemented anonymously (clause 6.E Capability
 *     Honesty: writing a comment requires a userid cookie this anonymous
 *     client never holds). It throws a documented IllegalStateException
 *     which the LavaTrackerSdk facade catches and maps to `false`. Real
 *     authenticated posting is deferred to SP-3a Phase 4.
 *
 * Once a `/comment/<id>` fixture is captured against an authenticated session
 * (Section K real-tracker integration test will need one), the parser KDoc's
 * structural notes can be hardened into a falsifiable test against this class.
 */
class RuTorComments @Inject constructor(
    private val http: RuTorHttpClient,
    private val parser: RuTorCommentsParser,
) : CommentsTracker {

    internal constructor(
        http: RuTorHttpClient,
        parser: RuTorCommentsParser,
        baseUrl: String,
    ) : this(http, parser) {
        this.baseUrlOverride = baseUrl
    }

    private var baseUrlOverride: String? = null
    private val baseUrl: String get() = baseUrlOverride ?: DEFAULT_BASE_URL

    override suspend fun getComments(topicId: String, page: Int): CommentsPage {
        val url = if (page <= 0) {
            "$baseUrl/comment/$topicId"
        } else {
            // RuTor's pagination on the comment surface is not formally documented
            // (the page is not pre-fetchable anonymously); we conservatively use
            // the canonical query-string ?page=<n> form most rutor templates accept.
            "$baseUrl/comment/$topicId?page=$page"
        }
        val response = http.get(url)
        val body = response.use { it.body?.string() ?: "" }
        return parser.parse(body, pageHint = page)
    }

    /**
     * Anonymous posting is not supported by rutor.info — see KDoc.
     * The SDK facade ([LavaTrackerSdk.addComment]) wraps this in try/catch
     * and surfaces `false` to the user; throwing here keeps the contract
     * honest under clause 6.E (no fake-success bluff).
     */
    override suspend fun addComment(topicId: String, message: String): Boolean {
        error(
            "RuTorComments.addComment requires an authenticated rutor session. " +
                "Anonymous posting is unsupported; call AuthenticatableTracker.login " +
                "first. Authenticated posting is scheduled for SP-3a Phase 4.",
        )
    }

    companion object {
        const val DEFAULT_BASE_URL: String = "https://rutor.info"
    }
}

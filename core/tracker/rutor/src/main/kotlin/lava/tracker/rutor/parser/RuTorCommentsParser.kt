package lava.tracker.rutor.parser

import lava.tracker.api.model.Comment
import lava.tracker.api.model.CommentsPage
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import javax.inject.Inject

/**
 * Parses rutor.info comment listings.
 *
 * Real-world structural notes (verified against the topic-*-2026-04-30 fixtures):
 *
 *  - Topic pages do NOT carry inline comment bodies. The topic HTML contains a
 *    "Написать комментарий" anchor pointing to `/comment/<id>` and nothing else.
 *    Comments are served separately from `/comment/<id>` and aren't part of the
 *    topic-detail surface.
 *  - This parser therefore accepts either:
 *      a) a topic page (no comments) — returns an empty CommentsPage
 *      b) a `/comment/<id>` page — extracted via the canonical author/body
 *         row pairs the rutor template renders. Selectors below are calibrated
 *         to be robust against the two known shapes (`<div id="comments">`
 *         legacy and `<table>` rows on the modern template).
 *
 * Adaptation-B in the plan: with no `/comment/<id>` fixture available in this
 * worktree (the plan specified only topic + login + search/browse fixtures),
 * the parser is defensive — it returns an empty page when no comment elements
 * can be identified. Section I's CommentsTracker implementation will need a
 * separate fetch against /comment/<id>, and a follow-up ticket should fetch a
 * real /comment/<id> page and add corresponding fixtures + tests.
 *
 * Sixth Law clause 6.E (capability honesty): if rutor.info ever ships a topic
 * page that does carry inline comments, this parser will surface them via the
 * `<div id="comments">` selector path; if not, it returns an empty page rather
 * than fabricating a non-zero comment count.
 */
class RuTorCommentsParser @Inject constructor() {

    fun parse(html: String, pageHint: Int = 0): CommentsPage {
        val doc = Jsoup.parse(html)
        // Strategy 1: legacy `<div id="comments">` containing repeating `<div class="comment">`.
        val legacyContainer = doc.selectFirst("div#comments")
        val items: List<Comment> = when {
            legacyContainer != null -> parseLegacy(legacyContainer)
            else -> parseModern(doc)
        }
        return CommentsPage(
            items = items,
            totalPages = 1,
            currentPage = pageHint,
        )
    }

    private fun parseLegacy(container: Element): List<Comment> {
        val nodes = container.select("div.comment, div[class^=comment]")
        return nodes.mapNotNull { node ->
            val author = node.selectFirst("a[href*=/user/], .author, b")?.text()?.trim()
                ?: return@mapNotNull null
            val body = node.selectFirst(".body, .text, p")?.text()?.trim() ?: node.text().trim()
            if (author.isEmpty() || body.isEmpty()) return@mapNotNull null
            Comment(author = author, body = body, timestamp = null)
        }
    }

    private fun parseModern(doc: org.jsoup.nodes.Document): List<Comment> {
        // Modern rutor `/comment/<id>` pages render comments in a table. Each comment row
        // has a `<a href="/user/<name>">` for the author and a sibling cell with body text.
        // We scan for any table row whose first cell links to /user/ and pair with the
        // adjacent cell's text. If none are found, the page has no comments.
        val rows = doc.select("tr:has(a[href*=/user/])")
        return rows.mapNotNull { row ->
            val authorAnchor = row.selectFirst("a[href*=/user/]") ?: return@mapNotNull null
            val author = authorAnchor.text().trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            // Body candidate: any sibling td not containing the author anchor.
            val bodyCell = row.select("td").firstOrNull { td ->
                td.selectFirst("a[href*=/user/]") == null && td.text().trim().isNotEmpty()
            } ?: return@mapNotNull null
            Comment(author = author, body = bodyCell.text().trim(), timestamp = null)
        }
    }
}

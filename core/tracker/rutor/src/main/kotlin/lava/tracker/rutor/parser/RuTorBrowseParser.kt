package lava.tracker.rutor.parser

import lava.tracker.api.model.BrowseResult
import lava.tracker.api.model.TorrentItem
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import javax.inject.Inject

/**
 * Parses the HTML returned by `/browse/<page>/<cat>/<user>/<sort>` on rutor.info.
 *
 * Real-world structural notes (verified against `browse-*-2026-04-30.html` fixtures):
 *
 *  - Rows live under `<div id="index">` and use the same `tr.gai` / `tr.tum`
 *    structure as search results — both row variants (4- and 5-column) coexist
 *    on a single page (verified in browse-normal). Row parsing is delegated to
 *    [RuTorSearchParser] to keep the column-content selector single-sourced.
 *  - Pagination differs from the search page. Browse uses a `<p align="center">`
 *    block of `<a href="/browse/<n>/0/0/0">` links interleaved with "..." and
 *    a `<b>` tag for the current range. Total pages = max integer in any
 *    `/browse/<n>/...` href + 1 (because hrefs reference page numbers, and the
 *    current page is unlinked).
 *  - Empty browse pages (e.g. /browse/99999/...) keep the column-header row but
 *    have no result rows. Pagination still references neighbouring pages, so the
 *    parser may legitimately compute totalPages > 1 even with zero items.
 *
 * Sixth Law clause 1: selectors were calibrated against the captured real fixtures
 * before this parser was committed.
 */
class RuTorBrowseParser @Inject constructor() {

    private val rowParser = RuTorSearchParser()

    fun parse(html: String, pageHint: Int = 0): BrowseResult {
        val doc = Jsoup.parse(html)
        // Re-use the search parser's row extraction by parsing the same document and
        // taking its items. This guarantees both feature implementations stay in lock-step
        // when the underlying row HTML drifts (a Sixth Law clause 1 contract).
        val items: List<TorrentItem> = rowParser.parse(html, pageHint).items
        val totalPages = parsePagination(doc)
        return BrowseResult(
            items = items,
            totalPages = totalPages,
            currentPage = pageHint,
            category = null,
        )
    }

    private fun parsePagination(doc: Document): Int {
        // Browse pages render pagination as `<a href="/browse/<n>/0/0/0">...<b>range</b>`.
        // Pull every page index out of those hrefs; total pages = max(href) + 1 because
        // the current page is unlinked and hrefs reference *other* pages.
        val anchors = doc.select("a[href^=/browse/]")
        if (anchors.isEmpty()) return 1
        val maxIndex = anchors
            .mapNotNull { a ->
                a.attr("href")
                    .removePrefix("/browse/")
                    .substringBefore('/')
                    .toIntOrNull()
            }
            .maxOrNull()
            ?: return 1
        // Pages are 0-indexed in rutor's URL scheme; total = max + 1.
        return maxIndex + 1
    }
}

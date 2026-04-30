package lava.tracker.rutor.parser

import lava.tracker.api.model.SearchResult
import lava.tracker.api.model.TorrentItem
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Parses the HTML returned by `/search/<page>/<cat>/<opts>/<sort>/<query>` on rutor.info.
 *
 * Real-world structural notes (verified against `search-*-2026-04-30.html` fixtures):
 *
 *  - Result rows live inside `<div id="index">` and use alternating row classes
 *    `tr.gai` / `tr.tum`. The `tr.backgr` row is the column header (skipped).
 *  - Two row variants coexist on real pages — see `search-edge-columns-2026-04-30.html`
 *    and `browse-normal-2026-04-30.html`:
 *      a) 4-column: date, title-cell (`<td colspan="2">`), size, peers
 *      b) 5-column: date, title-cell (`<td>`), comments-count (`<td>...com.gif</td>`),
 *         size, peers
 *    Both must parse to the same TorrentItem shape. The parser picks the size cell by
 *    content (any `<td>` whose text is parseable by [RuTorSizeParser]) instead of by
 *    column index, satisfying the variable-column hazard the plan calls out.
 *  - Pagination lives in `<div id="index"><b>Страницы: ...</b>`. The current page is
 *    rendered as plain text; remaining pages are `<a>` links. Total pages =
 *    max integer in the pagination block (current is included as plain digit).
 *  - Empty result pages have the column-header row but no result rows; pagination is
 *    `<b>Страницы: </b>` (no links). Default totalPages = 1 in that case.
 *  - Malformed pages (truncated mid-row, missing `</table>`) are tolerated by Jsoup's
 *    forgiving parser; this implementation never throws on broken HTML and degrades
 *    to whatever rows it could extract.
 *
 * Sixth Law clause 1: this parser traverses the actual rutor.info HTML — not a
 * synthetic shape. Selectors were calibrated against the real fixtures before code
 * was written.
 */
class RuTorSearchParser {

    /** [pageHint] is echoed back as `currentPage` when the page itself does not encode it. */
    fun parse(html: String, pageHint: Int = 0): SearchResult {
        val doc = Jsoup.parse(html)
        val items = parseRows(doc)
        val totalPages = parsePagination(doc)
        return SearchResult(
            items = items,
            totalPages = totalPages,
            currentPage = pageHint,
        )
    }

    private fun parseRows(doc: Document): List<TorrentItem> {
        // Rows live under `<div id="index">`; the news_table at the page top has its own rows
        // that must NOT be treated as torrents.
        val index = doc.selectFirst("div#index") ?: return emptyList()
        val rows = index.select("tr.gai, tr.tum")
        return rows.mapNotNull(::parseRow)
    }

    private fun parseRow(row: Element): TorrentItem? {
        // Title link: any /torrent/<id>/... anchor in the row.
        val titleAnchor = row.selectFirst("a[href^=/torrent/]") ?: return null
        val href = titleAnchor.attr("href").trim()
        // Path shape is /torrent/<id>[/<slug>] — id is the first numeric segment.
        val torrentId = href.removePrefix("/torrent/").substringBefore('/').trim()
        if (torrentId.isEmpty() || !torrentId.all { it.isDigit() }) return null
        val title = titleAnchor.text().trim()

        // Magnet link → infoHash via the standard 40-char hex pattern.
        val magnetAnchor = row.selectFirst("a[href^=magnet:]")
        val magnetUri = magnetAnchor?.attr("href")
        val infoHash = magnetUri?.let { INFO_HASH_PATTERN.find(it)?.value?.lowercase() }

        // Download link is `//d.rutor.info/download/<id>` (protocol-relative). Promote to https.
        val downloadAnchor = row.selectFirst("a.downgif[href]")
        val downloadHref = downloadAnchor?.attr("href")?.trim()
        val downloadUrl = downloadHref
            ?.takeIf { it.isNotEmpty() }
            ?.let { if (it.startsWith("//")) "https:$it" else it }

        // Date: ALWAYS the very first <td> of the row (verified across both variants).
        val dateText = row.selectFirst("td")?.text()?.replace(' ', ' ')?.trim()
        val publishDate = dateText?.let { RuTorDateParser.parse(it) }

        // Size: pick by content — first <td> whose text parses as a size string. This is
        // robust against the 4-vs-5-column hazard.
        val sizeBytes = row.select("td")
            .asSequence()
            .map { it.text().replace(' ', ' ').trim() }
            .mapNotNull { RuTorSizeParser.parse(it) }
            .firstOrNull()

        // Seeders / leechers: the `<span class="green">` and `<span class="red">` inside the row.
        val seeders = row.selectFirst("span.green")?.text()?.replace(' ', ' ')?.trim()
            ?.toIntOrNullSafe()
        val leechers = row.selectFirst("span.red")?.text()?.replace(' ', ' ')?.trim()
            ?.toIntOrNullSafe()

        return TorrentItem(
            trackerId = TRACKER_ID,
            torrentId = torrentId,
            title = title,
            sizeBytes = sizeBytes,
            seeders = seeders,
            leechers = leechers,
            infoHash = infoHash,
            magnetUri = magnetUri,
            downloadUrl = downloadUrl,
            detailUrl = href,
            publishDate = publishDate,
        )
    }

    /** Current-page hint is plain text; linked pages are `<a>`. Total pages = max(int) in block. */
    private fun parsePagination(doc: Document): Int {
        val index = doc.selectFirst("div#index") ?: return 1
        // Find the inline `<b>Страницы: ... </b>` block. Search-result pages render two of them
        // (top and bottom of the table); either one works. Empty-results pages render a single
        // bold tag with no numbers — default to 1.
        val paginationBolds = index.select("b").filter { it.text().contains("Страницы") }
        if (paginationBolds.isEmpty()) return 1
        val maxPage = paginationBolds.flatMap { bold ->
            // Both linked text and unlinked digit tokens count.
            DIGIT_RUN.findAll(bold.text()).map { it.value.toIntOrNull() ?: 0 }.toList()
        }.maxOrNull() ?: 0
        return if (maxPage <= 0) 1 else maxPage
    }

    private fun String.toIntOrNullSafe(): Int? = takeIf { it.isNotBlank() }?.toIntOrNull()

    companion object {
        const val TRACKER_ID = "rutor"
        private val INFO_HASH_PATTERN = Regex("[A-Fa-f0-9]{40}")
        private val DIGIT_RUN = Regex("""\d+""")
    }
}

package lava.tracker.nnmclub.parser

import lava.tracker.api.model.BrowseResult
import lava.tracker.api.model.TorrentItem
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import javax.inject.Inject

/**
 * Parses the HTML returned by `/forum/viewforum.php?f=...` on nnmclub.to.
 *
 * Row structure is identical to search results — both use `table.forumline`
 * with `a.genmed`, `.seedmed`, `.leechmed`, and size in the 6th column.
 */
class NnmclubBrowseParser @Inject constructor() {

    private val rowParser = NnmclubSearchParser()

    fun parse(html: String, pageHint: Int = 0): BrowseResult {
        val doc = Jsoup.parse(html)
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
        val maxStart = doc.select("a[href*=start=]").mapNotNull { a ->
            val href = a.attr("href")
            START_PARAM.find(href)?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull() ?: return 1
        return maxStart / 50 + 1
    }

    companion object {
        private val START_PARAM = Regex("""start=(\d+)""")
    }
}

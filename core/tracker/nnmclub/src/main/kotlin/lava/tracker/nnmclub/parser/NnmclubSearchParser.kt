package lava.tracker.nnmclub.parser

import lava.tracker.api.model.SearchResult
import lava.tracker.api.model.TorrentItem
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import javax.inject.Inject

/**
 * Parses the HTML returned by `/forum/tracker.php?nm=...` on nnmclub.to.
 *
 * Real-world structural notes:
 *  - Result rows live inside `table.forumline`.
 *  - Title anchor has class `genmed` and href `viewtopic.php?t=12345`.
 *  - Seeders and leechers are in `.seedmed` and `.leechmed`.
 *  - Size is in the 6th `<td>` column.
 *  - Magnet link may be present as `a[href^=magnet:]`.
 */
class NnmclubSearchParser @Inject constructor() {

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
        val rows = doc.select("table.forumline tr")
        return rows.mapNotNull(::parseRow)
    }

    private fun parseRow(row: org.jsoup.nodes.Element): TorrentItem? {
        if (row.selectFirst("th") != null) return null

        val titleAnchor = row.selectFirst("a.genmed") ?: return null
        val href = titleAnchor.attr("href").trim()
        val torrentId = extractTopicId(href) ?: return null
        val title = titleAnchor.text().trim()

        val seeders = row.selectFirst(".seedmed")?.text()?.trim()?.toIntOrNull()
        val leechers = row.selectFirst(".leechmed")?.text()?.trim()?.toIntOrNull()

        val cells = row.select("td")
        val sizeText = if (cells.size >= 6) cells[5].text().trim() else ""
        val sizeBytes = parseSize(sizeText)

        val magnetUri = row.selectFirst("a[href^=magnet:]")?.attr("href")

        return TorrentItem(
            trackerId = TRACKER_ID,
            torrentId = torrentId,
            title = title,
            sizeBytes = sizeBytes,
            seeders = seeders,
            leechers = leechers,
            magnetUri = magnetUri,
            detailUrl = href,
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
        const val TRACKER_ID = "nnmclub"
        private val START_PARAM = Regex("""start=(\d+)""")

        private fun extractTopicId(href: String): String? {
            val match = Regex("""t=(\d+)""").find(href)
            return match?.groupValues?.get(1)
        }

        private fun parseSize(text: String): Long? {
            val normalized = text.replace("\u00A0", " ").trim()
            if (normalized.isEmpty()) return null
            // Simple heuristic: extract number and unit
            val match = Regex("""([\d.,]+)\s*([KMGT]?B)""").find(normalized)
            if (match != null) {
                val num = match.groupValues[1].replace(",", ".").toDoubleOrNull() ?: return null
                return when (match.groupValues[2]) {
                    "B" -> (num).toLong()
                    "KB" -> (num * 1024).toLong()
                    "MB" -> (num * 1024 * 1024).toLong()
                    "GB" -> (num * 1024 * 1024 * 1024).toLong()
                    "TB" -> (num * 1024L * 1024L * 1024L * 1024L).toLong()
                    else -> null
                }
            }
            return null
        }
    }
}

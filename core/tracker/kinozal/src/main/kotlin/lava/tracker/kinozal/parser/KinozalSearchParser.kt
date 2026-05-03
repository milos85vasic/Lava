package lava.tracker.kinozal.parser

import lava.tracker.api.model.SearchResult
import lava.tracker.api.model.TorrentItem
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import javax.inject.Inject

/**
 * Parses Kinozal search / browse HTML pages.
 *
 * Real-world structural notes:
 *  - Result rows live inside `<table class="tumblers">`.
 *  - Title link uses class `namer`.
 *  - Size / seeders / leechers use class `sider`.
 *  - Magnet link is an `a[href^=magnet:]`.
 */
class KinozalSearchParser @Inject constructor() {

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
        val rows = doc.select("table.tumblers tr")
        return rows.mapNotNull { row ->
            if (row.select("th").isNotEmpty()) return@mapNotNull null
            val titleAnchor = row.selectFirst("a.namer") ?: return@mapNotNull null
            val title = titleAnchor.text().trim()
            val href = titleAnchor.attr("href").trim()
            val id = href.substringAfter("id=", "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null

            var size: String? = null
            var seeders: Int? = null
            var leechers: Int? = null
            row.select("span.sider").forEach { span ->
                val text = span.text().trim()
                when {
                    text.contains("GB") || text.contains("MB") || text.contains("KB") || text == "B" -> size = text
                    text.startsWith("S:") -> seeders = text.substringAfter(":").trim().toIntOrNull()
                    text.startsWith("L:") -> leechers = text.substringAfter(":").trim().toIntOrNull()
                }
            }

            val magnetUri = row.selectFirst("a[href^=magnet:]")?.attr("href")

            TorrentItem(
                trackerId = TRACKER_ID,
                torrentId = id,
                title = title,
                sizeBytes = null,
                seeders = seeders,
                leechers = leechers,
                magnetUri = magnetUri,
                detailUrl = href,
            )
        }
    }

    private fun parsePagination(doc: Document): Int {
        val maxPage = doc.select("a[href*=page=]").mapNotNull {
            val href = it.attr("href")
            val match = Regex("""page=(\d+)""").find(href)
            match?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull() ?: 0
        return maxPage + 1
    }

    companion object {
        const val TRACKER_ID = "kinozal"
    }
}

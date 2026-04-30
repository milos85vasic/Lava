package lava.tracker.rutor.parser

import lava.tracker.api.model.TopicDetail
import lava.tracker.api.model.TorrentFile
import lava.tracker.api.model.TorrentItem
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import javax.inject.Inject

/**
 * Parses `/torrent/<id>[/<slug>]` topic pages on rutor.info.
 *
 * Real-world structural notes (verified against `topic-*-2026-04-30.html`):
 *
 *  - The torrent's display title is the page-level `<h1>` immediately after the
 *    site menu (`<div id="menu">...</div><h1>...</h1>`). The page `<title>` is
 *    "rutor.info :: <title>" and is also extracted as a fallback.
 *  - The magnet URI lives inside `<div id="download">` as `<a href="magnet:...">`.
 *    Same 40-char hex regex as the search parser pulls the infoHash.
 *  - The download anchor is the `<a href="//d.rutor.info/download/<id>">` element;
 *    the parser promotes its protocol-relative URL to https.
 *  - Per-row metadata (size, category, seeders, leechers, posted-on) lives inside
 *    `<table id="details">`. Each row is `<tr><td class="header">Label</td><td>Value</td></tr>`.
 *    The size cell carries both human-readable and exact-byte counts —
 *    "4.25&nbsp;GB  (4567465984 Bytes)" — so the parser prefers the exact byte
 *    count when present and falls back to RuTorSizeParser for binary multipliers.
 *  - The description body is the long `<td>` child of the very first content row
 *    in `#details` (the row whose first `<td>` has `style="vertical-align:top;"`
 *    and is empty). The parser returns its plain text.
 *  - The file list (`<table id="files">`) is loaded by AJAX from
 *    `/descriptions/<id>.files` after the user clicks "Файлы (N)". On the topic
 *    page itself the tbody#filelist contains the placeholder text
 *    "Происходит загрузка списка файлов..." — files are NOT scrapeable from the
 *    topic page alone. The parser therefore returns an empty file list and
 *    documents this via [TopicDetail.files] = emptyList(). Adaptation-C in the
 *    sp3a plan acknowledges this; Section I's TopicTracker implementation will
 *    need a separate fetch against `/descriptions/<id>.files`.
 *
 * Sixth Law clause 1: structure was confirmed against the real fixtures before
 * any selectors were committed.
 */
class RuTorTopicParser @Inject constructor() {

    fun parse(html: String, topicIdHint: String? = null): TopicDetail {
        val doc = Jsoup.parse(html)

        val torrentId = topicIdHint?.takeIf { it.isNotBlank() }
            ?: extractTorrentId(doc)
            ?: ""

        val title = extractTitle(doc)
        val magnetAnchor = doc.selectFirst("#download a[href^=magnet:]")
            ?: doc.selectFirst("a[href^=magnet:]")
        val magnetUri = magnetAnchor?.attr("href")
        val infoHash = magnetUri?.let { INFO_HASH_PATTERN.find(it)?.value?.lowercase() }

        val downloadAnchor = doc.selectFirst("#download a[href*=/download/]")
            ?: doc.selectFirst("a[href*=/download/]")
        val downloadHref = downloadAnchor?.attr("href")?.trim()
        val downloadUrl = downloadHref
            ?.takeIf { it.isNotEmpty() }
            ?.let { if (it.startsWith("//")) "https:$it" else it }

        // Detail rows inside `<table id="details">`.
        val detailsRows = doc.select("table#details tr")
        val labelToValue = detailsRows.mapNotNull { row ->
            val header = row.selectFirst("td.header") ?: return@mapNotNull null
            val value = row.selectFirst("td:not(.header)")?.text()?.trim() ?: return@mapNotNull null
            header.text().trim() to value
        }.toMap()

        val sizeBytes = extractSizeBytes(labelToValue["Размер"])
        val seeders = labelToValue["Раздают"]?.replace(' ', ' ')?.trim()?.toIntOrNull()
        val leechers = labelToValue["Качают"]?.replace(' ', ' ')?.trim()?.toIntOrNull()
        val category = doc.selectFirst("table#details tr:has(td.header:matchesOwn(^Категория$)) td:not(.header) a")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        val description = extractDescription(doc)

        val torrent = TorrentItem(
            trackerId = TRACKER_ID,
            torrentId = torrentId,
            title = title,
            sizeBytes = sizeBytes,
            seeders = seeders,
            leechers = leechers,
            infoHash = infoHash,
            magnetUri = magnetUri,
            downloadUrl = downloadUrl,
            detailUrl = if (torrentId.isNotEmpty()) "/torrent/$torrentId" else null,
            category = category,
        )

        return TopicDetail(
            torrent = torrent,
            description = description,
            files = parseFiles(doc),
        )
    }

    private fun extractTitle(doc: Document): String {
        // Page-level <h1> is the most reliable; topic-page H1s carry the bare title.
        val h1 = doc.selectFirst("h1")?.text()?.trim()
        if (!h1.isNullOrEmpty()) return h1
        val pageTitle = doc.title().trim()
        return pageTitle.removePrefix("rutor.info :: ").trim()
    }

    private fun extractTorrentId(doc: Document): String? {
        // Look at the canonical-ish anchor inside #download or any /download/<id> reference.
        val href = doc.selectFirst("#download a[href*=/download/]")?.attr("href")
            ?: doc.selectFirst("a[href*=/download/]")?.attr("href")
            ?: return null
        // Path shape: //d.rutor.info/download/1052665 or /download/1052665.
        val tail = href.substringAfter("/download/")
        return tail.substringBefore('/').takeIf { it.isNotBlank() && it.all(Char::isDigit) }
    }

    /**
     * "4.25&nbsp;GB  (4567465984 Bytes)" — prefer the exact byte count in parens. If absent,
     * fall back to RuTorSizeParser. The parsing order matters because GB on rutor uses binary
     * multipliers, but the parenthetical is the authoritative byte count.
     */
    private fun extractSizeBytes(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val nbspNormalized = raw.replace(' ', ' ')
        val parens = PAREN_BYTES.find(nbspNormalized)
        if (parens != null) {
            return parens.groupValues[1].toLongOrNull()
        }
        return RuTorSizeParser.parse(nbspNormalized)
    }

    private fun extractDescription(doc: Document): String? {
        // The first content row in #details has an empty top-aligned td and the description
        // td as its sibling. The td may carry hidden <textarea>s (clickable expand sections);
        // their text is NOT visible until the user clicks, so the parser strips them.
        val firstRow = doc.selectFirst("table#details tr:first-of-type")
            ?: return null
        val descCell = firstRow.select("td").lastOrNull()
            ?: return null
        // Clone to avoid mutating the document; remove non-display nodes.
        val clone = descCell.clone()
        clone.select("script, style, textarea").remove()
        val text = clone.text().trim()
        return text.takeIf { it.isNotEmpty() }
    }

    private fun parseFiles(doc: Document): List<TorrentFile> {
        // The topic page itself never carries the resolved file list — see the parser KDoc.
        // We still parse defensively in case rutor changes shape and renders inline rows.
        val tbody = doc.selectFirst("table#files tbody#filelist") ?: return emptyList()
        val rows = tbody.select("tr")
        if (rows.isEmpty()) return emptyList()
        val parsed = rows.mapNotNull { row -> parseFileRow(row) }
        return parsed
    }

    private fun parseFileRow(row: Element): TorrentFile? {
        val cells = row.select("td")
        if (cells.size < 2) return null
        val name = cells[0].text().trim()
        // The placeholder row carries colspan="2" and a single "loading" cell — skip those.
        if (name.isEmpty() || cells[0].hasAttr("colspan")) return null
        val sizeText = cells[1].text().replace(' ', ' ').trim()
        val sizeBytes = RuTorSizeParser.parse(sizeText)
        return TorrentFile(name = name, sizeBytes = sizeBytes)
    }

    companion object {
        const val TRACKER_ID = "rutor"
        private val INFO_HASH_PATTERN = Regex("[A-Fa-f0-9]{40}")
        private val PAREN_BYTES = Regex("""\((\d+)\s*Bytes\)""", RegexOption.IGNORE_CASE)
    }
}

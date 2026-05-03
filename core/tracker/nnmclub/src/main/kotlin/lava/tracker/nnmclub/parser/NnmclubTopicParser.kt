package lava.tracker.nnmclub.parser

import lava.tracker.api.model.TopicDetail
import lava.tracker.api.model.TorrentItem
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import javax.inject.Inject

/**
 * Parses `/forum/viewtopic.php?t=<id>` topic pages on nnmclub.to.
 *
 * Real-world structural notes:
 *  - Title is in `.maintitle` or the page `<title>`.
 *  - Description lives in `#pagecontent .postbody`.
 *  - Magnet link is `a[href^=magnet:]`.
 *  - Torrent download is `a[href*=download.php?id=]`.
 */
class NnmclubTopicParser @Inject constructor() {

    fun parse(html: String, topicIdHint: String? = null): TopicDetail {
        val doc = Jsoup.parse(html)

        val torrentId = topicIdHint?.takeIf { it.isNotBlank() } ?: ""
        val title = extractTitle(doc)
        val description = extractDescription(doc)

        val magnetUri = doc.selectFirst("a[href^=magnet:]")?.attr("href")
        val infoHash = magnetUri?.let { INFO_HASH_PATTERN.find(it)?.value?.lowercase() }

        val downloadUrl = doc.select("a[href*=download.php]").firstOrNull()?.attr("href")

        val torrent = TorrentItem(
            trackerId = TRACKER_ID,
            torrentId = torrentId,
            title = title,
            infoHash = infoHash,
            magnetUri = magnetUri,
            downloadUrl = downloadUrl,
            detailUrl = if (torrentId.isNotEmpty()) "/forum/viewtopic.php?t=$torrentId" else null,
        )

        return TopicDetail(
            torrent = torrent,
            description = description,
            files = emptyList(),
        )
    }

    private fun extractTitle(doc: Document): String {
        val maint = doc.selectFirst(".maintitle")?.text()?.trim()
        if (!maint.isNullOrEmpty()) return maint
        val pageTitle = doc.title().trim()
        return pageTitle
    }

    private fun extractDescription(doc: Document): String? {
        val body = doc.selectFirst("#pagecontent .postbody")
        val text = body?.text()?.trim()
        return text?.takeIf { it.isNotEmpty() }
    }

    companion object {
        const val TRACKER_ID = "nnmclub"
        private val INFO_HASH_PATTERN = Regex("[A-Fa-f0-9]{40}")
    }
}

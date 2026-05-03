package lava.tracker.kinozal.parser

import lava.tracker.api.model.TopicDetail
import lava.tracker.api.model.TorrentItem
import org.jsoup.Jsoup
import javax.inject.Inject

/**
 * Parses Kinozal `/details.php?id=...` topic pages.
 *
 * Selectors:
 *  - Title: first `<h1>` or `<title>`.
 *  - Magnet: `a.magnet` or `a[href^=magnet:]`.
 *  - Description: `div.content`.
 */
class KinozalTopicParser @Inject constructor() {

    fun parse(html: String, topicIdHint: String? = null): TopicDetail {
        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.title().trim()

        val magnetUri = doc.selectFirst("a.magnet")?.attr("href")
            ?: doc.selectFirst("a[href^=magnet:]")?.attr("href")

        val description = doc.selectFirst("div.content")?.text()?.trim()

        val torrent = TorrentItem(
            trackerId = TRACKER_ID,
            torrentId = topicIdHint ?: "",
            title = title,
            magnetUri = magnetUri,
        )

        return TopicDetail(
            torrent = torrent,
            description = description,
            files = emptyList(),
        )
    }

    companion object {
        const val TRACKER_ID = "kinozal"
    }
}

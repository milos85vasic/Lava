package lava.tracker.rutor.feature

import lava.tracker.api.feature.TopicTracker
import lava.tracker.api.model.TopicDetail
import lava.tracker.api.model.TopicPage
import lava.tracker.rutor.http.RuTorHttpClient
import lava.tracker.rutor.parser.RuTorTopicParser
import javax.inject.Inject

/**
 * RuTor implementation of [TopicTracker] (SP-3a Task 3.37, Section I).
 *
 * Two-fetch dance for [getTopic]:
 *   1. GET `<baseUrl>/torrent/<id>` — primary topic page (title, magnet, size,
 *      seeders/leechers, description). Parser known-issue: file list on this
 *      page is the AJAX-loading placeholder text — no file rows survive.
 *   2. GET `<baseUrl>/descriptions/<id>.files` — HTML-fragment file rows
 *      (`<tr><td>name</td><td>size</td></tr>` × N) loaded by the rutor.info
 *      JS when the user clicks "Файлы (N)".
 * The fragment from (2) is folded into the [TopicDetail.files] list. If the
 * fragment fetch fails (404, non-2xx, IOException, parser exception) the
 * topic is returned with `files = emptyList()` per Pre-authorized adaptation B
 * — the topic-detail UI degrades gracefully rather than the entire screen
 * failing.
 *
 * Sixth Law clause 1: both URLs are exactly the URLs rutor.info itself fetches
 * when the user opens a topic page; verified by inspecting the topic-page
 * HTML's inline JS (`xhr.open('GET', '/descriptions/<id>.files', ...)`) and a
 * manual curl rehearsal that returned the same row shape committed in
 * `fixtures/rutor/files/files-1052665-2026-04-30.html`.
 *
 * RuTor does not paginate topic pages — comments live separately at
 * `/comment/<id>`. [getTopicPage] therefore always returns
 * `TopicPage(getTopic(id), totalPages = 1, currentPage = 0)` and the page
 * argument is ignored.
 */
class RuTorTopic @Inject constructor(
    private val http: RuTorHttpClient,
    private val parser: RuTorTopicParser,
) : TopicTracker {

    internal constructor(
        http: RuTorHttpClient,
        parser: RuTorTopicParser,
        baseUrl: String,
    ) : this(http, parser) {
        this.baseUrlOverride = baseUrl
    }

    private var baseUrlOverride: String? = null
    private val baseUrl: String get() = baseUrlOverride ?: DEFAULT_BASE_URL

    override suspend fun getTopic(id: String): TopicDetail {
        val topicUrl = "$baseUrl/torrent/$id"
        val topicHtml = http.get(topicUrl).use { it.body?.string() ?: "" }
        val baseDetail = parser.parse(topicHtml, topicIdHint = id)

        val files = fetchFilesOrEmpty(id)
        return if (files.isEmpty()) baseDetail else baseDetail.copy(files = files)
    }

    override suspend fun getTopicPage(id: String, page: Int): TopicPage =
        TopicPage(topic = getTopic(id), totalPages = 1, currentPage = 0)

    private suspend fun fetchFilesOrEmpty(id: String) = try {
        val url = "$baseUrl/descriptions/$id.files"
        val response = http.get(url)
        val body = response.use { resp ->
            if (!resp.isSuccessful) "" else resp.body?.string() ?: ""
        }
        if (body.isBlank() || body.contains("Not found", ignoreCase = true)) {
            emptyList()
        } else {
            // The file-fragment endpoint returns bare <tr>...</tr> rows. We wrap
            // them in a minimal table so Jsoup parses tbody#filelist matching the
            // topic-page selectors RuTorTopicParser already uses.
            val wrapped = "<table id=\"files\"><tbody id=\"filelist\">$body</tbody></table>"
            parser.parse(wrapped, topicIdHint = id).files
        }
    } catch (_: Throwable) {
        emptyList()
    }

    companion object {
        const val DEFAULT_BASE_URL: String = "https://rutor.info"
    }
}

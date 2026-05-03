package lava.tracker.gutenberg.feature

import lava.tracker.api.feature.TopicTracker
import lava.tracker.api.model.TopicDetail
import lava.tracker.api.model.TopicPage
import lava.tracker.api.model.TorrentFile
import lava.tracker.gutenberg.http.GutenbergHttpClient
import lava.tracker.gutenberg.model.Book
import lava.tracker.gutenberg.model.bestFormatLabel
import lava.tracker.gutenberg.model.pickBestDownloadUrl
import javax.inject.Inject

/**
 * Project Gutenberg implementation of [TopicTracker].
 *
 * URL contract: `https://gutendex.com/books/{id}/`.
 */
class GutenbergTopic @Inject constructor(
    private val http: GutenbergHttpClient,
) : TopicTracker {

    internal constructor(http: GutenbergHttpClient, baseUrl: String) : this(http) {
        this.baseUrlOverride = baseUrl
    }

    private var baseUrlOverride: String? = null
    private val baseUrl: String get() = baseUrlOverride ?: DEFAULT_BASE_URL

    override suspend fun getTopic(id: String): TopicDetail {
        val url = "$baseUrl/books/$id/"
        val response = http.get(url)
        val body = response.use { it.body?.string() ?: "" }
        val book = http.decodeFromString(Book.serializer(), body)
        return book.toTopicDetail()
    }

    override suspend fun getTopicPage(id: String, page: Int): TopicPage =
        TopicPage(topic = getTopic(id), totalPages = 1, currentPage = 0)

    companion object {
        const val DEFAULT_BASE_URL: String = "https://gutendex.com"
    }
}

private fun Book.toTopicDetail(): TopicDetail {
    val creator = authors.firstOrNull()?.name ?: ""
    val description = buildString {
        appendLine("Authors: $creator")
        if (subjects.isNotEmpty()) {
            appendLine("Subjects: ${subjects.joinToString(", ")}")
        }
        appendLine("Downloads: $download_count")
    }
    return TopicDetail(
        torrent = lava.tracker.api.model.TorrentItem(
            trackerId = "gutenberg",
            torrentId = id.toString(),
            title = title,
            category = subjects.firstOrNull(),
            downloadUrl = pickBestDownloadUrl(formats),
            metadata = mapOf(
                "creator" to creator,
                "format" to bestFormatLabel(formats),
                "downloads" to download_count.toString(),
            ),
        ),
        description = description,
        files = formats.map { (mime, _) ->
            TorrentFile(name = "$title — $mime", sizeBytes = null)
        },
    )
}

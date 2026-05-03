package lava.tracker.archiveorg.feature

import kotlinx.serialization.Serializable
import lava.tracker.api.feature.TopicTracker
import lava.tracker.api.model.TopicDetail
import lava.tracker.api.model.TopicPage
import lava.tracker.api.model.TorrentFile
import lava.tracker.api.model.TorrentItem
import lava.tracker.archiveorg.http.ArchiveOrgHttpClient
import javax.inject.Inject

/**
 * Internet Archive implementation of [TopicTracker].
 *
 * Consumes archive.org's metadata JSON API:
 *   GET /metadata/{identifier}
 *
 * Internet Archive items are not paginated, so [getTopicPage] always returns
 * totalPages=1 and currentPage=0.
 */
class ArchiveOrgTopic @Inject constructor(
    private val http: ArchiveOrgHttpClient,
) : TopicTracker {

    internal constructor(http: ArchiveOrgHttpClient, baseUrl: String) : this(http) {
        this.baseUrlOverride = baseUrl
    }

    private var baseUrlOverride: String? = null
    private val baseUrl: String get() = baseUrlOverride ?: DEFAULT_BASE_URL

    override suspend fun getTopic(id: String): TopicDetail {
        val url = "$baseUrl/metadata/$id"
        val response = http.get(url)
        val body = response.use { it.body?.string() ?: "" }
        val dto = http.json.decodeFromString(MetadataResponseDto.serializer(), body)
        return dto.toDomain(id)
    }

    override suspend fun getTopicPage(id: String, page: Int): TopicPage =
        TopicPage(topic = getTopic(id), totalPages = 1, currentPage = 0)

    companion object {
        const val DEFAULT_BASE_URL: String = "https://archive.org"
    }
}

@Serializable
private data class MetadataResponseDto(
    val metadata: MetadataDto,
    val files: List<FileDto> = emptyList(),
) {
    fun toDomain(identifier: String): TopicDetail {
        val torrent = TorrentItem(
            trackerId = "archiveorg",
            torrentId = identifier,
            title = metadata.title,
            metadata = buildMap {
                metadata.creator?.let { put("creator", it) }
                metadata.date?.let { put("date", it) }
                metadata.mediatype?.let { put("mediatype", it) }
            },
        )
        val fileList = files.map { file ->
            TorrentFile(
                name = file.name,
                sizeBytes = file.size?.toLongOrNull(),
            )
        }
        return TopicDetail(
            torrent = torrent,
            description = metadata.description,
            files = fileList,
        )
    }
}

@Serializable
private data class MetadataDto(
    val title: String,
    val creator: String? = null,
    val description: String? = null,
    val date: String? = null,
    val mediatype: String? = null,
)

@Serializable
private data class FileDto(
    val name: String,
    val size: String? = null,
    val format: String? = null,
)

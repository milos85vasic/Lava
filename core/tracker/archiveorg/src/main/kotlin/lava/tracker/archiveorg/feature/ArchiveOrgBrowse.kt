package lava.tracker.archiveorg.feature

import lava.tracker.api.feature.BrowsableTracker
import lava.tracker.api.model.BrowseResult
import lava.tracker.api.model.ForumCategory
import lava.tracker.api.model.ForumTree
import lava.tracker.archiveorg.http.ArchiveOrgHttpClient
import javax.inject.Inject

/**
 * Internet Archive implementation of [BrowsableTracker].
 *
 * Browse consumes the same advancedsearch.php endpoint but filters by
 * collection: `q=collection:{collectionID}`.
 *
 * [getForumTree] returns a static tree of top-level archive.org collections
 * because the Internet Archive does not expose a dynamic forum/category tree
 * comparable to RuTracker's.
 */
class ArchiveOrgBrowse @Inject constructor(
    private val http: ArchiveOrgHttpClient,
) : BrowsableTracker {

    internal constructor(http: ArchiveOrgHttpClient, baseUrl: String) : this(http) {
        this.baseUrlOverride = baseUrl
    }

    private var baseUrlOverride: String? = null
    private val baseUrl: String get() = baseUrlOverride ?: DEFAULT_BASE_URL

    override suspend fun browse(category: String?, page: Int): BrowseResult {
        val collection = category?.takeIf { it.isNotBlank() } ?: "movies"
        val url = "$baseUrl/advancedsearch.php?q=collection:${encode(collection)}&output=json&rows=50&page=${page + 1}"
        val response = http.get(url)
        val body = response.use { it.body?.string() ?: "" }
        val dto = http.json.decodeFromString(SearchResponseDto.serializer(), body)
        return dto.toBrowseResult(page)
    }

    override suspend fun getForumTree(): ForumTree = ForumTree(
        rootCategories = listOf(
            ForumCategory(id = "movies", name = "Movies"),
            ForumCategory(id = "audio", name = "Audio"),
            ForumCategory(id = "texts", name = "Texts"),
            ForumCategory(id = "software", name = "Software"),
            ForumCategory(id = "image", name = "Image"),
        ),
    )

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8)

    companion object {
        const val DEFAULT_BASE_URL: String = "https://archive.org"
    }
}

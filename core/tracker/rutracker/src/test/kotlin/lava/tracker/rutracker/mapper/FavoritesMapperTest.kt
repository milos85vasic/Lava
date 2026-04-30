package lava.tracker.rutracker.mapper

import lava.network.dto.forum.CategoryDto
import lava.network.dto.topic.AuthorDto
import lava.network.dto.topic.CommentsPageDto
import lava.network.dto.topic.TopicDto
import lava.network.dto.topic.TorrentDto
import lava.network.dto.topic.TorrentStatusDto
import lava.network.dto.user.FavoritesDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [FavoritesMapper].
 *
 * Falsifiability rehearsal (Sixth Law clause 2): the load-bearing assertion
 * is that resulting TorrentItem.trackerId == "rutracker" and torrentId
 * matches the legacy id. Replacing trackerId with anything else would fail
 * "expected:<rutracker> but was:<x>" — the bookmarks UI groups items by
 * trackerId.
 */
class FavoritesMapperTest {

    private val mapper = FavoritesMapper()

    @Test
    fun `bookmarked torrent maps with rutracker trackerId`() {
        val dto = FavoritesDto(
            topics = listOf(
                TorrentDto(
                    id = "5050",
                    title = "Bookmarked Movie",
                    author = AuthorDto(name = "u"),
                    category = CategoryDto(id = "55", name = "Movies"),
                    status = TorrentStatusDto.Approved,
                    seeds = 99,
                    leeches = 1,
                    size = "2.0 GB",
                    magnetLink = "magnet:?xt=urn:btih:f00d",
                ),
            ),
        )

        val items = mapper.toTorrentItems(dto)

        assertEquals(1, items.size)
        val item = items.single()
        assertEquals("rutracker", item.trackerId)
        assertEquals("5050", item.torrentId)
        assertEquals("Bookmarked Movie", item.title)
        assertEquals(99, item.seeders)
        assertEquals("magnet:?xt=urn:btih:f00d", item.magnetUri)
    }

    @Test
    fun `bookmarked discussion topic produces thin TorrentItem`() {
        val dto = FavoritesDto(
            topics = listOf(
                TopicDto(
                    id = "9090",
                    title = "Bookmarked discussion",
                    author = AuthorDto(name = "u"),
                    category = CategoryDto(id = "1", name = "Discussion"),
                ),
            ),
        )

        val item = mapper.toTorrentItems(dto).single()

        assertEquals("9090", item.torrentId)
        assertEquals("rutracker", item.trackerId)
        assertNull("topic-only rows have no magnet", item.magnetUri)
        assertEquals("topic", item.metadata["rutracker.kind"])
    }

    @Test
    fun `CommentsPageDto entry is silently skipped`() {
        val dto = FavoritesDto(
            topics = listOf(
                CommentsPageDto(
                    id = "drop",
                    title = "should be skipped",
                    page = 1,
                    pages = 1,
                    posts = emptyList(),
                ),
                TorrentDto(id = "kept", title = "kept"),
            ),
        )

        val items = mapper.toTorrentItems(dto)

        assertEquals(1, items.size)
        assertEquals("kept", items.single().torrentId)
    }

    @Test
    fun `empty favorites list returns empty list`() {
        val items = mapper.toTorrentItems(FavoritesDto(topics = emptyList()))
        assertEquals(0, items.size)
    }
}

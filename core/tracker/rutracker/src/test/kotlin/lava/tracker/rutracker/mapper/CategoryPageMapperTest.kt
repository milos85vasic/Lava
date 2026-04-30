package lava.tracker.rutracker.mapper

import lava.network.dto.forum.CategoryDto
import lava.network.dto.forum.CategoryPageDto
import lava.network.dto.topic.AuthorDto
import lava.network.dto.topic.CommentsPageDto
import lava.network.dto.topic.TopicDto
import lava.network.dto.topic.TorrentDto
import lava.network.dto.topic.TorrentStatusDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [CategoryPageMapper].
 *
 * Falsifiability rehearsal (Sixth Law clause 2): the primary assertion is
 * that the resulting BrowseResult.category.id matches the legacy
 * CategoryDto.id, and that mixed Torrent/Topic rows both round-trip with
 * trackerId == "rutracker". A regression that hard-codes category=null
 * would fail "expected:<33> but was:<null>" on the assertion below.
 */
class CategoryPageMapperTest {

    private val mapper = CategoryPageMapper()

    @Test
    fun `mixed torrent and topic rows map preserving order and trackerId`() {
        val dto = CategoryPageDto(
            category = CategoryDto(id = "33", name = "OS Distros"),
            page = 3,
            pages = 11,
            sections = null,
            children = null,
            topics = listOf(
                TorrentDto(
                    id = "111",
                    title = "TorrentRow",
                    author = AuthorDto(name = "u1"),
                    category = CategoryDto(id = "33", name = "OS Distros"),
                    status = TorrentStatusDto.Approved,
                    seeds = 10,
                    leeches = 2,
                    size = "1.0 GB",
                ),
                TopicDto(
                    id = "222",
                    title = "TopicRow (no torrent meta)",
                    author = AuthorDto(name = "u2"),
                    category = CategoryDto(id = "33", name = "OS Distros"),
                ),
            ),
        )

        val result = mapper.toBrowseResult(dto, currentPage = 3)

        assertEquals(2, result.items.size)
        assertEquals(11, result.totalPages)
        assertEquals(3, result.currentPage)
        assertNotNull(result.category)
        assertEquals("33", result.category!!.id)
        assertEquals("OS Distros", result.category!!.name)

        val torrentItem = result.items[0]
        assertEquals("rutracker", torrentItem.trackerId)
        assertEquals("111", torrentItem.torrentId)
        assertEquals(10, torrentItem.seeders)

        val topicItem = result.items[1]
        assertEquals("rutracker", topicItem.trackerId)
        assertEquals("222", topicItem.torrentId)
        assertNull("Topics carry no seed count", topicItem.seeders)
        assertEquals("topic", topicItem.metadata["rutracker.kind"])
    }

    @Test
    fun `null topics list yields empty items but preserves category`() {
        val dto = CategoryPageDto(
            category = CategoryDto(id = "44", name = "Empty Cat"),
            page = 1,
            pages = 1,
            sections = null,
            children = null,
            topics = null,
        )

        val result = mapper.toBrowseResult(dto, currentPage = 1)

        assertEquals(0, result.items.size)
        assertEquals("44", result.category?.id)
    }

    @Test
    fun `CommentsPageDto inside topics list is skipped`() {
        val dto = CategoryPageDto(
            category = CategoryDto(id = "55", name = "Mixed"),
            page = 1,
            pages = 1,
            topics = listOf(
                CommentsPageDto(
                    id = "999",
                    title = "should be skipped",
                    page = 1,
                    pages = 1,
                    posts = emptyList(),
                ),
                TorrentDto(id = "1", title = "kept"),
            ),
        )

        val result = mapper.toBrowseResult(dto, currentPage = 1)

        assertEquals(1, result.items.size)
        assertEquals("1", result.items.single().torrentId)
    }
}

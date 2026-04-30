package lava.tracker.rutracker.mapper

import kotlinx.datetime.Instant
import lava.network.dto.forum.CategoryDto
import lava.network.dto.search.SearchPageDto
import lava.network.dto.topic.AuthorDto
import lava.network.dto.topic.TorrentDto
import lava.network.dto.topic.TorrentStatusDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SearchPageMapper].
 *
 * Falsifiability rehearsal (Sixth Law clause 2): the primary assertion is
 * that the resulting `TorrentItem.trackerId == "rutracker"`. If the mapper
 * is broken to set `trackerId = "x"`, this test fails with
 * `expected:<rutracker> but was:<x>`. The rest of the test asserts on
 * user-visible fields the UI displays (title, seeders, leechers, magnet,
 * publish date, category name).
 */
class SearchPageMapperTest {

    private val mapper = SearchPageMapper()

    @Test
    fun `single torrent maps with rutracker trackerId and core fields`() {
        val dto = SearchPageDto(
            page = 2,
            pages = 7,
            torrents = listOf(
                TorrentDto(
                    id = "12345",
                    title = "Ubuntu 24.04 LTS",
                    author = AuthorDto(id = "777", name = "uploader"),
                    category = CategoryDto(id = "33", name = "OS Distros"),
                    tags = "[ISO]",
                    status = TorrentStatusDto.Approved,
                    date = 1_700_000_000L,
                    size = "4.7 GB",
                    seeds = 1234,
                    leeches = 56,
                    magnetLink = "magnet:?xt=urn:btih:abcdef",
                ),
            ),
        )

        val result = mapper.toSearchResult(dto, currentPage = 2)

        assertEquals(1, result.items.size)
        assertEquals(7, result.totalPages)
        assertEquals(2, result.currentPage)
        val item = result.items.single()
        assertEquals("rutracker", item.trackerId)
        assertEquals("12345", item.torrentId)
        assertEquals("Ubuntu 24.04 LTS", item.title)
        assertEquals(1234, item.seeders)
        assertEquals(56, item.leechers)
        assertEquals("magnet:?xt=urn:btih:abcdef", item.magnetUri)
        assertEquals("OS Distros", item.category)
        assertEquals(Instant.fromEpochSeconds(1_700_000_000L), item.publishDate)
        // LF-6 RESOLVED 2026-04-30: sizeBytes is now populated by parsing
        // the formatted display string ("4.7 GB") via [RuTrackerSizeParser].
        // Primary assertion on a user-visible numeric value the SDK consumer
        // reads when ranking or filtering by size.
        // 4.7 * 2^30 = 5_046_586_572.8 -> Long truncates.
        assertEquals(java.lang.Long.valueOf(5_046_586_572L), item.sizeBytes)
        assertEquals("4.7 GB", item.metadata["rutracker.size_text"])
        assertEquals("33", item.metadata["rutracker.categoryId"])
        assertEquals("OS Distros", item.metadata["rutracker.categoryName"])
        assertEquals("777", item.metadata["rutracker.authorId"])
        assertEquals("[ISO]", item.metadata["rutracker.tags"])
        assertEquals("Approved", item.metadata["rutracker.status"])
    }

    @Test
    fun `empty torrents list yields empty items but preserves pagination`() {
        val dto = SearchPageDto(page = 1, pages = 1, torrents = emptyList())
        val result = mapper.toSearchResult(dto, currentPage = 1)

        assertTrue(result.items.isEmpty())
        assertEquals(1, result.totalPages)
        assertEquals(1, result.currentPage)
    }

    @Test
    fun `null date and null optional fields produce null model fields not crashes`() {
        val dto = SearchPageDto(
            page = 1,
            pages = 1,
            torrents = listOf(
                TorrentDto(
                    id = "9",
                    title = "no metadata",
                    author = null,
                    category = null,
                    date = null,
                    size = null,
                    seeds = null,
                    leeches = null,
                    magnetLink = null,
                ),
            ),
        )

        val item = mapper.toSearchResult(dto, currentPage = 1).items.single()

        assertEquals("rutracker", item.trackerId)
        assertNull(item.publishDate)
        assertNull(item.seeders)
        assertNull(item.leechers)
        assertNull(item.magnetUri)
        assertNull(item.category)
        assertTrue(
            "metadata for null-everywhere DTO must be empty",
            item.metadata.isEmpty(),
        )
    }
}

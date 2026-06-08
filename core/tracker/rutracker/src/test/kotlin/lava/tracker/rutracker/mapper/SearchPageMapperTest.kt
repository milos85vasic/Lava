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

    @Test
    fun `comma-decimal size string from a mirror parses to bytes`() {
        // Third-party rutracker mirrors occasionally emit a comma decimal separator. The mapper
        // must still surface a non-null sizeBytes while preserving the verbatim display string.
        val dto = SearchPageDto(
            page = 1,
            pages = 1,
            torrents = listOf(TorrentDto(id = "1", title = "comma size", size = "1,5 GB")),
        )

        val item = mapper.toSearchResult(dto, currentPage = 1).items.single()

        // 1.5 * 2^30 = 1_610_612_736.
        assertEquals(java.lang.Long.valueOf(1_610_612_736L), item.sizeBytes)
        assertEquals("1,5 GB", item.metadata["rutracker.size_text"])
    }

    @Test
    fun `non-breaking space inside the size string is tolerated`() {
        // The scraper sometimes leaves U+00A0 between the number and the unit.
        val dto = SearchPageDto(
            page = 1,
            pages = 1,
            torrents = listOf(TorrentDto(id = "1", title = "nbsp size", size = "2 GB")),
        )

        val item = mapper.toSearchResult(dto, currentPage = 1).items.single()

        // 2 * 2^30 = 2_147_483_648.
        assertEquals(java.lang.Long.valueOf(2_147_483_648L), item.sizeBytes)
    }

    @Test
    fun `unparseable size yields null bytes but keeps the user-visible size text`() {
        // When the scraper stored something the parser can't read, sizeBytes must be null while
        // the original string survives in metadata as the user-facing fallback — never a crash.
        val dto = SearchPageDto(
            page = 1,
            pages = 1,
            torrents = listOf(TorrentDto(id = "1", title = "weird size", size = "несколько гигов")),
        )

        val item = mapper.toSearchResult(dto, currentPage = 1).items.single()

        assertNull("unparseable size must yield null sizeBytes", item.sizeBytes)
        assertEquals("несколько гигов", item.metadata["rutracker.size_text"])
    }

    @Test
    fun `Cyrillic title is preserved verbatim through the mapping`() {
        val dto = SearchPageDto(
            page = 1,
            pages = 1,
            torrents = listOf(
                TorrentDto(
                    id = "1",
                    title = "Война и мир (2025) [сезон 1] WEB-DL",
                    category = CategoryDto(id = "4", name = "Наши сериалы"),
                ),
            ),
        )

        val item = mapper.toSearchResult(dto, currentPage = 1).items.single()

        assertEquals("Война и мир (2025) [сезон 1] WEB-DL", item.title)
        assertEquals("Наши сериалы", item.category)
    }

    @Test
    fun `pages value of zero is threaded through unchanged for the caller to clamp`() {
        // The mapper is a pure pass-through for pagination — it must not silently invent a page
        // count. A boundary value (0) is reported as-is so the caller's clamp logic is exercised
        // on the real value rather than a mapper-side default that would mask a scraper bug.
        val dto = SearchPageDto(page = 0, pages = 0, torrents = emptyList())

        val result = mapper.toSearchResult(dto, currentPage = 0)

        assertEquals(0, result.totalPages)
        assertEquals(0, result.currentPage)
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun `mixed list maps each row independently with its own optional fields`() {
        // A realistic page mixes fully-populated rows with sparse ones; each must map on its own
        // merits without one row's missing field affecting a neighbour.
        val dto = SearchPageDto(
            page = 1,
            pages = 3,
            torrents = listOf(
                TorrentDto(
                    id = "100",
                    title = "full row",
                    size = "700 MB",
                    seeds = 9,
                    leeches = 1,
                    magnetLink = "magnet:?xt=urn:btih:aaa",
                ),
                TorrentDto(id = "200", title = "no peers", size = "1 GB"),
            ),
        )

        val items = mapper.toSearchResult(dto, currentPage = 1).items
        assertEquals(2, items.size)

        val full = items.first { it.torrentId == "100" }
        assertEquals(java.lang.Long.valueOf(700L * 1024L * 1024L), full.sizeBytes)
        assertEquals(9, full.seeders)
        assertEquals("magnet:?xt=urn:btih:aaa", full.magnetUri)

        val sparse = items.first { it.torrentId == "200" }
        assertEquals(java.lang.Long.valueOf(1_073_741_824L), sparse.sizeBytes)
        assertNull("sparse row has no seeders", sparse.seeders)
        assertNull("sparse row has no magnet", sparse.magnetUri)
    }
}

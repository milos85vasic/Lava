package lava.tracker.archiveorg.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anti-bluff unit tests for the Internet Archive DTO -> domain mapping in
 * [SearchResponseDto] / [SearchDocDto].
 *
 * These call the REAL [SearchResponseDto.toDomain], [SearchResponseDto.toBrowseResult]
 * and [SearchDocDto.toDomain] functions and assert on the exact field values a
 * real search/browse screen renders (titles, sizes, categories, computed page
 * counts, and the conditional metadata map). No mocking — the DTOs are
 * constructed directly and mapped.
 *
 * Pagination contract under test: Archive.org returns 50 docs per page, so
 * totalPages = ceil(numFound / 50), floored at 1.
 */
class ArchiveOrgDtoTest {

    private fun doc(
        identifier: String = "id-1",
        title: String = "Title 1",
        creator: String? = null,
        downloads: Int? = null,
        itemSize: Long? = null,
        mediatype: String? = null,
        year: String? = null,
    ) = SearchDocDto(
        identifier = identifier,
        title = title,
        creator = creator,
        downloads = downloads,
        itemSize = itemSize,
        mediatype = mediatype,
        year = year,
    )

    private fun response(numFound: Int, docs: List<SearchDocDto>) =
        SearchResponseDto(ResponseDto(numFound = numFound, start = 0, docs = docs))

    // --- SearchDocDto.toDomain ----------------------------------------------

    @Test
    fun `doc maps all populated fields onto the torrent item`() {
        val item = doc(
            identifier = "the-great-gatsby",
            title = "The Great Gatsby",
            creator = "F. Scott Fitzgerald",
            downloads = 12345,
            itemSize = 9_876_543L,
            mediatype = "texts",
            year = "1925",
        ).toDomain()

        assertEquals("archiveorg", item.trackerId)
        assertEquals("the-great-gatsby", item.torrentId)
        assertEquals("The Great Gatsby", item.title)
        assertEquals(9_876_543L, item.sizeBytes)
        assertEquals("texts", item.category)
        assertEquals("F. Scott Fitzgerald", item.metadata["creator"])
        assertEquals("12345", item.metadata["downloads"])
        assertEquals("1925", item.metadata["year"])
    }

    @Test
    fun `doc omits absent optional fields from metadata and leaves nullable columns null`() {
        val item = doc(
            identifier = "bare",
            title = "Bare Item",
            creator = null,
            downloads = null,
            itemSize = null,
            mediatype = null,
            year = null,
        ).toDomain()

        assertNull(item.sizeBytes)
        assertNull(item.category)
        assertTrue(item.metadata.isEmpty())
        assertFalse(item.metadata.containsKey("creator"))
        assertFalse(item.metadata.containsKey("downloads"))
        assertFalse(item.metadata.containsKey("year"))
    }

    @Test
    fun `doc includes only the present optional fields in metadata`() {
        val item = doc(
            identifier = "partial",
            title = "Partial",
            creator = "Someone",
            downloads = null,
            year = "2001",
        ).toDomain()

        assertEquals(setOf("creator", "year"), item.metadata.keys)
        assertEquals("Someone", item.metadata["creator"])
        assertEquals("2001", item.metadata["year"])
    }

    // --- SearchResponseDto.toDomain (search) ---------------------------------

    @Test
    fun `toDomain maps docs and carries through the requested page`() {
        val result = response(
            numFound = 1,
            docs = listOf(doc(identifier = "a", title = "A"), doc(identifier = "b", title = "B")),
        ).toDomain(page = 3)

        assertEquals(2, result.items.size)
        assertEquals("a", result.items[0].torrentId)
        assertEquals("b", result.items[1].torrentId)
        assertEquals(3, result.currentPage)
    }

    @Test
    fun `toDomain computes total pages by ceiling division of numFound by 50`() {
        assertEquals(1, response(0, emptyList()).toDomain(1).totalPages)
        assertEquals(1, response(1, listOf(doc())).toDomain(1).totalPages)
        assertEquals(1, response(50, listOf(doc())).toDomain(1).totalPages)
        assertEquals(2, response(51, listOf(doc())).toDomain(1).totalPages)
        assertEquals(2, response(100, listOf(doc())).toDomain(1).totalPages)
        assertEquals(3, response(101, listOf(doc())).toDomain(1).totalPages)
        // 1234 / 50 = 24.68 -> 25
        assertEquals(25, response(1234, listOf(doc())).toDomain(1).totalPages)
    }

    // --- SearchResponseDto.toBrowseResult (browse) ---------------------------

    @Test
    fun `toBrowseResult maps docs and carries through the requested page`() {
        val result = response(
            numFound = 75,
            docs = listOf(doc(identifier = "x", mediatype = "movies", itemSize = 42L)),
        ).toBrowseResult(page = 2)

        assertEquals(1, result.items.size)
        assertEquals("x", result.items[0].torrentId)
        assertEquals("movies", result.items[0].category)
        assertEquals(42L, result.items[0].sizeBytes)
        assertEquals(2, result.currentPage)
        // 75 / 50 = 1.5 -> 2 pages
        assertEquals(2, result.totalPages)
        // browse-specific category defaults to null when not set by mapper
        assertNull(result.category)
    }
}

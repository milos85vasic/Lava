package lava.tracker.rutracker.mapper

import lava.network.dto.forum.CategoryDto
import lava.network.dto.forum.CategoryPageDto
import lava.network.dto.search.SearchPageDto
import lava.network.dto.topic.AuthorDto
import lava.network.dto.topic.TopicDto
import lava.network.dto.topic.TorrentDto
import lava.network.dto.topic.TorrentStatusDto
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Round-trip tests for [RuTrackerDtoMappers].
 *
 * Anti-Bluff Pact contract: each test exercises the SAME forward mapper
 * the production SwitchingNetworkApi will use, then the reverse mapper
 * we are introducing here, then the forward mapper a second time. The
 * primary assertion is on data equality of the forward result the
 * second time around vs the first time around — i.e.
 *
 *     forward(reverse(forward(dto))) == forward(dto)
 *
 * which is the strongest invariant achievable given the documented
 * forward-only information loss (formatted size strings, post AST,
 * UserDto.id/avatarUrl). A test that asserts only `assertNotNull(round)`
 * would be a bluff under Sixth Law clause 3 — the assertion below is on
 * the entire reconstructed model object, so a missing field surfaces as
 * a clear field-by-field mismatch.
 *
 * Falsifiability rehearsal: deleting `metadata["rutracker.size_text"]`
 * recovery from `toTorrentDto` would make the round-trip TorrentItem
 * lose its size_text metadata key, failing the assertEquals on the
 * second-forward TorrentItem with a clear `expected:<...> but was:<...>`.
 */
class RuTrackerDtoMappersTest {

    private val mappers = RuTrackerDtoMappers()
    private val forward = SearchPageMapper()
    private val browseForward = CategoryPageMapper()

    @Test
    fun `searchResultToDto round-trips a populated SearchPageDto`() {
        val originalDto = SearchPageDto(
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

        val firstForward = forward.toSearchResult(originalDto, currentPage = 2)
        val reversedDto = mappers.searchResultToDto(firstForward)
        val secondForward = forward.toSearchResult(reversedDto, currentPage = 2)

        assertEquals(
            "forward(reverse(forward(dto))) must equal forward(dto)",
            firstForward,
            secondForward,
        )
    }

    @Test
    fun `searchResultToDto round-trips an empty page boundary`() {
        val originalDto = SearchPageDto(page = 1, pages = 1, torrents = emptyList())

        val firstForward = forward.toSearchResult(originalDto, currentPage = 1)
        val reversedDto = mappers.searchResultToDto(firstForward)
        val secondForward = forward.toSearchResult(reversedDto, currentPage = 1)

        assertEquals(firstForward, secondForward)
        assertEquals(0, reversedDto.torrents.size)
        assertEquals(1, reversedDto.pages)
        assertEquals(1, reversedDto.page)
    }

    @Test
    fun `browseResultToDto round-trips mixed Torrent and Topic rows`() {
        val originalDto = CategoryPageDto(
            category = CategoryDto(id = "33", name = "OS Distros"),
            page = 3,
            pages = 11,
            sections = null,
            children = null,
            topics = listOf(
                TorrentDto(
                    id = "111",
                    title = "TorrentRow",
                    author = AuthorDto(id = "u1id", name = "u1"),
                    category = CategoryDto(id = "33", name = "OS Distros"),
                    status = TorrentStatusDto.Approved,
                    seeds = 10,
                    leeches = 2,
                    size = "1.0 GB",
                ),
                TopicDto(
                    id = "222",
                    title = "TopicRow (no torrent meta)",
                    author = AuthorDto(id = "u2id", name = "u2"),
                    category = CategoryDto(id = "33", name = "OS Distros"),
                ),
            ),
        )

        val firstForward = browseForward.toBrowseResult(originalDto, currentPage = 3)
        val reversedDto = mappers.browseResultToDto(firstForward)
        val secondForward = browseForward.toBrowseResult(reversedDto, currentPage = 3)

        assertEquals(
            "forward(reverse(forward(dto))) must equal forward(dto) for browse pages",
            firstForward,
            secondForward,
        )
        assertEquals(2, reversedDto.topics?.size)
        // The TopicDto branch must be preserved across reverse — the forward
        // mapper relies on `metadata["rutracker.kind"] == "topic"` to choose
        // the thin TopicDto path; if the reverse mapper produced a TorrentDto
        // for a Topic row, the second-forward call would fill seeders=0 and
        // the equality check above would fail.
        assertEquals(true, reversedDto.topics?.get(1) is TopicDto)
    }

    @Test
    fun `browseResultToDto round-trips an empty topics list with null id collapse`() {
        // Boundary: legacy CategoryDto.id is nullable. Forward collapses to
        // "" and reverse must restore null. If the reverse step left "",
        // re-forward would yield ForumCategory.id = "" but the forward step
        // also collapses null -> "" so equality holds either way at the
        // ForumCategory level — but the reversed CategoryDto.id should still
        // be null to match the original DTO contract.
        val originalDto = CategoryPageDto(
            category = CategoryDto(id = null, name = "Root"),
            page = 1,
            pages = 1,
            sections = null,
            children = null,
            topics = emptyList(),
        )

        val firstForward = browseForward.toBrowseResult(originalDto, currentPage = 1)
        val reversedDto = mappers.browseResultToDto(firstForward)
        val secondForward = browseForward.toBrowseResult(reversedDto, currentPage = 1)

        assertEquals(firstForward, secondForward)
        // Anti-Bluff Pact (third law): null-id collapse must round-trip
        // exactly per Section D's empty-string-as-null contract.
        assertEquals(null, reversedDto.category.id)
        assertEquals("Root", reversedDto.category.name)
    }
}

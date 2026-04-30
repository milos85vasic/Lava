package lava.tracker.rutracker.mapper

import lava.network.dto.forum.CategoryDto
import lava.network.dto.search.SearchPageDto
import lava.network.dto.topic.AuthorDto
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
}

package lava.tracker.rutracker.mapper

import lava.network.dto.forum.CategoryDto
import lava.network.dto.topic.AuthorDto
import lava.network.dto.topic.CommentsPageDto
import lava.network.dto.topic.PostDto
import lava.network.dto.topic.Text
import lava.network.dto.topic.TopicDto
import lava.network.dto.topic.TopicPageCommentsDto
import lava.network.dto.topic.TopicPageDto
import lava.network.dto.topic.TorrentDataDto
import lava.network.dto.topic.TorrentDescriptionDto
import lava.network.dto.topic.TorrentDto
import lava.network.dto.topic.TorrentStatusDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TopicMapper].
 *
 * Falsifiability rehearsal (Sixth Law clause 2): the load-bearing assertion
 * is that toTopicDetail produces a TorrentItem whose magnetUri == the legacy
 * TorrentDto.magnetLink. Replacing the magnet pass-through with `null` would
 * fail "expected:<magnet:...> but was:<null>" — magnets are user-visible
 * (the Open in Torrent Client button copies them).
 */
class TopicMapperTest {

    private val mapper = TopicMapper()

    @Test
    fun `Torrent branch maps magnet seeders description`() {
        val dto = TorrentDto(
            id = "9000",
            title = "Some.Movie.2024.1080p.BluRay",
            author = AuthorDto(id = "u1", name = "uploader"),
            category = CategoryDto(id = "44", name = "Movies"),
            tags = "[Movies]",
            status = TorrentStatusDto.Approved,
            date = 1_700_000_000L,
            size = "8.0 GB",
            seeds = 555,
            leeches = 12,
            magnetLink = "magnet:?xt=urn:btih:abcd",
            description = TorrentDescriptionDto(
                children = listOf(Text("Plot summary line 1"), Text(" continued")),
            ),
        )

        val detail = mapper.toTopicDetail(dto)

        assertEquals("rutracker", detail.torrent.trackerId)
        assertEquals("9000", detail.torrent.torrentId)
        assertEquals("magnet:?xt=urn:btih:abcd", detail.torrent.magnetUri)
        assertEquals(555, detail.torrent.seeders)
        assertEquals(12, detail.torrent.leechers)
        assertEquals("Movies", detail.torrent.category)
        // LF-6 RESOLVED 2026-04-30: sizeBytes parsed from "8.0 GB" string.
        // 8 * 2^30 = 8_589_934_592.
        assertEquals(java.lang.Long.valueOf(8_589_934_592L), detail.torrent.sizeBytes)
        // Description flattens to plain text (rich AST is lossy on round-trip).
        assertNotNull(detail.description)
        assertTrue(
            "description should contain the plot summary text",
            detail.description!!.contains("Plot summary line 1"),
        )
        assertTrue(
            "description should contain the continuation text",
            detail.description!!.contains("continued"),
        )
        assertEquals(emptyList<Any>(), detail.files)
    }

    @Test
    fun `Topic-only branch maps a thin TorrentItem`() {
        val dto = TopicDto(
            id = "4242",
            title = "Discussion topic, no torrent attached",
            author = AuthorDto(name = "u9"),
            category = CategoryDto(id = "1", name = "Discussion"),
        )

        val detail = mapper.toTopicDetail(dto)

        assertEquals("rutracker", detail.torrent.trackerId)
        assertEquals("4242", detail.torrent.torrentId)
        assertNull(detail.torrent.magnetUri)
        assertNull(detail.torrent.seeders)
        assertNull(detail.description)
    }

    @Test
    fun `CommentsPage branch produces TorrentItem with kind=comments`() {
        val dto = CommentsPageDto(
            id = "111",
            title = "Topic with comments only",
            page = 1,
            pages = 3,
            posts = emptyList(),
        )

        val detail = mapper.toTopicDetail(dto)

        assertEquals("111", detail.torrent.torrentId)
        assertEquals("comments", detail.torrent.metadata["rutracker.kind"])
    }

    @Test
    fun `toTopicPage threads currentPage and totalPages from comments pagination`() {
        val dto = TopicPageDto(
            id = "12",
            title = "Topic Page",
            author = AuthorDto(name = "u1"),
            category = CategoryDto(id = "33", name = "OS"),
            torrentData = TorrentDataDto(
                tags = "[ISO]",
                status = TorrentStatusDto.Approved,
                date = "2024-04-01T12:00:00Z",
                size = "4.5 GB",
                seeds = 100,
                leeches = 5,
                magnetLink = "magnet:?xt=urn:btih:beef",
            ),
            commentsPage = TopicPageCommentsDto(
                page = 4,
                pages = 9,
                posts = listOf(
                    PostDto(
                        id = "p1",
                        author = AuthorDto(name = "alice"),
                        date = "2024-04-01T12:00:00Z",
                        children = listOf(Text("first post")),
                    ),
                ),
            ),
        )

        val page = mapper.toTopicPage(dto, currentPage = 4)

        assertEquals(9, page.totalPages)
        assertEquals(4, page.currentPage)
        assertEquals("12", page.topic.torrent.torrentId)
        assertEquals("magnet:?xt=urn:btih:beef", page.topic.torrent.magnetUri)
        assertEquals(100, page.topic.torrent.seeders)
        // ISO-8601 string parses cleanly to Instant.
        assertNotNull(page.topic.torrent.publishDate)
        assertEquals("4.5 GB", page.topic.torrent.metadata["rutracker.size_text"])
        // LF-6 RESOLVED 2026-04-30: sizeBytes parsed from "4.5 GB".
        // 4.5 * 2^30 = 4_831_838_208.
        assertEquals(java.lang.Long.valueOf(4_831_838_208L), page.topic.torrent.sizeBytes)
    }

    @Test
    fun `toTopicPage tolerates malformed date string by yielding null Instant`() {
        val dto = TopicPageDto(
            id = "13",
            title = "Topic with bad date",
            author = null,
            category = null,
            torrentData = TorrentDataDto(date = "not-a-date"),
            commentsPage = TopicPageCommentsDto(page = 1, pages = 1, posts = emptyList()),
        )

        val page = mapper.toTopicPage(dto, currentPage = 1)

        assertNull(
            "malformed date string should yield null publishDate, not crash",
            page.topic.torrent.publishDate,
        )
        assertEquals(
            "the original date string is preserved in metadata",
            "not-a-date",
            page.topic.torrent.metadata["rutracker.date_text"],
        )
    }
}

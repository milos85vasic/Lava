package lava.tracker.rutracker.mapper

import kotlinx.datetime.Instant
import lava.network.dto.topic.AuthorDto
import lava.network.dto.topic.CommentsPageDto
import lava.network.dto.topic.PostDto
import lava.network.dto.topic.Quote
import lava.network.dto.topic.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CommentsMapper].
 *
 * Falsifiability rehearsal (Sixth Law clause 2): the load-bearing assertion
 * is that the resulting Comment.body contains the post's text content.
 * Replacing the body flatten with `""` would fail
 * "expected: contains 'first post text', got: ''" — this is what users see
 * in the comment list UI.
 */
class CommentsMapperTest {

    private val mapper = CommentsMapper()

    @Test
    fun `normal post maps author timestamp and body`() {
        val dto = CommentsPageDto(
            id = "777",
            title = "Discussion",
            page = 2,
            pages = 5,
            posts = listOf(
                PostDto(
                    id = "p1",
                    author = AuthorDto(id = "u1", name = "alice"),
                    date = "2024-01-15T10:30:00Z",
                    children = listOf(Text("first post text")),
                ),
            ),
        )

        val page = mapper.toCommentsPage(dto, currentPage = 2)

        assertEquals(5, page.totalPages)
        assertEquals(2, page.currentPage)
        assertEquals(1, page.items.size)
        val comment = page.items.single()
        assertEquals("alice", comment.author)
        assertEquals(Instant.parse("2024-01-15T10:30:00Z"), comment.timestamp)
        assertTrue(
            "body must contain the post text content",
            comment.body.contains("first post text"),
        )
    }

    @Test
    fun `embedded quote flattens with leading angle bracket`() {
        val dto = CommentsPageDto(
            id = "777",
            title = "T",
            page = 1,
            pages = 1,
            posts = listOf(
                PostDto(
                    id = "p2",
                    author = AuthorDto(name = "bob"),
                    date = "2024-02-01T00:00:00Z",
                    children = listOf(
                        Quote(
                            title = "alice",
                            id = "q1",
                            children = listOf(Text("quoted line")),
                        ),
                        Text("my reply"),
                    ),
                ),
            ),
        )

        val comment = mapper.toCommentsPage(dto, currentPage = 1).items.single()

        assertTrue(
            "body should mark up the quoted text with leading >",
            comment.body.contains(">"),
        )
        assertTrue(comment.body.contains("quoted line"))
        assertTrue(comment.body.contains("my reply"))
    }

    @Test
    fun `empty post produces empty body without crash`() {
        val dto = CommentsPageDto(
            id = "777",
            title = "T",
            page = 1,
            pages = 1,
            posts = listOf(
                PostDto(
                    id = "p3",
                    author = AuthorDto(name = "carol"),
                    date = "2024-03-01T00:00:00Z",
                    children = emptyList(),
                ),
            ),
        )

        val comment = mapper.toCommentsPage(dto, currentPage = 1).items.single()

        assertEquals("carol", comment.author)
        assertEquals("", comment.body)
    }

    @Test
    fun `malformed date string yields null timestamp not crash`() {
        val dto = CommentsPageDto(
            id = "777",
            title = "T",
            page = 1,
            pages = 1,
            posts = listOf(
                PostDto(
                    id = "p4",
                    author = AuthorDto(name = "dan"),
                    date = "yesterday",
                    children = listOf(Text("body content")),
                ),
            ),
        )

        val comment = mapper.toCommentsPage(dto, currentPage = 1).items.single()

        assertNull(
            "malformed date should yield null timestamp, not propagate exception",
            comment.timestamp,
        )
        assertEquals("dan", comment.author)
    }
}

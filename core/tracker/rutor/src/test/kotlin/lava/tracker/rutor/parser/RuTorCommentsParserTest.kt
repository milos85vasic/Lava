package lava.tracker.rutor.parser

import lava.tracker.testing.LavaFixtureLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [RuTorCommentsParser] against the topic fixtures (which are the only
 * comment-bearing surface available in this worktree).
 *
 * Sixth Law clause 6.E (capability honesty): the parser must report 0 comments
 * for a topic page that legitimately has none, rather than fabricating items.
 * A future fixture pulled from `/comment/<id>` will exercise non-empty cases.
 */
class RuTorCommentsParserTest {

    private val loader = LavaFixtureLoader(tracker = "rutor")
    private val parser = RuTorCommentsParser()

    @Test
    fun `topic page without inline comments returns an empty CommentsPage`() {
        val html = loader.load("topic", "topic-normal-2026-04-30.html")
        val page = parser.parse(html)

        // Topic pages on rutor never embed comment bodies — comments are served
        // separately from /comment/<id>. The parser must NOT hallucinate any.
        assertTrue(
            "topic page must yield zero comments — got ${page.items.size}",
            page.items.isEmpty(),
        )
        assertEquals(1, page.totalPages)
        assertEquals(0, page.currentPage)
    }

    @Test
    fun `pageHint round-trips into CommentsPage_currentPage`() {
        val html = loader.load("topic", "topic-with-files-2026-04-30.html")
        val page = parser.parse(html, pageHint = 7)
        assertEquals(7, page.currentPage)
    }

    @Test
    fun `topic-with-long-description page also yields zero comments`() {
        val html = loader.load("topic", "topic-with-long-description-2026-04-30.html")
        val page = parser.parse(html)
        assertTrue("long-description topic must yield zero comments", page.items.isEmpty())
    }
}

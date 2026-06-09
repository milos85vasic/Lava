package lava.data.converters

import lava.models.topic.ColorValue
import lava.models.topic.Content
import lava.models.topic.ContentColumn
import lava.models.topic.ContentRow
import lava.models.topic.Post
import lava.models.topic.PostContent
import lava.models.topic.TextAlignment
import lava.models.topic.TextContent
import lava.network.dto.topic.Align
import lava.network.dto.topic.AuthorDto
import lava.network.dto.topic.Bold
import lava.network.dto.topic.Box
import lava.network.dto.topic.Br
import lava.network.dto.topic.Color
import lava.network.dto.topic.Hr
import lava.network.dto.topic.Image
import lava.network.dto.topic.Italic
import lava.network.dto.topic.Link
import lava.network.dto.topic.PostBr
import lava.network.dto.topic.PostDto
import lava.network.dto.topic.PostElementDto
import lava.network.dto.topic.Quote
import lava.network.dto.topic.Size
import lava.network.dto.topic.Text
import lava.network.dto.topic.TopicPageCommentsDto
import lava.network.dto.topic.TopicPageDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import lava.network.dto.topic.Color as ColorDto
import lava.network.dto.topic.ColorValue as ColorValueDto
import lava.network.dto.topic.TextAlignment as TextAlignmentDto

/**
 * Behavioral unit test for the package-internal post-content converter in
 * `lava.data.converters` (PostConverters.kt). The converter turns the wire
 * `List<PostElementDto>` of a forum post into the rendered `Content` tree the user
 * actually SEES on the topic screen — so a bug here renders posts wrong (bold text
 * that isn't bold, font sizes outside the readable range, missing spacers between
 * paragraphs, the wrong text colour, mis-aligned blocks).
 *
 * Every test drives the REAL production path a user's "open topic" action triggers:
 *   TopicPageDto.toCommentsPage() -> List<PostDto>.toPosts() -> toContent().
 * Only the DTO inputs are synthetic; no SUT mocking. The chief assertion in each
 * test is on the structure/values of the resulting Content tree (user-visible).
 *
 * FALSIFIABILITY REHEARSAL (see commit body for the full Bluff-Audit block) — e.g.
 * `Size(size = size.coerceIn(14, 20), ...)` -> `Size(size = size, ...)` makes the
 * size-clamp test FAIL because a wire size of 40 renders unclamped at 40 instead of 20.
 */
class PostConverterTest {

    private fun content(vararg elements: PostElementDto): Content {
        val dto = TopicPageDto(
            id = "t1",
            title = "Title",
            author = null,
            category = null,
            torrentData = null,
            commentsPage = TopicPageCommentsDto(
                page = 1,
                pages = 1,
                posts = listOf(
                    PostDto(
                        id = "p1",
                        author = AuthorDto(id = "u1", name = "alice"),
                        date = "today",
                        children = elements.toList(),
                    ),
                ),
            ),
        )
        return dto.toCommentsPage().items.single().content
    }

    private fun post(vararg elements: PostElementDto): Post {
        val dto = TopicPageDto(
            id = "t1",
            title = "Title",
            author = null,
            category = null,
            torrentData = null,
            commentsPage = TopicPageCommentsDto(
                page = 2,
                pages = 5,
                posts = listOf(
                    PostDto(
                        id = "p1",
                        author = AuthorDto(id = "u1", name = "alice"),
                        date = "2026-06-09",
                        children = elements.toList(),
                    ),
                ),
            ),
        )
        return dto.toCommentsPage().items.single()
    }

    @Test
    fun `post header fields are carried through to the rendered post`() {
        val rendered = post(Text("hi"))

        // Author / id / date are what the user sees in the post header.
        assertEquals("p1", rendered.id)
        assertEquals("alice", rendered.author.name)
        assertEquals("u1", rendered.author.id)
        assertEquals("2026-06-09", rendered.date)
    }

    @Test
    fun `comments page preserves pagination so the user can page through replies`() {
        val dto = TopicPageDto(
            id = "t1",
            title = "Title",
            author = null,
            category = null,
            torrentData = null,
            commentsPage = TopicPageCommentsDto(
                page = 3,
                pages = 7,
                posts = emptyList(),
            ),
        )
        val page = dto.toCommentsPage()
        assertEquals(3, page.page)
        assertEquals(7, page.pages)
        assertTrue(page.items.isEmpty())
    }

    @Test
    fun `single plain text element renders as a single Text leaf`() {
        // column collapses to the single row's single content -> the Text leaf itself.
        val c = content(Text("hello world"))
        assertEquals(TextContent.Text("hello world"), c)
    }

    @Test
    fun `bold wrapping only text is classified as text-bold not block-bold`() {
        // Bold over a pure-text child must be the lightweight TextContent.Bold so it
        // inlines with surrounding text, NOT the heavier PostContent.Bold block.
        val c = content(Bold(listOf(Text("strong"))))
        assertEquals(TextContent.Bold(TextContent.Text("strong")), c)
    }

    @Test
    fun `bold wrapping a non-text element becomes a block-level bold`() {
        // Bold over an Image (not text) must become PostContent.Bold so it lays out
        // as a block, not inline text.
        val c = content(Bold(listOf(Image("http://x/img.png"))))
        assertTrue("expected block bold but was $c", c is PostContent.Bold)
        val inner = (c as PostContent.Bold).content
        assertEquals(PostContent.Image("http://x/img.png"), inner)
    }

    @Test
    fun `font size larger than 20 is clamped to the readable maximum`() {
        // The converter clamps wire sizes into 14..20 so a malicious/oversized post
        // can't render giant unreadable text.
        val c = content(Size(size = 40, children = listOf(Text("big"))))
        assertTrue(c is PostContent.Size)
        assertEquals(20, (c as PostContent.Size).size)
    }

    @Test
    fun `font size smaller than 14 is clamped up to the readable minimum`() {
        val c = content(Size(size = 4, children = listOf(Text("tiny"))))
        assertTrue(c is PostContent.Size)
        assertEquals(14, (c as PostContent.Size).size)
    }

    @Test
    fun `font size within range is preserved`() {
        val c = content(Size(size = 16, children = listOf(Text("ok"))))
        assertEquals(16, (c as PostContent.Size).size)
    }

    @Test
    fun `hex color on text is mapped to the domain hex color value`() {
        val c = content(
            ColorDto(
                color = ColorValueDto.Hex(0xFF8800L),
                children = listOf(Text("orange")),
            ),
        )
        // text-only color -> TextContent.Color carrying the exact hex the user sees.
        assertTrue(c is TextContent.Color)
        assertEquals(ColorValue.Hex(0xFF8800L), (c as TextContent.Color).color)
    }

    @Test
    fun `named color on a block is mapped to the domain named color value`() {
        val c = content(
            ColorDto(
                color = ColorValueDto.Name("red"),
                children = listOf(Image("http://x/i.png")),
            ),
        )
        assertTrue(c is PostContent.Color)
        assertEquals(ColorValue.Name("red"), (c as PostContent.Color).color)
    }

    @Test
    fun `each wire text-alignment maps to the matching domain alignment`() {
        val cases = mapOf(
            TextAlignmentDto.Left to TextAlignment.Left,
            TextAlignmentDto.Right to TextAlignment.Right,
            TextAlignmentDto.Center to TextAlignment.Center,
            TextAlignmentDto.Justify to TextAlignment.Justify,
        )
        cases.forEach { (wire, domain) ->
            val c = content(Align(alignment = wire, children = listOf(Text("x"))))
            assertTrue("$wire did not become an Align block", c is PostContent.Align)
            assertEquals("alignment mismatch for $wire", domain, (c as PostContent.Align).alignment)
        }
    }

    @Test
    fun `consecutive PostBr produce only one spacer not a stack of spacers`() {
        // Two PostBr in a row must collapse to a SINGLE Spacer so paragraphs don't
        // accumulate ever-growing blank gaps as a user scrolls a long post.
        val c = content(Text("a"), PostBr, PostBr, Text("b"))
        val children = (c as ContentColumn).children
        val spacers = children.count { it == PostContent.Spacer }
        assertEquals("expected exactly one spacer, tree=$c", 1, spacers)
    }

    @Test
    fun `divider replaces a preceding spacer rather than stacking after it`() {
        // PostBr then Hr: the spacer is removed and only the Divider remains, so a
        // horizontal rule is never preceded by a redundant blank gap.
        val c = content(Text("a"), PostBr, Hr, Text("b"))
        val children = (c as ContentColumn).children
        assertTrue("divider must be present", children.contains(PostContent.Divider))
        assertEquals("spacer should have been replaced by divider", 0, children.count { it == PostContent.Spacer })
    }

    @Test
    fun `a Br between two text runs splits them into separate column rows`() {
        // Br is a line break: "a" and "b" must land in different rows of the column,
        // not be concatenated into one run.
        val c = content(Text("a"), Br, Text("b"))
        val children = (c as ContentColumn).children
        assertEquals(
            listOf(TextContent.Text("a"), TextContent.Text("b")),
            children,
        )
    }

    @Test
    fun `text run followed by an image groups into a row preserving order`() {
        // Mixed inline text + block image in one line: the converter must keep them in
        // order inside a ContentRow so the image appears after the text, not before.
        val c = content(Text("see "), Image("http://x/p.png"))
        val row = c as ContentRow
        assertEquals(2, row.children.size)
        // The leading text run is grouped into a TextRow; the trailing image follows
        // as a separate block group (a ContentRow wrapping the Image), in that order.
        assertEquals(TextContent.TextRow(listOf(TextContent.Text("see "))), row.children[0])
        assertEquals(ContentRow(listOf(PostContent.Image("http://x/p.png"))), row.children[1])
    }

    @Test
    fun `quote keeps its title and body so the user sees who is quoted`() {
        val c = content(
            Quote(title = "alice wrote", id = "q1", children = listOf(Text("original"))),
        )
        assertTrue(c is PostContent.Quote)
        val quote = c as PostContent.Quote
        assertEquals("alice wrote", quote.title)
        assertEquals(TextContent.Text("original"), quote.content)
    }

    @Test
    fun `nested bold inside italic over pure text stays inline text content`() {
        // Italic(Bold(Text)) is all text -> must stay TextContent (inline emphasis),
        // never promote to block-level PostContent.
        val c = content(Italic(listOf(Bold(listOf(Text("both"))))))
        assertEquals(
            TextContent.Italic(TextContent.Bold(TextContent.Text("both"))),
            c,
        )
    }

    @Test
    fun `link wrapping a box becomes a block link carrying the href`() {
        // A link around a non-text Box renders as a block-level link; the user-tappable
        // href must survive.
        val c = content(
            Link(src = "http://x/page", children = listOf(Box(listOf(Image("http://x/i.png"))))),
        )
        assertTrue(c is PostContent.Link)
        assertEquals("http://x/page", (c as PostContent.Link).src)
    }
}

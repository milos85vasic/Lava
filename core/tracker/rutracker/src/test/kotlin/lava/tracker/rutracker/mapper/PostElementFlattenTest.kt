package lava.tracker.rutracker.mapper

import lava.network.dto.topic.Align
import lava.network.dto.topic.Bold
import lava.network.dto.topic.Box
import lava.network.dto.topic.Br
import lava.network.dto.topic.Code
import lava.network.dto.topic.Hr
import lava.network.dto.topic.Image
import lava.network.dto.topic.Italic
import lava.network.dto.topic.Link
import lava.network.dto.topic.PostBr
import lava.network.dto.topic.PostElementDto
import lava.network.dto.topic.Quote
import lava.network.dto.topic.Spoiler
import lava.network.dto.topic.Text
import lava.network.dto.topic.TextAlignment
import lava.network.dto.topic.UList
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Anti-Bluff unit tests for [flattenToText] (in [PostElementFlatten.kt]).
 *
 * Why this is a real gap: [flattenToText] is the function that turns
 * rutracker's rich post AST ([PostElementDto] tree) into the plain-text
 * description and comment body shown to the user on the topic and comments
 * screens. It carries non-trivial, user-visible branching — Quote gets a
 * per-line "> " prefix plus an optional title, Link renders as
 * `text (src)`, Image renders as `[image] src`, UList collapses to a
 * comma-joined string, Hr/Br/PostBr become newlines, and the formatting
 * wrappers (Bold/Italic/Box/Align/...) are transparent passthroughs that
 * still emit their children. It was exercised only transitively through
 * TopicMapper/CommentsMapper; no test asserted directly on the flattened
 * string, so none of these branches' output was pinned down. The string
 * this function returns IS what the user reads — a bug here silently
 * corrupts every topic description and comment.
 *
 * Anti-Bluff Pact compliance:
 *  - SUT is the real `flattenToText` extension; no mocks (pure function).
 *  - Table-driven; the primary assertion is on the returned String value
 *    (the exact text a user would see), not on a call count.
 *  - Falsifiability rehearsed (see commit body / report): mutating the
 *    Quote per-line prefix and the Link `(src)` suffix each made specific
 *    rows fail with a clear assertion message.
 *
 * JUnit 4 to match the module's existing tests (RuTrackerDtoMappersTest).
 */
class PostElementFlattenTest {

    private data class Case(
        val name: String,
        val input: List<PostElementDto>,
        val expected: String,
    )

    private val cases = listOf(
        Case(
            name = "plain text passes through verbatim",
            input = listOf(Text("Hello world")),
            expected = "Hello world",
        ),
        Case(
            name = "result is trimmed of surrounding whitespace",
            input = listOf(Text("  padded  ")),
            expected = "padded",
        ),
        Case(
            name = "adjacent text nodes concatenate without separators",
            input = listOf(Text("foo"), Text("bar")),
            expected = "foobar",
        ),
        Case(
            name = "formatting wrappers are transparent and still emit children",
            input = listOf(
                Bold(listOf(Text("b"))),
                Italic(listOf(Text("i"))),
                Box(listOf(Text("x"))),
                Align(TextAlignment.Center, listOf(Text("c"))),
            ),
            expected = "bixc",
        ),
        Case(
            name = "Br becomes a newline",
            input = listOf(Text("line1"), Br, Text("line2")),
            expected = "line1\nline2",
        ),
        Case(
            name = "Hr and PostBr also become newlines",
            input = listOf(Text("a"), Hr, Text("b"), PostBr, Text("c")),
            expected = "a\nb\nc",
        ),
        Case(
            name = "Link renders child text followed by parenthesized src",
            input = listOf(Link(src = "https://example.com", children = listOf(Text("click here")))),
            expected = "click here (https://example.com)",
        ),
        Case(
            name = "Image renders the image marker followed by src",
            input = listOf(Image(src = "https://img.example/x.png")),
            expected = "[image] https://img.example/x.png",
        ),
        Case(
            name = "UList collapses children to a comma-joined string",
            input = listOf(
                UList(
                    listOf(
                        Text("alpha"),
                        Br,
                        Text("beta"),
                        Br,
                        Text("gamma"),
                    ),
                ),
            ),
            // Each child is flattened+trimmed individually; blank parts
            // (the Br-produced newlines) are filtered out before joining.
            expected = "alpha, beta, gamma",
        ),
        Case(
            name = "Spoiler with a title prefixes the title then emits children",
            input = listOf(Spoiler(title = "Cast", children = listOf(Text("the body")))),
            expected = "Cast: the body",
        ),
        Case(
            name = "Code with a blank title omits the prefix",
            input = listOf(Code(title = "", children = listOf(Text("x = 1")))),
            expected = "x = 1",
        ),
        Case(
            name = "Quote with a title prefixes each inner line with the marker",
            input = listOf(
                Quote(
                    title = "Author",
                    id = "42",
                    children = listOf(Text("first"), Br, Text("second")),
                ),
            ),
            // Leading "> " then "Author: ", then every inner line carries
            // its own "> " prefix.
            expected = "> Author: > first\n> second",
        ),
        Case(
            name = "Quote without a title still prefixes inner lines",
            input = listOf(
                Quote(
                    title = "",
                    id = "7",
                    children = listOf(Text("only line")),
                ),
            ),
            expected = "> > only line",
        ),
    )

    @Test
    fun `flattenToText produces the exact user-visible plain text per element type`() {
        cases.forEach { case ->
            val actual = case.input.flattenToText()
            assertEquals(
                "flattenToText mismatch for case: ${case.name}",
                case.expected,
                actual,
            )
        }
    }
}

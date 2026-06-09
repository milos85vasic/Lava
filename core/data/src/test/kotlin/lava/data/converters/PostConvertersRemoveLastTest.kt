package lava.data.converters

import lava.models.topic.Content
import lava.models.topic.ContentColumn
import lava.models.topic.PostContent
import lava.models.topic.TextContent
import lava.network.dto.topic.AuthorDto
import lava.network.dto.topic.Hr
import lava.network.dto.topic.PostBr
import lava.network.dto.topic.PostDto
import lava.network.dto.topic.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LVA-054 regression test for the package-internal post converter in
 * `lava.data.converters` (PostConverters.kt `toContent` / `addDivider`).
 *
 * The crash class: Kotlin `MutableList.removeLast()` compiles, on JDK21 + AGP
 * core-library-desugaring, to `java.util.List.removeLast` (the JDK21
 * `SequencedCollection` method). That method does NOT exist on the Android
 * platform `java.util.ArrayList` below API 35, so a real user on Android 14 /
 * 13 / 12 / ... hits `NoSuchMethodError` at runtime — even though the JVM unit
 * test passes (the JVM's `ArrayList` DOES have `removeLast`). This is exactly
 * the LVA-053 PostConverters crash; LVA-054 sweeps for ALL remaining
 * occurrences. The fix replaced `column.removeLast()` with
 * `column.removeAt(column.lastIndex)`, which is the API-safe equivalent that
 * removes and returns the last element on every Android API level.
 *
 * The production trigger is a post whose wire children are `[Text, PostBr, Hr]`:
 *  - Text  -> a TextContent.Text content in the current row
 *  - PostBr -> addSpacer(): flushes the row, then appends PostContent.Spacer
 *  - Hr     -> addDivider(): sees the trailing Spacer and removes it (the
 *              removeLast() call site) before appending PostContent.Divider.
 *
 * USER-VISIBLE OUTCOME asserted: the rendered post content is
 * `[Text("hello"), Divider]` — the trailing Spacer is collapsed into the
 * Divider exactly as the topic screen renders it. On a pre-API-35 device with
 * the OLD `removeLast()` code, this path threw NoSuchMethodError and the topic
 * post never rendered at all.
 *
 * FALSIFIABILITY REHEARSAL (LVA-054, §6.T.1 / Seventh Law clause 1):
 *   Mutation: in PostConverters.addDivider(), comment out the
 *     `column.removeAt(column.lastIndex)` line (skip the Spacer removal).
 *   Observed-Failure (this test, `divider_after_spacer_collapses_trailing_spacer`):
 *     java.lang.AssertionError: trailing Spacer must be collapsed into Divider
 *     expected:<[Text(text=hello), Divider]> but was:<[Text(text=hello),
 *     Spacer, Divider]>
 *   Reverted: yes
 *
 * NOTE on JVM reproducibility: a pure-JVM JUnit run CANNOT reproduce the
 * NoSuchMethodError itself (desktop `ArrayList` has `removeLast`). The desugar
 * gap is an Android-runtime (<API 35) property. This test therefore (a) pins
 * the corrected behavior of the exact code path that crashed, and (b) is the
 * regression that fails the moment the Spacer-removal logic regresses. The
 * static guard added in scripts/scan-no-removelast-seqcoll.sh is the mechanical
 * layer that prevents the unsafe API from being reintroduced at all.
 */
class PostConvertersRemoveLastTest {

    private fun postWith(vararg children: lava.network.dto.topic.PostElementDto): PostDto =
        PostDto(
            id = "1",
            author = AuthorDto(name = "tester"),
            date = "2026-01-01",
            children = children.toList(),
        )

    @Test
    fun divider_after_spacer_collapses_trailing_spacer() {
        val posts = listOf(postWith(Text("hello"), PostBr, Hr)).toPosts()

        assertEquals("exactly one post is produced", 1, posts.size)

        val content = posts.first().content
        // column.size > 1 (Text + Divider) so the converter wraps in a ContentColumn.
        assertTrue(
            "expected a ContentColumn for multi-element post content, was $content",
            content is ContentColumn,
        )
        val children = (content as ContentColumn).children

        assertEquals(
            "trailing Spacer must be collapsed into Divider",
            listOf<Content>(TextContent.Text("hello"), PostContent.Divider),
            children,
        )
    }

    @Test
    fun divider_after_spacer_at_column_head_collapses_to_single_divider() {
        // Children = [PostBr, Hr]: PostBr -> Spacer (column head), Hr -> addDivider
        // removes the Spacer then adds Divider. Result column has exactly one
        // element (Divider) -> converter returns the Divider directly (size == 1).
        val posts = listOf(postWith(PostBr, Hr)).toPosts()

        assertEquals(1, posts.size)
        assertEquals(
            "a Spacer immediately followed by a divider must render as a single Divider",
            PostContent.Divider,
            posts.first().content,
        )
    }
}

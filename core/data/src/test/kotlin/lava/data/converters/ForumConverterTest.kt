package lava.data.converters

import lava.models.forum.ForumItem
import lava.network.dto.forum.CategoryDto
import lava.network.dto.forum.CategoryPageDto
import lava.network.dto.forum.ForumDto
import lava.network.dto.forum.SectionDto
import lava.network.dto.topic.AuthorDto
import lava.network.dto.topic.TopicDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioral unit test for the package-internal forum converters in
 * `lava.data.converters` (Forum.kt). These turn the wire forum tree into the domain
 * model the user navigates: the root forum list, a category page's mixed item list,
 * and the bookmark round-trip. A bug here means the user sees the wrong forum
 * categories, items in the wrong order, or a crash opening a forum section.
 *
 * Every test drives the REAL `toForum()` / `toCategoryPage()` production path with
 * synthetic DTO input only — no SUT mocking. The chief assertion is on the resulting
 * domain structure (ids, ordering, item type) the user actually sees.
 *
 * FALSIFIABILITY REHEARSAL (full Bluff-Audit block in commit body) — e.g. the item
 * ordering in `toCategoryPage()` (children + topics + sections) was reordered to
 * (sections + topics + children); the ordering test FAILED because the first item was
 * a Section instead of the expected Category.
 */
class ForumConverterTest {

    @Test
    fun `root category with null id gets a synthesized stable id by index`() {
        // The user can still tap a root category even when the wire omits its id —
        // the converter must synthesize "c-<index>" so navigation has a key.
        val forum = ForumDto(
            children = listOf(
                CategoryDto(id = null, name = "First"),
                CategoryDto(id = null, name = "Second"),
            ),
        ).toForum()

        assertEquals(2, forum.children.size)
        assertEquals("c-0", forum.children[0].id)
        assertEquals("First", forum.children[0].name)
        assertEquals("c-1", forum.children[1].id)
        assertEquals("Second", forum.children[1].name)
    }

    @Test
    fun `root category keeps its real id when the wire provides one`() {
        val forum = ForumDto(
            children = listOf(CategoryDto(id = "42", name = "Movies")),
        ).toForum()

        assertEquals("42", forum.children.single().id)
    }

    @Test
    fun `nested categories are mapped recursively preserving the tree`() {
        val forum = ForumDto(
            children = listOf(
                CategoryDto(
                    id = "root",
                    name = "Root",
                    children = listOf(
                        CategoryDto(id = "child", name = "Child"),
                    ),
                ),
            ),
        ).toForum()

        val root = forum.children.single()
        assertEquals("root", root.id)
        assertEquals(1, root.children.size)
        assertEquals("child", root.children.single().id)
        assertEquals("Child", root.children.single().name)
    }

    @Test
    fun `root with no children produces an empty forum, not a crash`() {
        val forum = ForumDto(children = emptyList()).toForum()
        assertTrue(forum.children.isEmpty())
    }

    @Test
    fun `category page preserves pagination so the user can page through a section`() {
        val page = CategoryPageDto(
            category = CategoryDto(id = "c", name = "Cat"),
            page = 4,
            pages = 9,
        ).toCategoryPage()

        assertEquals(4, page.page)
        assertEquals(9, page.pages)
        assertTrue(page.items.isEmpty())
    }

    @Test
    fun `category page orders children, then topics, then sections`() {
        // The screen renders items top-to-bottom in this exact order: sub-categories
        // first, then topics, then sections. A reordering bug shows the user a
        // scrambled section.
        val page = CategoryPageDto(
            category = CategoryDto(id = "c", name = "Cat"),
            page = 1,
            pages = 1,
            children = listOf(CategoryDto(id = "sub", name = "Sub")),
            topics = listOf(TopicDto(id = "t", title = "A topic", author = AuthorDto(name = "u"))),
            sections = listOf(SectionDto(name = "Sec", topics = listOf("x"))),
        ).toCategoryPage()

        assertEquals(3, page.items.size)
        assertTrue("first item must be the Category", page.items[0] is ForumItem.Category)
        assertTrue("second item must be the Topic", page.items[1] is ForumItem.Topic)
        assertTrue("third item must be the Section", page.items[2] is ForumItem.Section)
    }

    @Test
    fun `category page item carries the sub-category id and name`() {
        val page = CategoryPageDto(
            category = CategoryDto(id = "c", name = "Cat"),
            page = 1,
            pages = 1,
            children = listOf(CategoryDto(id = "sub99", name = "Sub Forum")),
        ).toCategoryPage()

        val item = page.items.single() as ForumItem.Category
        assertEquals("sub99", item.category.id)
        assertEquals("Sub Forum", item.category.name)
    }

    @Test
    fun `section item carries its name and topic titles for display`() {
        val page = CategoryPageDto(
            category = CategoryDto(id = "c", name = "Cat"),
            page = 1,
            pages = 1,
            sections = listOf(SectionDto(name = "Announcements", topics = listOf("Rules", "FAQ"))),
        ).toCategoryPage()

        val section = (page.items.single() as ForumItem.Section).section
        assertEquals("Announcements", section.name)
        assertEquals(listOf("Rules", "FAQ"), section.topics)
    }

    @Test
    fun `topic item title is preserved so the user can read it in the list`() {
        val page = CategoryPageDto(
            category = CategoryDto(id = "c", name = "Cat"),
            page = 1,
            pages = 1,
            topics = listOf(TopicDto(id = "t1", title = "Interesting thread", author = AuthorDto(name = "bob"))),
        ).toCategoryPage()

        val topic = (page.items.single() as ForumItem.Topic).topic
        assertEquals("t1", topic.id)
        assertEquals("Interesting thread", topic.title)
    }

    @Test
    fun `a nested category missing its id is rejected rather than silently dropped`() {
        // requireNotNull guards the recursive branch: a non-root category without an id
        // is a malformed wire payload and must fail loudly, not render an un-navigable
        // item. (Root categories are tolerated via the index fallback above.)
        val malformed = ForumDto(
            children = listOf(
                CategoryDto(
                    id = "root",
                    name = "Root",
                    children = listOf(CategoryDto(id = null, name = "Broken child")),
                ),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            malformed.toForum()
        }
    }
}

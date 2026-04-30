package lava.tracker.rutracker.mapper

import lava.network.dto.forum.CategoryDto
import lava.network.dto.forum.ForumDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [ForumDtoMapper].
 *
 * Falsifiability rehearsal (Sixth Law clause 2): the load-bearing assertion
 * is that nested categories carry the correct parentId linking to their
 * parent's id. Breaking the recursion (e.g. always passing `null` as
 * parentId) would fail the "child has parentId == parent.id" assertion.
 */
class ForumDtoMapperTest {

    private val mapper = ForumDtoMapper()

    @Test
    fun `flat tree maps top-level categories with null parentId`() {
        val dto = ForumDto(
            children = listOf(
                CategoryDto(id = "10", name = "OS"),
                CategoryDto(id = "20", name = "Games"),
            ),
        )

        val tree = mapper.toForumTree(dto)

        assertEquals(2, tree.rootCategories.size)
        val os = tree.rootCategories[0]
        assertEquals("10", os.id)
        assertEquals("OS", os.name)
        assertNull(os.parentId)
        assertEquals(emptyList<Any>(), os.children)
    }

    @Test
    fun `nested categories carry parentId of their parent`() {
        val dto = ForumDto(
            children = listOf(
                CategoryDto(
                    id = "100",
                    name = "Software",
                    children = listOf(
                        CategoryDto(id = "101", name = "Linux Distros"),
                        CategoryDto(
                            id = "102",
                            name = "OS Tools",
                            children = listOf(
                                CategoryDto(id = "103", name = "Editors"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val tree = mapper.toForumTree(dto)

        val software = tree.rootCategories.single()
        assertEquals("100", software.id)
        assertNull(software.parentId)
        assertEquals(2, software.children.size)
        val linuxDistros = software.children[0]
        assertEquals("101", linuxDistros.id)
        assertEquals("100", linuxDistros.parentId)
        val osTools = software.children[1]
        assertEquals("102", osTools.id)
        assertEquals("100", osTools.parentId)
        val editors = osTools.children.single()
        assertEquals("103", editors.id)
        assertEquals("102", editors.parentId)
    }

    @Test
    fun `null id collapses to empty string preserving recursion`() {
        val dto = ForumDto(
            children = listOf(
                CategoryDto(
                    id = null,
                    name = "Header (no id)",
                    children = listOf(CategoryDto(id = "555", name = "real cat")),
                ),
            ),
        )

        val tree = mapper.toForumTree(dto)

        val header = tree.rootCategories.single()
        assertEquals("", header.id)
        // Child's parentId is "" (empty), reflecting the parent's missing id.
        val realCat = header.children.single()
        assertEquals("555", realCat.id)
        assertEquals("", realCat.parentId)
    }
}

package lava.testing.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import lava.models.forum.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioral-equivalence guard for [TestBookmarksRepository] (Anti-Bluff Pact
 * Third Law, LVA-012). Replaces the historical inverted
 * `TestBookmarksRepositoryStubBluffTest` (which passed BECAUSE the fake threw).
 * Pins the fake to the REAL `BookmarksRepositoryImpl` semantics (Room
 * `BookmarkDao`, insert = UPSERT-by-id, `timestamp DESC`):
 *
 *  - add UPSERTS by id and moves to newest; observers emit live;
 *  - isBookmark / getAllBookmarks reflect adds;
 *  - update(id, …) mutates ONLY an existing row (absent id = no-op);
 *  - observeBookmarks derives newTopicsCount from newTopics.size;
 *  - observeNewTopics() flattens across all bookmarks; the id-scoped overload is per-row;
 *  - remove / clear delete.
 *
 * Bluff-Audit (Sixth Law 6.A / Seventh Law clause 1):
 *   Mutation: in TestBookmarksRepository.update, remove the
 *             `if (rowsFlow.value.none { it.category.id == id }) return` guard so
 *             a missing id silently inserts/updates a phantom row.
 *   Observed-Failure: `update_only_mutates_existing_row` fails —
 *     "update of an absent id MUST be a no-op expected:<[]> but was:<[ghost]>".
 *   Reverted: yes.
 */
class TestBookmarksRepositoryEquivalenceTest {

    private fun category(id: String, name: String = "C$id"): Category = Category(id = id, name = name)

    @Test
    fun add_upserts_and_observers_are_live() = runTest {
        val repo = TestBookmarksRepository()

        assertFalse(repo.isBookmark("a"))
        repo.add(category("a"))
        repo.add(category("b"))

        // Live emission + newest-first.
        assertEquals(listOf("b", "a"), repo.observeIds().first())
        assertEquals(listOf("b", "a"), repo.getAllBookmarks().map(Category::id))
        assertTrue(repo.isBookmark("a"))
        assertTrue("observeBookmarks marks isBookmark", repo.observeBookmarks().first().all { it.isBookmark })

        // Re-add existing id → UPSERT (one row).
        repo.add(category("a", name = "A v2"))
        assertEquals("add of existing id MUST upsert (no duplicate)", listOf("a", "b"), repo.observeIds().first().sorted())
    }

    @Test
    fun update_only_mutates_existing_row() = runTest {
        val repo = TestBookmarksRepository()
        repo.add(category("a"))

        // Existing id → updated.
        repo.update("a", topics = listOf("t1", "t2"), newTopics = listOf("t2"))
        assertEquals(listOf("t1", "t2"), repo.getTopics("a"))
        assertEquals(listOf("t2"), repo.getNewTopics("a"))
        assertEquals(
            "newTopicsCount derives from newTopics.size",
            1,
            repo.observeBookmarks().first().first { it.category.id == "a" }.newTopicsCount,
        )

        // Absent id → no-op (real impl `get(id)?.let { … }`). No phantom row appears.
        repo.update("ghost", topics = listOf("x"), newTopics = listOf("x"))
        assertEquals("update of an absent id MUST be a no-op", listOf("a"), repo.observeIds().first())
    }

    @Test
    fun observeNewTopics_flattens_all_and_scopes_by_id() = runTest {
        val repo = TestBookmarksRepository()
        repo.add(category("a"))
        repo.add(category("b"))
        repo.update("a", topics = listOf("a1"), newTopics = listOf("a1"))
        repo.update("b", topics = listOf("b1", "b2"), newTopics = listOf("b1", "b2"))

        // No-arg overload flattens newTopics across all bookmarks.
        assertEquals(setOf("a1", "b1", "b2"), repo.observeNewTopics().first().toSet())
        // Id-scoped overload returns only that bookmark's newTopics.
        assertEquals(listOf("a1"), repo.observeNewTopics("a").first())
        assertEquals(emptyList<String>(), repo.observeNewTopics("absent").first())
    }

    @Test
    fun remove_and_clear_delete_rows() = runTest {
        val repo = TestBookmarksRepository()
        repo.add(category("a"))
        repo.add(category("b"))

        repo.remove("a")
        assertEquals(listOf("b"), repo.observeIds().first())
        assertFalse(repo.isBookmark("a"))

        repo.clear()
        assertEquals(emptyList<String>(), repo.observeIds().first())
    }
}

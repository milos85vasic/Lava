package lava.testing.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import lava.data.api.repository.BookmarksRepository
import lava.models.forum.Category
import lava.models.forum.CategoryModel

/**
 * Behaviorally equivalent in-memory fake of `BookmarksRepositoryImpl` (LVA-012,
 * 2026-06-09). Anti-Bluff Pact Third Law: every branch of the real impl MUST
 * have a matching branch in the fake.
 *
 * Real counterpart: `lava.data.impl.repository.BookmarksRepositoryImpl`, backed by
 * Room `BookmarkDao` whose insert uses `OnConflictStrategy.REPLACE` (UPSERT by id)
 * and whose list queries are ordered `timestamp DESC` (newest first). Contract
 * this fake mirrors:
 *
 *  - `add(category)` → DAO `insert(entity)` (UPSERT). Re-adding the same id
 *    replaces it; a fresh add starts with empty topics/newTopics and moves to
 *    newest position.
 *  - `observeBookmarks()` → `CategoryModel(category, isBookmark = true,
 *    newTopicsCount = newTopics.size)` newest-first.
 *  - `observeIds()` → ids of all bookmarks.
 *  - `observeNewTopics()` (no-arg) → FLATTENED newTopics across all bookmarks.
 *  - `observeNewTopics(id)` → that bookmark's newTopics (empty when absent).
 *  - `getAllBookmarks()` → categories newest-first. `getTopics(id)` /
 *    `getNewTopics(id)` → the stored lists (empty when absent).
 *  - `isBookmark(id)` → membership.
 *  - `update(id, topics, newTopics)` → updates ONLY when the id already exists
 *    (real impl `get(id)?.let { … }`; absent id is a silent no-op).
 *  - `remove(id)` → delete the matching row. `clear()` → deleteAll.
 *  - Every observer emits LIVE updates on every mutation (Room Flow semantics).
 *
 * The previous form of this fake threw `TODO("Not yet implemented")` from nearly
 * every method — a stub bluff fake (Third-Law violation): feature tests could not
 * wire it and silently rolled their own in-memory doubles. The inverted
 * `TestBookmarksRepositoryStubBluffTest` that documented the stub is replaced by
 * `TestBookmarksRepositoryEquivalenceTest`.
 */
class TestBookmarksRepository : BookmarksRepository {

    /** One stored bookmark, mirroring the relevant `BookmarkEntity` columns. */
    private data class Row(
        val category: Category,
        val topics: List<String>,
        val newTopics: List<String>,
        val timestamp: Long,
    )

    // Stored newest-first (Room `ORDER BY timestamp DESC`).
    private val rowsFlow = MutableStateFlow<List<Row>>(emptyList())

    // Strictly-increasing so the most-recently-added row has the LARGEST
    // timestamp and sorts FIRST under `timestamp DESC` (newest-first).
    private var nextTimestamp = 0L

    private fun sorted(rows: List<Row>): List<Row> = rows.sortedByDescending { it.timestamp }

    override fun observeBookmarks(): Flow<List<CategoryModel>> = rowsFlow.map { rows ->
        rows.map { row ->
            CategoryModel(
                category = row.category,
                isBookmark = true,
                newTopicsCount = row.newTopics.size,
            )
        }
    }

    override fun observeIds(): Flow<List<String>> = rowsFlow.map { rows -> rows.map { it.category.id } }

    override fun observeNewTopics(): Flow<List<String>> =
        rowsFlow.map { rows -> rows.flatMap { it.newTopics } }

    override fun observeNewTopics(id: String): Flow<List<String>> =
        rowsFlow.map { rows -> rows.firstOrNull { it.category.id == id }?.newTopics ?: emptyList() }

    override suspend fun getAllBookmarks(): List<Category> = rowsFlow.value.map { it.category }

    override suspend fun getTopics(id: String): List<String> =
        rowsFlow.value.firstOrNull { it.category.id == id }?.topics ?: emptyList()

    override suspend fun getNewTopics(id: String): List<String> =
        rowsFlow.value.firstOrNull { it.category.id == id }?.newTopics ?: emptyList()

    override suspend fun isBookmark(id: String): Boolean = rowsFlow.value.any { it.category.id == id }

    override suspend fun add(category: Category) {
        // DAO insert is UPSERT-by-id with a fresh timestamp → moves to newest and
        // resets the (topics, newTopics) lists, exactly as toBookmarkEntity yields
        // an entity with empty topic lists.
        val withoutExisting = rowsFlow.value.filterNot { it.category.id == category.id }
        val row = Row(
            category = category,
            topics = emptyList(),
            newTopics = emptyList(),
            timestamp = nextTimestamp++,
        )
        rowsFlow.value = sorted(withoutExisting + row)
    }

    override suspend fun remove(id: String) {
        rowsFlow.value = rowsFlow.value.filterNot { it.category.id == id }
    }

    override suspend fun update(id: String, topics: List<String>, newTopics: List<String>) {
        // Real impl: `bookmarkDao.get(id)?.let { … }` — update ONLY when present.
        if (rowsFlow.value.none { it.category.id == id }) return
        rowsFlow.value = rowsFlow.value.map { row ->
            if (row.category.id == id) row.copy(topics = topics, newTopics = newTopics) else row
        }
    }

    override suspend fun clear() {
        rowsFlow.value = emptyList()
    }
}

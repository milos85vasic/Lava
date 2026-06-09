package lava.testing.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import lava.data.api.repository.SearchHistoryRepository
import lava.models.forum.Category
import lava.models.search.Filter
import lava.models.search.Search

/**
 * Behaviorally equivalent fake of `SearchHistoryRepositoryImpl`.
 *
 * LVA-015 (2026-06-09) — Third-Law (behavioural-equivalence) fix for `add` +
 * `observeAll`. The prior form assigned a POSITIONAL id (`Search(it.size, …)`)
 * and ALWAYS appended in insertion order, so:
 *   - re-adding the same search created a SECOND row (real impl REPLACEs → one);
 *   - ids were `0,1,2,…` while the real id is content-derived, so a `remove(id)`
 *     test exercised a different id space than production;
 *   - `observeAll` emitted oldest-first while the real DAO is newest-first.
 * That made any "search history" test pass against a fake shaped unlike
 * production — a Sixth-Law-clause-3 bluff fake.
 *
 * Real counterpart: `lava.data.impl.repository.SearchHistoryRepositoryImpl` over
 * Room `SearchHistoryDao`:
 *   - `add(filter)` → `insert(filter.toEntity())` with `@Insert(onConflict = REPLACE)`;
 *     the entity id is content-derived (`Search.kt` `Filter.id()` = hash of
 *     query + period + author.id + categories — NOTE it deliberately ignores
 *     sort/order/providerIds, so two searches that differ ONLY in sort collapse
 *     to ONE history row).
 *   - `observeAll()` is `ORDER BY timestamp DESC` (newest-first).
 *   - `remove(id)` deletes the matching row.
 *   - `clear()` deletes all.
 *
 * This fake mirrors all branches: content-derived id (replicating `Filter.id()`,
 * with the equivalence test pinning the dedup behaviour against drift), UPSERT
 * keyed on that id, newest-first emission (re-add/replace moves to the front).
 */
class TestSearchHistoryRepository : SearchHistoryRepository {
    // Stored newest-first (front = most recently added/replaced).
    private val searchFlow: MutableStateFlow<List<Search>> = MutableStateFlow(emptyList())

    override fun observeAll(): Flow<List<Search>> = searchFlow

    override suspend fun add(filter: Filter) {
        val id = filter.historyId()
        searchFlow.update { current ->
            // UPSERT + newest-first: drop any existing row with this id, prepend.
            listOf(Search(id, filter)) + current.filterNot { it.id == id }
        }
    }

    override suspend fun remove(id: Int) {
        // Mirror SearchHistoryRepositoryImpl.remove → DAO delete(id): drop the
        // matching row, keep the rest.
        searchFlow.update { list -> list.filterNot { it.id == id } }
    }

    override suspend fun clear() {
        searchFlow.update { emptyList() }
    }
}

/**
 * Replicates the private `Filter.id()` in
 * `core/data/src/main/kotlin/lava/data/converters/Search.kt` (the production
 * source of truth for the Room primary key). Deliberately ignores sort, order,
 * and providerIds — so logically-equivalent searches dedup. The equivalence
 * test (`TestSearchHistoryRepositoryTest`) pins this behaviour; if production
 * `Filter.id()` changes, that test must be updated in the same commit.
 */
private fun Filter.historyId(): Int {
    var id = query?.hashCode() ?: 0
    id = 31 * id + period.ordinal
    id = 31 * id + (author?.id?.hashCode() ?: 0)
    id = 31 * id + (categories?.sumOf(Category::hashCode) ?: 0)
    return id
}

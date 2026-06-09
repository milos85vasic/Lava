package lava.testing.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import lava.data.api.repository.SuggestsRepository

/**
 * Behaviorally equivalent fake of `SuggestsRepositoryImpl`.
 *
 * LVA-014 (2026-06-09) — Third-Law (behavioural-equivalence) fix. The prior
 * form was a TODO-throw stub (`observeSuggests()` and `addSuggest()` threw
 * `TODO("Not yet implemented")`, `clear()` was a silent no-op) — the same
 * unusable-stub bluff class as LVA-012. Any ViewModel/UseCase test wired to it
 * could not exercise suggests at all, yet the fake's presence implied coverage.
 *
 * Real counterpart: `lava.data.impl.repository.SuggestsRepositoryImpl` backed by
 * Room `SuggestDao`:
 *   - `observeSuggests()` emits suggests newest-first (`SuggestDao.observerAll`
 *     is `ORDER BY timestamp DESC`).
 *   - `addSuggest(s)` does `suggestDao.insert(s.toEntity())` with
 *     `@Insert(onConflict = REPLACE)` and the entity id is `s.lowercase().hashCode()`
 *     (`Search.kt` `String.toEntity()`), i.e. CASE-INSENSITIVE UPSERT — re-adding
 *     "Ubuntu" then "ubuntu" keeps ONE row (the newest), not two.
 *   - `clear()` deletes all.
 *
 * This fake mirrors all three branches: case-insensitive dedup keyed on
 * `lowercase().hashCode()`, newest-first emission (re-add moves the entry to the
 * front), and `clear()` empties.
 */
class TestSuggestsRepository : SuggestsRepository {
    private data class Row(val id: Int, val suggest: String)

    // Stored newest-first (front = most recently added/replaced), mirroring the
    // real DAO's `ORDER BY timestamp DESC`.
    private val rows = MutableStateFlow<List<Row>>(emptyList())

    override fun observeSuggests(): Flow<List<String>> =
        rows.map { list -> list.map(Row::suggest) }

    override suspend fun addSuggest(suggest: String) {
        // Source of truth: Search.kt `String.toEntity()` → id = lowercase().hashCode()
        // + SuggestDao `@Insert(onConflict = REPLACE)`. Case-insensitive UPSERT,
        // moved to front (newest).
        val id = suggest.lowercase().hashCode()
        rows.update { current ->
            listOf(Row(id, suggest)) + current.filterNot { it.id == id }
        }
    }

    override suspend fun clear() {
        rows.value = emptyList()
    }
}

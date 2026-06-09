package lava.testing.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Behavioral-equivalence guard for [TestSuggestsRepository] (Anti-Bluff Pact,
 * Third Law).
 *
 * LVA-014 (2026-06-09): the prior fake was a TODO-throw stub (observeSuggests /
 * addSuggest threw, clear no-op'd) — unusable, the LVA-012 bluff class. These
 * tests pin the rewritten fake to the real `SuggestsRepositoryImpl` semantics:
 * case-insensitive UPSERT keyed on `lowercase().hashCode()`, newest-first
 * emission (`SuggestDao` is `ORDER BY timestamp DESC`), and clear-empties.
 */
class TestSuggestsRepositoryTest {

    @Test
    fun `addSuggest emits newest-first`() = runTest {
        val repo = TestSuggestsRepository()
        repo.addSuggest("alpha")
        repo.addSuggest("beta")

        assertEquals(listOf("beta", "alpha"), repo.observeSuggests().first())
    }

    /**
     * Real impl: `@Insert(onConflict = REPLACE)` with id = `lowercase().hashCode()`.
     * Re-adding the same suggest (any case) keeps ONE row.
     *
     * Bluff-Audit (Seventh Law clause 1):
     *   Mutation: in TestSuggestsRepository.addSuggest, drop the
     *             `.filterNot { it.id == id }` so re-adds always prepend.
     *   Observed-Failure: this test fails — "Ubuntu"+"ubuntu" yield 2 rows,
     *     "expected:<1> but was:<2>".
     *   Reverted: yes.
     *
     * Primary assertion: persisted row count after a case-variant duplicate add.
     */
    @Test
    fun `addSuggest is a case-insensitive upsert`() = runTest {
        val repo = TestSuggestsRepository()
        repo.addSuggest("Ubuntu")
        repo.addSuggest("ubuntu")

        val all = repo.observeSuggests().first()
        assertEquals("case-insensitive UPSERT MUST keep one row", 1, all.size)
        // Newest write wins (the lower-case re-add moved to front, REPLACE).
        assertEquals("ubuntu", all.single())
    }

    @Test
    fun `distinct suggests get distinct rows`() = runTest {
        val repo = TestSuggestsRepository()
        repo.addSuggest("alpha")
        repo.addSuggest("beta")
        assertEquals(2, repo.observeSuggests().first().size)
    }

    @Test
    fun `clear empties the suggests`() = runTest {
        val repo = TestSuggestsRepository()
        repo.addSuggest("alpha")
        repo.clear()
        assertEquals(emptyList<String>(), repo.observeSuggests().first())
    }
}

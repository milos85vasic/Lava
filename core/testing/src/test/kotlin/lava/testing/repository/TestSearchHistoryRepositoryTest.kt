package lava.testing.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import lava.models.search.Filter
import lava.models.search.Sort
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Behavioral-equivalence guard for [TestSearchHistoryRepository] (Anti-Bluff
 * Pact, Third Law).
 *
 * LVA-015 (2026-06-09): the prior version of this test asserted the FAKE's
 * shape, not production's — positional ids `[0,2]` and oldest-first order
 * `["alpha","beta"]`. The real `SearchHistoryRepositoryImpl` uses a
 * content-derived id (`Filter.id()`), `@Insert(onConflict = REPLACE)` UPSERT,
 * and `ORDER BY timestamp DESC` (newest-first). These tests pin the fake to
 * those real semantics.
 */
class TestSearchHistoryRepositoryTest {

    @Test
    fun `add prepends newest-first like the real DESC ordering`() = runTest {
        val repo = TestSearchHistoryRepository()
        repo.add(Filter(query = "alpha"))
        repo.add(Filter(query = "beta"))

        val all = repo.observeAll().first()
        // Real DAO is ORDER BY timestamp DESC → most recent ("beta") first.
        assertEquals(listOf("beta", "alpha"), all.map { it.filter.query })
    }

    /**
     * Real impl is `@Insert(onConflict = REPLACE)` keyed on the content-derived
     * `Filter.id()`. Re-adding the SAME logical search must keep ONE row (the
     * newest), not append a duplicate.
     *
     * Bluff-Audit (Seventh Law clause 1):
     *   Mutation: in TestSearchHistoryRepository.add, revert to
     *             `searchFlow.update { it.plus(Search(it.size, filter)) }`
     *             (positional id, always append — the historical bluff form).
     *   Observed-Failure: this test fails — re-adding "alpha" yields 2 rows,
     *     "expected:<1> but was:<2>".
     *   Reverted: yes.
     *
     * Primary assertion: the persisted row count after a duplicate add.
     */
    @Test
    fun `add upserts the same logical search instead of duplicating`() = runTest {
        val repo = TestSearchHistoryRepository()
        repo.add(Filter(query = "alpha"))
        repo.add(Filter(query = "alpha"))

        val all = repo.observeAll().first()
        assertEquals("re-adding the same search MUST UPSERT (one row)", 1, all.size)
        assertEquals("alpha", all.single().filter.query)
    }

    /**
     * `Filter.id()` deliberately ignores `sort`/`order`/`providerIds`, so two
     * searches that differ ONLY in sort collapse to the SAME history row
     * (the user sees one "ubuntu" entry, not one per sort choice). The fake's
     * replicated id MUST honour that.
     */
    @Test
    fun `add dedups searches that differ only by sort`() = runTest {
        val repo = TestSearchHistoryRepository()
        repo.add(Filter(query = "ubuntu", sort = Sort.DATE))
        repo.add(Filter(query = "ubuntu", sort = Sort.SEEDS))

        val all = repo.observeAll().first()
        assertEquals(
            "searches differing only by sort MUST collapse to one row (Filter.id ignores sort)",
            1,
            all.size,
        )
        // The newest write wins (REPLACE).
        assertEquals(Sort.SEEDS, all.single().filter.sort)
    }

    @Test
    fun `distinct queries get distinct rows`() = runTest {
        val repo = TestSearchHistoryRepository()
        repo.add(Filter(query = "alpha"))
        repo.add(Filter(query = "beta"))
        assertEquals(2, repo.observeAll().first().size)
    }

    /**
     * `remove(id)` deletes the row whose content-derived id matches and keeps
     * the rest — mirroring the real DAO `delete(id)`. We capture the real ids
     * from `observeAll` (NOT positional indices) so the test exercises the same
     * id space production does.
     *
     * Falsifiability: with the historical `filter { it.id == id }` (keeps the
     * matched row, drops the others) this fails — only "beta" would remain.
     */
    @Test
    fun `remove deletes the matching content id and keeps the rest`() = runTest {
        val repo = TestSearchHistoryRepository()
        repo.add(Filter(query = "alpha"))
        repo.add(Filter(query = "beta"))
        repo.add(Filter(query = "gamma"))

        val betaId = repo.observeAll().first().first { it.filter.query == "beta" }.id
        repo.remove(betaId)

        val remaining = repo.observeAll().first().map { it.filter.query }
        // Newest-first, beta removed → gamma then alpha.
        assertEquals(listOf("gamma", "alpha"), remaining)
    }

    @Test
    fun `clear empties the history`() = runTest {
        val repo = TestSearchHistoryRepository()
        repo.add(Filter(query = "alpha"))
        repo.clear()
        assertEquals(emptyList<String>(), repo.observeAll().first().map { it.filter.query })
    }
}

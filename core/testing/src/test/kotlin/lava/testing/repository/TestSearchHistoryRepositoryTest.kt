package lava.testing.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import lava.models.search.Filter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Behavioral-equivalence guard for [TestSearchHistoryRepository] (Anti-Bluff
 * Pact, Third Law). A test double whose `remove` does the opposite of the real
 * `SearchHistoryRepositoryImpl.remove` (Room `delete(id)`) is a bluff fake — it
 * lets a ViewModel/UseCase test about "remove from history" pass while the real
 * remove is broken. This test pins the fake to the REAL remove semantics:
 * remove(id) deletes the matching row and keeps the rest.
 *
 * Falsifiability: with the historical `it.filter { it.id == id }` (keeps the
 * matched row, drops the others) `remove deletes the matching item …` fails
 * with `expected [0, 2] but was [1]`.
 */
class TestSearchHistoryRepositoryTest {

    @Test
    fun `add appends in insertion order with monotonic ids`() = runTest {
        val repo = TestSearchHistoryRepository()
        repo.add(Filter(query = "alpha"))
        repo.add(Filter(query = "beta"))

        val all = repo.observeAll().first()
        assertEquals(listOf("alpha", "beta"), all.map { it.filter.query })
    }

    @Test
    fun `remove deletes the matching item and keeps the rest`() = runTest {
        val repo = TestSearchHistoryRepository()
        repo.add(Filter(query = "alpha")) // id 0
        repo.add(Filter(query = "beta")) // id 1
        repo.add(Filter(query = "gamma")) // id 2

        repo.remove(1)

        val all = repo.observeAll().first()
        assertEquals("remove(1) must delete beta and keep alpha+gamma", listOf(0, 2), all.map { it.id })
        assertEquals(listOf("alpha", "gamma"), all.map { it.filter.query })
    }

    @Test
    fun `clear empties the history`() = runTest {
        val repo = TestSearchHistoryRepository()
        repo.add(Filter(query = "alpha"))
        repo.clear()
        assertEquals(emptyList<Int>(), repo.observeAll().first().map { it.id })
    }
}

package lava.testing.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Documents that TestBookmarksRepository is currently a complete stub
 * (every method body is `TODO("Not yet implemented")`). This is a known
 * Anti-Bluff Pact Third-Law violation tracked in the SP-3a coverage
 * exemption ledger at
 * docs/superpowers/specs/2026-04-30-sp3a-coverage-exemptions.md.
 *
 * These tests are INVERTED: they pass *because* the fake is a bluff.
 * When TestBookmarksRepository is later implemented to behavioral
 * equivalence with BookmarksRepositoryImpl, these tests will start
 * failing — that failure is the trigger to:
 *   1. Update the exemption ledger to mark the bluff resolved.
 *   2. Replace this file with a real TestBookmarksRepositoryEquivalenceTest
 *      following the pattern in TestEndpointsRepositoryEquivalenceTest.
 *
 * Falsifiability rehearsal recorded at
 * .lava-ci-evidence/sp3a-bluff-audit/0.2-test-bookmarks-repository.json.
 *
 * Real-counterpart contract (BookmarksRepositoryImpl, what an equivalent
 * fake would need to satisfy when implementation lands):
 *   - getAllBookmarks() returns the persisted Category list.
 *   - isBookmark(id) returns true iff a row with that id exists.
 *   - observeBookmarks() emits CategoryModel rows with isBookmark=true
 *     and newTopicsCount derived from the entity's newTopics size.
 *   - add(category) inserts a row keyed on category id (Room PK; second
 *     add of the same id must surface a SQLiteConstraintException-equivalent).
 *   - clear() deletes all rows.
 *
 * Until that contract is implemented, MenuViewModelTest constructs a
 * ClearBookmarksUseCase against this stub but does NOT exercise any code
 * path that calls a stubbed method (no test invokes MenuAction.ClearBookmarks
 * or any bookmark-clearing intent). The wiring is therefore latent; adding
 * such a test before fixing the stub would surface NotImplementedError.
 */
class TestBookmarksRepositoryStubBluffTest {

    /**
     * Suspend-method bluff: getAllBookmarks() body is `TODO(...)`.
     * Inverted assertion — passes BECAUSE the stub throws.
     */
    @Test
    fun getAllBookmarks_throws_NotImplementedError_proving_fake_is_a_stub_bluff() = runTest {
        val repository = TestBookmarksRepository()
        assertThrows(NotImplementedError::class.java) {
            // runTest's TestScope swallows suspend boundaries; the throw
            // surfaces synchronously from the TODO() call site.
            kotlinx.coroutines.runBlocking { repository.getAllBookmarks() }
        }
    }

    /**
     * Suspend-method bluff: isBookmark(id) body is `TODO(...)`.
     * Inverted assertion — passes BECAUSE the stub throws.
     */
    @Test
    fun isBookmark_throws_NotImplementedError_proving_fake_is_a_stub_bluff() = runTest {
        val repository = TestBookmarksRepository()
        assertThrows(NotImplementedError::class.java) {
            kotlinx.coroutines.runBlocking { repository.isBookmark("any-id") }
        }
    }

    /**
     * Flow-method bluff: observeBookmarks()'s factory body is `TODO(...)`,
     * which throws on the call (not on first collection). The throw fires
     * at invocation time because the TODO sits in the function body before
     * any Flow builder.
     * Inverted assertion — passes BECAUSE the stub throws.
     */
    @Test
    fun observeBookmarks_throws_NotImplementedError_proving_fake_is_a_stub_bluff() = runTest {
        val repository = TestBookmarksRepository()
        assertThrows(NotImplementedError::class.java) {
            // Calling observeBookmarks() itself executes the TODO() in the
            // function body — there is no Flow builder wrapper to defer it.
            // We additionally call .first() to defend against any future
            // refactor that moves the TODO inside a flow { ... } block.
            kotlinx.coroutines.runBlocking { repository.observeBookmarks().first() }
        }
    }
}

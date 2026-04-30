package lava.testing.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import lava.models.settings.Endpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * SP-3a Phase 0 Task 0.1 — equivalence audit of [TestEndpointsRepository].
 *
 * Real counterpart: `lava.data.impl.repository.EndpointsRepositoryImpl`,
 * which delegates to a Room `EndpointDao` whose entity carries an
 * `@PrimaryKey` on the endpoint identity. Inserting a row with a
 * primary key already present in the table throws
 * `android.database.sqlite.SQLiteConstraintException` at runtime.
 *
 * Anti-Bluff Pact Third Law: every branch of the real implementation
 * MUST have a matching branch in the fake. The fake's behaviour was
 * fixed on 2026-04-29 (see the file's own KDoc lines 10-43) — this
 * test locks the fix in place by:
 *
 *  1. Asserting the duplicate-rejection branch throws an
 *     `IllegalStateException` whose message names the conflict.
 *  2. Asserting the `isEmpty()`-guarded seeding branch fires once
 *     and once only — re-observing must NOT re-seed.
 *
 * Falsifiability rehearsal (Sixth Law clause 6.A): comment out the
 * `if (mutableEndpoints.value.contains(endpoint)) { throw … }` block
 * in [TestEndpointsRepository.add] and re-run this class — the first
 * test below MUST fail with a clear assertion message naming the
 * duplicate-rejection violation. The recorded failure is preserved in
 * `.lava-ci-evidence/sp3a-bluff-audit/0.1-test-endpoints-repository.json`.
 */
class TestEndpointsRepositoryEquivalenceTest {

    /**
     * The real impl backs `add(endpoint)` with `endpointDao.insert(...)`,
     * and the entity declares an `@PrimaryKey` on the endpoint identity.
     * Inserting a duplicate row hits Room's
     * `SQLiteConstraintException`. The fake must surface an analogous
     * error so consumer tests that rely on duplicate-rejection (e.g.
     * `returns AlreadyConfigured when same endpoint already exists`) do
     * not silently see a swallowed dup.
     *
     * Primary assertion: an `IllegalStateException` is thrown on the
     * second `add` call and its message contains "already exists".
     */
    @Test
    fun fake_rejects_duplicate_endpoint_like_real_impl() = runTest {
        val repository = TestEndpointsRepository()
        val endpoint = Endpoint.Mirror("192.168.1.100:8080")

        // First add — must succeed (the table was empty for this row).
        repository.add(endpoint)

        // Second add — must throw, mirroring Room PRIMARY KEY conflict.
        try {
            repository.add(endpoint)
            fail(
                "Adding the same endpoint twice MUST throw " +
                    "IllegalStateException — TestEndpointsRepository diverged " +
                    "from EndpointsRepositoryImpl's Room PRIMARY KEY behaviour " +
                    "(this is a Third-Law bluff fake; see CLAUDE.md Sixth Law 6.A).",
            )
        } catch (e: IllegalStateException) {
            // Message-substring check anchors the test to the human-readable
            // failure signal a developer would actually see in a stack trace.
            assertNotNull(
                "IllegalStateException must carry a non-null message naming the conflict",
                e.message,
            )
            assertTrue(
                "Duplicate-rejection IllegalStateException message MUST contain " +
                    "'already exists' to match the fake's contract — got: ${e.message}",
                e.message!!.contains("already exists"),
            )
        }
    }

    /**
     * The real impl's seeding branch is `if (endpointDao.isEmpty()) { … }`.
     * That guard fires exactly once: on first observation against an
     * empty table. A second observation, after the table is populated,
     * must NOT re-insert the defaults.
     *
     * Primary assertion: observing `observeAll()` twice in a row yields
     * the same `[Endpoint.Rutracker]` snapshot — NOT
     * `[Endpoint.Rutracker, Endpoint.Rutracker]`.
     */
    @Test
    fun fake_seeds_default_only_when_empty_like_real_impl() = runTest {
        val repository = TestEndpointsRepository()

        // First observation triggers `onStart` and seeds the default.
        val firstSnapshot = repository.observeAll().first()
        assertEquals(
            "First observation against an empty store MUST seed exactly " +
                "[Endpoint.Rutracker] — this is the fresh-install user-visible state.",
            listOf(Endpoint.Rutracker),
            firstSnapshot,
        )

        // Second observation must NOT re-seed — the store is no longer empty.
        // If the fake re-runs the seed unconditionally, the snapshot would
        // be [Rutracker, Rutracker] (or worse, the store would silently grow
        // without bound on every observation, which is a Third-Law bluff).
        val secondSnapshot = repository.observeAll().first()
        assertEquals(
            "Second observation MUST NOT re-seed (real impl is " +
                "isEmpty()-guarded). Got: $secondSnapshot",
            listOf(Endpoint.Rutracker),
            secondSnapshot,
        )
    }
}

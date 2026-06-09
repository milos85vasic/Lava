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
     * LVA-011 regression — Third-Law (behavioural-equivalence) bluff fix.
     *
     * Real counterpart: `EndpointsRepositoryImpl.add()` early-returns for
     * `Endpoint.Rutracker`:
     *
     *     override suspend fun add(endpoint: Endpoint) {
     *         if (endpoint is Endpoint.Rutracker) return   // ← no-op
     *         endpointDao.insert(endpoint.toEntity())
     *     }
     *
     * i.e. adding direct rutracker.org is a SILENT no-op — it is never
     * persisted (operator directive 2026-05-12: rutracker.org is no longer a
     * user-addable endpoint). The previous fake STORED Rutracker (and would
     * raise a duplicate-conflict on a second Rutracker add), so a test
     * asserting "adding Rutracker is a no-op" would pass against the fake
     * while exercising DIFFERENT behaviour than production. This test locks
     * the parity fix in place by asserting ALL THREE real constraints in one
     * place:
     *
     *  1. add(Rutracker) is a no-op — the store does not grow, and a second
     *     add(Rutracker) does NOT throw (proving Rutracker never entered the
     *     dup-tracked set), matching the real impl's early-return.
     *  2. Real (non-Rutracker) duplicates are STILL rejected with
     *     IllegalStateException, matching Room's PRIMARY KEY conflict.
     *  3. The isEmpty()-guarded default seeding still fires on first observe.
     *
     * Bluff-Audit (Sixth Law clause 6.A / Seventh Law clause 1):
     *   Mutation: remove `if (endpoint is Endpoint.Rutracker) return` from
     *             TestEndpointsRepository.add (the LVA-011 no-op branch).
     *   Observed-Failure: this test fails on the post-add snapshot assertion —
     *     "add(Rutracker) MUST be a no-op (real impl early-returns) — store
     *      grew to [Rutracker] / or the second add(Rutracker) threw …"
     *   Reverted: yes.
     *
     * Primary assertion: user-visible persisted state — the snapshot list
     * after add(Rutracker) is unchanged (empty), and the real Mirror duplicate
     * is rejected.
     */
    @Test
    fun fake_no_ops_rutracker_add_like_real_impl() = runTest {
        val repository = TestEndpointsRepository()

        // (1) add(Rutracker) MUST be a no-op — real impl early-returns and
        //     never persists it. The store stays empty.
        repository.add(Endpoint.Rutracker)
        assertTrue(
            "add(Endpoint.Rutracker) MUST be a no-op (real impl early-returns " +
                "in EndpointsRepositoryImpl.add) — but the store grew to " +
                "${repository.currentEndpoints()}. This is the LVA-011 " +
                "Third-Law bluff fake: the fake stored Rutracker while " +
                "production never does.",
            repository.currentEndpoints().isEmpty(),
        )

        // A SECOND add(Rutracker) MUST ALSO be a silent no-op — it must NOT
        // throw a duplicate-conflict. The real impl early-returns before the
        // insert, so Rutracker never enters the dup-tracked set. If the fake
        // had stored Rutracker on the first add, this second call would throw.
        repository.add(Endpoint.Rutracker)
        assertTrue(
            "A second add(Endpoint.Rutracker) MUST also be a silent no-op " +
                "(real impl early-returns before any persistence, so Rutracker " +
                "is never in the dup-tracked set). Store is now: " +
                "${repository.currentEndpoints()}",
            repository.currentEndpoints().isEmpty(),
        )

        // (2) Real (non-Rutracker) endpoints MUST still be persisted AND
        //     duplicate-rejected, exactly as before — the no-op branch must
        //     not weaken the Room PRIMARY KEY parity.
        val mirror = Endpoint.Mirror("192.168.1.100:8080")
        repository.add(mirror)
        assertEquals(
            "A real (non-Rutracker) endpoint MUST be persisted by the fake — " +
                "the LVA-011 no-op branch must only short-circuit Rutracker.",
            listOf(mirror),
            repository.currentEndpoints(),
        )
        try {
            repository.add(mirror)
            fail(
                "Adding the same non-Rutracker endpoint twice MUST still throw " +
                    "IllegalStateException — the LVA-011 no-op branch must not " +
                    "disable the Room PRIMARY KEY duplicate-rejection parity.",
            )
        } catch (e: IllegalStateException) {
            assertTrue(
                "Duplicate-rejection message MUST contain 'already exists' — " +
                    "got: ${e.message}",
                e.message?.contains("already exists") == true,
            )
        }

        // (3) Default seeding still fires on first observation (isEmpty()-guarded).
        //     Adding the Mirror above means the store is no longer empty, so a
        //     fresh repository is used to prove the seed branch is intact.
        val freshRepository = TestEndpointsRepository()
        assertEquals(
            "First observation against an empty store MUST still seed the " +
                "default — the LVA-011 add() fix must not disturb the " +
                "isEmpty()-guarded seeding branch.",
            listOf(Endpoint.Rutracker),
            freshRepository.observeAll().first(),
        )
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

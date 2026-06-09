package lava.testing.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import lava.data.api.repository.EndpointsRepository
import lava.models.settings.Endpoint

/**
 * Behaviorally equivalent fake of `EndpointsRepositoryImpl`.
 *
 * Anti-Bluff Pact Third Law: each branch of the real implementation
 * MUST have a matching branch in the fake. The real impl
 * (`core/data/src/main/kotlin/lava/data/impl/repository/EndpointsRepositoryImpl.kt`)
 * uses:
 *
 *     onStart {
 *         runCatching {
 *             if (endpointDao.isEmpty()) {            // ← guarded
 *                 endpointDao.insertAll(defaultEndpoints)
 *             }
 *         }
 *     }
 *
 * — i.e. it seeds defaults ONLY when the table is currently empty. A
 * test that calls `add(mirror)` before any observation would, on the
 * real impl, end up with `[mirror, …]` after seeding skips because
 * the table is no longer empty. The previous form of this fake
 * unconditionally overwrote the list with `[Proxy, Rutracker]` on
 * first observation — which silently destroyed any earlier add and
 * caused `returns AlreadyConfigured when same endpoint already
 * exists and is selected` to fail with the wrong result type. That
 * was a Sixth-Law-clause-3 bluff: the fake's "seeded" branch did
 * not match the real impl's `isEmpty()`-guarded branch. Fixed
 * 2026-04-29 alongside the `TestDispatchers` scheduler-share fix.
 *
 * Behaviour (LVA-013, 2026-06-09 — deeper Third-Law parity for observeAll):
 * - Seeds NOTHING on first observation. The real impl's `defaultEndpoints`
 *   is `emptyList()` (operator directive 2026-05-12: direct rutracker.org is
 *   no longer a seeded/listed endpoint), so its `if (isEmpty()) insertAll(...)`
 *   branch inserts nothing — a fresh-install user's first observe emits [].
 * - NEVER emits [Endpoint.Rutracker]. The real impl `purgeRutrackerLegacy()`s
 *   any stale row on every observe AND `.filterNot { it is Endpoint.Rutracker }`s
 *   every emission. The fake mirrors this with an emission-level filter.
 *   (The PRIOR form SEEDED + EMITTED [Endpoint.Rutracker] on first observe —
 *   a phantom endpoint production never lists. Consumer tests asserting
 *   "Rutracker is seeded" were Sixth-Law-clause-3 bluffs; rewritten in the
 *   same commit to the real contract.)
 * - Rejects duplicate additions with [IllegalStateException], matching
 *   Room's primary-key constraint violation.
 */
class TestEndpointsRepository : EndpointsRepository {
    private val mutableEndpoints = MutableStateFlow<List<Endpoint>>(emptyList())

    override suspend fun observeAll(): Flow<List<Endpoint>> = mutableEndpoints
        .asStateFlow()
        // Branch parity with EndpointsRepositoryImpl.observeAll, whose
        // mapLatest ends `.filterNot { it is Endpoint.Rutracker }`. Endpoint
        // .Rutracker is never listed to the user even if a legacy row exists.
        .map { endpoints -> endpoints.filterNot { it is Endpoint.Rutracker } }

    override suspend fun add(endpoint: Endpoint) {
        // Third-Law branch parity (LVA-011, 2026-06-09). Source of truth:
        // `lava.data.impl.repository.EndpointsRepositoryImpl.add()`, which
        // early-returns for Endpoint.Rutracker:
        //
        //     override suspend fun add(endpoint: Endpoint) {
        //         if (endpoint is Endpoint.Rutracker) return   // ← no-op, never persisted
        //         endpointDao.insert(endpoint.toEntity())
        //     }
        //
        // Operator directive 2026-05-12: direct rutracker.org is no longer a
        // user-addable endpoint — the real DAO never stores it. The previous
        // form of this fake STORED Endpoint.Rutracker (and would even raise a
        // duplicate-conflict for it), so a test asserting "adding Rutracker is
        // a no-op" would pass against the fake while exercising different
        // behaviour than production. That is a Third-Law (behavioural-
        // equivalence) bluff fake. The branch below restores parity: the fake
        // silently no-ops on Rutracker add, exactly as the real impl does.
        if (endpoint is Endpoint.Rutracker) return
        if (mutableEndpoints.value.contains(endpoint)) {
            throw IllegalStateException(
                "Endpoint $endpoint already exists (simulating Room PRIMARY KEY conflict)",
            )
        }
        mutableEndpoints.value = mutableEndpoints.value + endpoint
    }

    override suspend fun remove(endpoint: Endpoint) {
        mutableEndpoints.value = mutableEndpoints.value - endpoint
    }

    /**
     * Synchronous snapshot of the current endpoint list — for test assertions
     * that need to inspect persisted state without collecting the flow.
     *
     * Note: this returns the RAW backing list (any [add]s, unfiltered). It is
     * NOT routed through [observeAll]'s `.filterNot { it is Endpoint.Rutracker }`
     * emission filter — though, since [add] no-ops Rutracker (LVA-011), the raw
     * list cannot contain Rutracker in practice anyway.
     */
    fun currentEndpoints(): List<Endpoint> = mutableEndpoints.value
}

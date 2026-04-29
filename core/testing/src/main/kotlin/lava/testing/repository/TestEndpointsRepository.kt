package lava.testing.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onStart
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
 * Behaviour:
 * - Seeds [Endpoint.Rutracker] on first observation (SP-3.2: Endpoint.Proxy was removed)
 *   ONLY if the store is currently empty.
 * - Rejects duplicate additions with [IllegalStateException], matching
 *   Room's primary-key constraint violation.
 */
class TestEndpointsRepository : EndpointsRepository {
    private val mutableEndpoints = MutableStateFlow<List<Endpoint>>(emptyList())

    override suspend fun observeAll(): Flow<List<Endpoint>> = mutableEndpoints
        .asStateFlow()
        .onStart {
            if (mutableEndpoints.value.isEmpty()) {
                mutableEndpoints.value = listOf(Endpoint.Rutracker)
            }
        }

    override suspend fun add(endpoint: Endpoint) {
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
}

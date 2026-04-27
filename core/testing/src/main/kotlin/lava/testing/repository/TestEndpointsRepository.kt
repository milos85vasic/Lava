package lava.testing.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onStart
import lava.data.api.repository.EndpointsRepository
import lava.models.settings.Endpoint

/**
 * Behaviorally equivalent fake of [EndpointsRepositoryImpl].
 *
 * - Seeds default endpoints ([Endpoint.Proxy], [Endpoint.Rutracker]) on first observation,
 *   matching the real repository's `onStart { insertAll(defaultEndpoints) }` behavior.
 * - Rejects duplicate additions with [IllegalStateException], matching Room's
 *   primary-key constraint violation.
 */
class TestEndpointsRepository : EndpointsRepository {
    private val mutableEndpoints = MutableStateFlow<List<Endpoint>>(emptyList())
    private var seeded = false

    override suspend fun observeAll(): Flow<List<Endpoint>> = mutableEndpoints
        .asStateFlow()
        .onStart {
            if (!seeded) {
                seeded = true
                mutableEndpoints.value = listOf(Endpoint.Proxy, Endpoint.Rutracker)
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

    fun setEndpoints(endpoints: List<Endpoint>) {
        seeded = true
        mutableEndpoints.value = endpoints
    }
}

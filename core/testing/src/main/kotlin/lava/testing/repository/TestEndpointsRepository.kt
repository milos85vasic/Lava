package lava.testing.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import lava.data.api.repository.EndpointsRepository
import lava.models.settings.Endpoint

class TestEndpointsRepository : EndpointsRepository {
    private val mutableEndpoints = MutableStateFlow<List<Endpoint>>(emptyList())

    override suspend fun observeAll(): Flow<List<Endpoint>> = mutableEndpoints.asStateFlow()

    override suspend fun add(endpoint: Endpoint) {
        mutableEndpoints.value = mutableEndpoints.value + endpoint
    }

    override suspend fun remove(endpoint: Endpoint) {
        mutableEndpoints.value = mutableEndpoints.value - endpoint
    }

    fun setEndpoints(endpoints: List<Endpoint>) {
        mutableEndpoints.value = endpoints
    }
}

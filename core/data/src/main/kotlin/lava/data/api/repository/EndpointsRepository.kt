package lava.data.api.repository

import kotlinx.coroutines.flow.Flow
import lava.models.settings.Endpoint

interface EndpointsRepository {
    suspend fun observeAll(): Flow<List<Endpoint>>
    suspend fun add(endpoint: Endpoint)
    suspend fun remove(endpoint: Endpoint)
}

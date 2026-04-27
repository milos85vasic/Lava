package lava.data.api.repository

import lava.models.settings.Endpoint
import kotlinx.coroutines.flow.Flow

interface EndpointsRepository {
    suspend fun observeAll(): Flow<List<Endpoint>>
    suspend fun add(endpoint: Endpoint)
    suspend fun remove(endpoint: Endpoint)
}

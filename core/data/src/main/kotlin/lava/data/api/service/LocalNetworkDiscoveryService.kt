package lava.data.api.service

import kotlinx.coroutines.flow.Flow

interface LocalNetworkDiscoveryService {
    fun discover(): Flow<DiscoveredEndpoint>
}

data class DiscoveredEndpoint(
    val host: String,
    val port: Int,
    val name: String,
)

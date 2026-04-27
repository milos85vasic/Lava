package lava.testing.service

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import lava.data.api.service.DiscoveredEndpoint
import lava.data.api.service.LocalNetworkDiscoveryService

class TestLocalNetworkDiscoveryService : LocalNetworkDiscoveryService {
    private val channel = Channel<DiscoveredEndpoint>()

    override fun discover(): Flow<DiscoveredEndpoint> = channel.consumeAsFlow()

    suspend fun emit(endpoint: DiscoveredEndpoint) {
        channel.send(endpoint)
    }

    fun complete() {
        channel.close()
    }
}

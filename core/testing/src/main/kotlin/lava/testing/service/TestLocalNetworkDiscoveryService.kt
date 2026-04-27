package lava.testing.service

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import lava.data.api.service.DiscoveredEndpoint
import lava.data.api.service.LocalNetworkDiscoveryService

/**
 * Behaviorally equivalent fake of [LocalNetworkDiscoveryServiceImpl].
 *
 * Simulates asynchronous mDNS discovery by emitting [DiscoveredEndpoint] values
 * through a channel. Tests control the discovery timeline explicitly via [emit]
 * and [complete].
 *
 * To simulate a discovery that hangs (triggering the real use case's 5-second
 * [kotlinx.coroutines.withTimeoutOrNull]), simply do not call [complete].
 */
class TestLocalNetworkDiscoveryService : LocalNetworkDiscoveryService {
    private var channel: Channel<DiscoveredEndpoint> = Channel()

    override fun discover(): Flow<DiscoveredEndpoint> = channel.consumeAsFlow()

    suspend fun emit(endpoint: DiscoveredEndpoint) {
        channel.send(endpoint)
    }

    fun complete() {
        channel.close()
    }

    /**
     * Resets the channel so the service can be reused across tests.
     */
    fun reset() {
        channel.close()
        channel = Channel()
    }
}

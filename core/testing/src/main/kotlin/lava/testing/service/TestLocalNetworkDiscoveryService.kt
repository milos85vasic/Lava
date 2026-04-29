package lava.testing.service

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import lava.data.api.service.DiscoveredEndpoint
import lava.data.api.service.LocalNetworkDiscoveryService

/**
 * Behaviorally equivalent fake of [LocalNetworkDiscoveryServiceImpl].
 *
 * Anti-Bluff Pact Third Law alignment: the real impl uses
 * `callbackFlow` + `trySend(...)` — a non-suspending buffered
 * emission. The fake MUST mirror that.
 *
 * Forensic anchor (2026-04-29). The previous form used a rendezvous
 * `Channel()` which made `emit` suspend until a receiver parked,
 * forcing tests to wrap seeds in `launch { emit; … }`. The
 * `launch + cross-scheduler` interaction with `runTest`'s
 * auto-advance and `MainDispatcherRule`'s scheduler produced
 * "TurbineAssertionError: No value produced in 3s" failures (e.g.
 * `auto discovery on init emits OpenConnectionSettings when endpoint
 * found`). The rendezvous form was a Third-Law bluff fake — the real
 * impl's `trySend` never suspends.
 *
 * Behaviour:
 * - [emit] is non-suspending; emissions buffer in a [Channel.UNLIMITED]
 *   queue and flow to any subsequent collector.
 * - [complete] closes the channel; collectors see end-of-flow.
 * - [reset] (kept for back-compat) drains and replaces.
 */
class TestLocalNetworkDiscoveryService : LocalNetworkDiscoveryService {
    private var channel: Channel<DiscoveredEndpoint> = Channel(Channel.UNLIMITED)

    override fun discover(): Flow<DiscoveredEndpoint> = channel.consumeAsFlow()

    /**
     * Non-suspending — mirrors real impl's `trySend`. `suspend`
     * keyword is kept for source-compat; the call never actually
     * suspends with an UNLIMITED channel.
     */
    suspend fun emit(endpoint: DiscoveredEndpoint) {
        channel.send(endpoint)
    }

    fun complete() {
        channel.close()
    }

    /** Resets the channel so the service can be reused across tests. */
    fun reset() {
        channel.close()
        channel = Channel(Channel.UNLIMITED)
    }
}

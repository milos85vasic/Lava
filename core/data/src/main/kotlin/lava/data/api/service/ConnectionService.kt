package lava.data.api.service

import kotlinx.coroutines.flow.Flow
import lava.models.settings.Endpoint

interface ConnectionService {
    val networkUpdates: Flow<Boolean>

    /**
     * Whether the given [endpoint] is reachable from the device. Probes the
     * exact host:port pair the network layer would talk to for that variant
     * — see [lava.data.impl.service.ConnectionServiceImpl] for the per-variant
     * mapping. A green icon next to the endpoint in the Connections list is
     * driven by this method.
     */
    suspend fun isReachable(endpoint: Endpoint): Boolean

    /**
     * Whether the device has working Internet at all (used to distinguish
     * "endpoint blocked" from "no Internet" in the UI).
     */
    suspend fun isInternetReachable(): Boolean
}

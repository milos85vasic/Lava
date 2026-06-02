package lava.api.app.control

/**
 * The observable lifecycle state of the on-device Lava API.
 *
 * Emitted by [ApiEngineController.state]; consumed by the foreground Service
 * (to decide locks + notification content) and by the Compose UI (D-ui). The
 * states form the cycle:
 *
 *   Stopped → Starting → Running → Stopping → Stopped
 *
 * with [Error] reachable from Starting/Stopping when the engine fails.
 */
sealed interface ApiControlState {
    /** No server is running. The initial state. */
    data object Stopped : ApiControlState

    /** A start was requested; the engine has not yet reported Running. */
    data object Starting : ApiControlState

    /**
     * The embed is up and serving. All fields come from the real post-start
     * [lava.apiengine.ApiStatus] + the host's discovered LAN IPs — never
     * synthesized.
     *
     * @param url the reachable base URL a peer device hits (e.g.
     *   `https://192.168.1.5:8443`), built from the first LAN IP + the running
     *   port + the reported scheme.
     * @param lanIps every non-loopback IPv4 the host is reachable at.
     * @param port the running listener port.
     * @param requestCount inbound requests served since start.
     * @param authKey the in-effect Lava-Auth credential peers must present.
     * @param authFieldName the header peers send [authKey] in.
     */
    data class Running(
        val url: String,
        val lanIps: List<String>,
        val port: Int,
        val requestCount: Long,
        val authKey: String,
        val authFieldName: String,
    ) : ApiControlState

    /** A stop was requested; the engine has not yet confirmed shutdown. */
    data object Stopping : ApiControlState

    /** The last start/stop failed. [message] is the engine's error text. */
    data class Error(val message: String) : ApiControlState
}

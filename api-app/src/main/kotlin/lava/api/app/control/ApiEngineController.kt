package lava.api.app.control

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import lava.api.app.auth.ApiKeyStore
import lava.api.app.service.MdnsAdvertiser
import lava.apiengine.ApiConfig
import lava.apiengine.ApiEngine
import lava.apiengine.ApiStatus

/**
 * The Android-free lifecycle core of the on-device API.
 *
 * Owns the [ApiEngine] (the embedded lava-api-go server), the [MdnsAdvertiser]
 * (LAN discovery), and the [ApiKeyStore] (the persisted Lava-Auth credential).
 * Exposes [state] as the single source of truth the Service + UI observe.
 *
 * Deliberately has NO Android Service / Context dependency so it is unit-tested
 * with the real controller wired to [lava.apiengine.FakeApiEngine] + fakes for
 * the advertiser/keyStore — the Anti-Bluff load-bearing test
 * ([ApiEngineControllerTest]).
 *
 * @param engine the embedded API engine.
 * @param advertiser the mDNS advertiser; registered on Running, unregistered on
 *   Stopped.
 * @param keyStore supplies the persisted Lava-Auth key passed to the embed.
 * @param lanIpProvider returns the host's current non-loopback LAN IPv4s; the
 *   first is used to build the reachable URL.
 * @param sqlitePathProvider returns the absolute SQLite DB path the embed
 *   persists to (and stores its TLS material beside).
 * @param port the listener port (defaults to the embed's production default).
 */
class ApiEngineController(
    private val engine: ApiEngine,
    private val advertiser: MdnsAdvertiser,
    private val keyStore: ApiKeyStore,
    private val lanIpProvider: () -> List<String>,
    private val sqlitePathProvider: () -> String,
    private val port: Int = DEFAULT_PORT,
) {

    private val _state = MutableStateFlow<ApiControlState>(ApiControlState.Stopped)
    val state: StateFlow<ApiControlState> = _state.asStateFlow()

    /**
     * Starts the embed and, on success, registers the mDNS advertisement and
     * emits [ApiControlState.Running] built from the REAL post-start
     * [ApiStatus] + the host's LAN IPs. On engine failure emits
     * [ApiControlState.Error] and does NOT advertise.
     */
    suspend fun start() {
        // Idempotent: if the embed is already serving (Running) or a start is
        // in flight (Starting), do NOT bind the listener again — a second
        // bind fails with "listen 0.0.0.0:<port>; bind: address already in
        // use" and would crash a perfectly healthy engine. This is the
        // operator-reported bug from re-launching the API app (e.g. tapping
        // "Open Lava API app" from the client) while it was already running:
        // the relaunch MUST surface the live Running state, never re-start.
        // Stopped/Error fall through and (re)start normally.
        val current = _state.value
        if (current is ApiControlState.Running || current is ApiControlState.Starting) {
            return
        }

        _state.value = ApiControlState.Starting

        val key = keyStore.getOrCreate()
        val config = ApiConfig(
            port = port,
            sqlitePath = sqlitePathProvider(),
            authSharedKey = key,
            authFieldName = keyStore.fieldName,
        )

        engine.start(config).fold(
            onSuccess = { status -> onStarted(status) },
            onFailure = { error -> _state.value = ApiControlState.Error(error.message ?: "start failed") },
        )
    }

    /**
     * Stops the embed, unregisters the mDNS advertisement, and emits
     * [ApiControlState.Stopped]. On engine failure emits
     * [ApiControlState.Error]; the advertisement is still torn down so we do
     * not leave a stale record pointing at a dead listener.
     */
    suspend fun stop() {
        _state.value = ApiControlState.Stopping
        advertiser.unregister()

        engine.stop().fold(
            onSuccess = { _state.value = ApiControlState.Stopped },
            onFailure = { error -> _state.value = ApiControlState.Error(error.message ?: "stop failed") },
        )
    }

    /**
     * Restart always ends [ApiControlState.Running] regardless of the starting
     * state. When the embed is already stopped (e.g. the user tapped Stop then
     * Restart, or the notification Restart action fires after a Stop) there is
     * nothing to stop — calling [stop] would hit the embed's "no server running"
     * guard and surface a spurious [ApiControlState.Error], so we skip straight
     * to [start]. Only a stop that FAILS from a live state leaves an unknown
     * state worth halting on (that branch still returns Error and does NOT
     * start). Passes through [ApiControlState.Starting].
     */
    suspend fun restart() {
        if (_state.value !is ApiControlState.Stopped) {
            stop()
            if (_state.value is ApiControlState.Error) return
        }
        start()
    }

    private fun onStarted(status: ApiStatus) {
        val ips = lanIpProvider()
        advertiser.register(status.port)
        _state.value = ApiControlState.Running(
            url = buildUrl(status.scheme, ips, status.port),
            lanIps = ips,
            port = status.port,
            requestCount = status.requestCount,
            // The embed echoes the in-effect key; prefer it, fall back to the
            // key we supplied (they are identical for a Kotlin-supplied key).
            authKey = status.authKey ?: keyStore.getOrCreate(),
            authFieldName = status.authFieldName,
        )
    }

    private fun buildUrl(scheme: String, ips: List<String>, port: Int): String {
        val host = ips.firstOrNull() ?: LOOPBACK
        return "$scheme://$host:$port"
    }

    private companion object {
        // Only used when no LAN IP is resolvable yet (e.g. Wi-Fi not up); the
        // running embed binds 0.0.0.0 so loopback is always reachable on-device.
        const val LOOPBACK = "127.0.0.1"

        // The embed's production default listener port — read from ApiConfig's
        // own default (a synthetic sqlitePath is supplied only to construct it;
        // never used) so the literal lives in :core:apiengine, not here (§6.R).
        val DEFAULT_PORT = ApiConfig(sqlitePath = "").port
    }
}

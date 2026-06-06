package lava.apiengine

/**
 * Public Kotlin surface for the embedded lava-api-go server (Phase C).
 *
 * The embed is a Go c-shared library loaded via JNI ([LavaNative]); this
 * interface hides that native boundary behind ordinary suspend functions and
 * data classes. Consumers (the Android app, Phase D/E wiring) depend ONLY on
 * this file — never on [LavaNative] directly.
 *
 * Decoupled Reusable rationale: this is Lava-domain glue exposing the embed's
 * Start/Stop/Status lifecycle to Kotlin. It introduces no logic another
 * vasic-digital project would consume directly.
 */

/**
 * Configuration passed to [ApiEngine.start]. Serialized to the exact JSON shape
 * `internal/mobile.startConfig` parses (`bindAddr`, `port`, `sqlitePath`,
 * `authSharedKey`, `authFieldName`).
 *
 * @param bindAddr the IP the server binds. Defaults to the wildcard `0.0.0.0`
 *   so LAN peers can discover the on-device API (network exposure is the
 *   intended design per the embed spec; loopback-only would defeat the feature).
 * @param port the TCP port. Defaults to 8443, matching the production LAN
 *   listener.
 * @param sqlitePath REQUIRED absolute path to the SQLite database file. The
 *   self-signed TLS material is persisted alongside it.
 * @param authSharedKey the base64-UUID credential LAN clients present in the
 *   [authFieldName] header. When null, the embed GENERATES one and surfaces it
 *   via [ApiStatus.authKey] (the embed is authenticated by default — there is
 *   no unauthenticated mode).
 * @param authFieldName the HTTP header name the credential is read from.
 *   Defaults to `Lava-Auth`, matching the production gate.
 */
data class ApiConfig(
    val bindAddr: String = "0.0.0.0",
    val port: Int = 8443,
    val sqlitePath: String,
    val authSharedKey: String? = null,
    val authFieldName: String = "Lava-Auth",
)

/**
 * Snapshot of the embed's server state, mirroring the JSON document
 * `internal/mobile.Status()` returns.
 *
 * @param state `"running"` while a server is up, `"stopped"` otherwise.
 * @param bindAddr the address the running server is bound to (empty when
 *   stopped).
 * @param port the running server's port (0 when stopped).
 * @param requestCount total inbound requests served since the last start.
 * @param backend the storage backend in use (always `"sqlite"` for the embed).
 * @param version the embed's reported version name.
 * @param scheme the transport scheme (always `"https"`).
 * @param authEnabled whether the Lava-Auth gate is enforced (always true while
 *   running).
 * @param authFieldName the header clients present the credential in.
 * @param authKey the in-effect credential (base64-UUID blob) the host app can
 *   display to configure peer devices. Null when stopped or not surfaced.
 * @param sourceHash the 64-hex sha256 of the lava-api-go source codebase
 *   compiled into the running embed (`version.SourceHash`, ldflags-injected by
 *   `build-cshared.sh`). Empty when the `.so` was built without injection. The
 *   on-device sync Challenge asserts this equals `BuildConfig.LAVA_API_SOURCE_HASH`
 *   — equal hashes prove the embed contains EXACTLY the current lava-api-go
 *   codebase (no drift).
 */
data class ApiStatus(
    val state: String,
    val bindAddr: String,
    val port: Int,
    val requestCount: Long,
    val backend: String,
    val version: String,
    val scheme: String,
    val authEnabled: Boolean,
    val authFieldName: String,
    val authKey: String?,
    val sourceHash: String = "",
)

/**
 * Lifecycle controller for the embedded lava-api-go server.
 *
 * Exactly one server instance runs per process (enforced by the native side):
 * calling [start] while already running yields a failed [Result].
 */
interface ApiEngine {
    /**
     * Starts the embedded server with [config]. Returns a successful [Result]
     * holding the post-start [ApiStatus] (`state == "running"`), or a failed
     * [Result] carrying the native error (already-running, bad config, bind
     * failure, …).
     */
    suspend fun start(config: ApiConfig): Result<ApiStatus>

    /**
     * Gracefully stops the running server. Returns a failed [Result] when no
     * server is running (Stop is not a silent no-op, matching the embed).
     */
    suspend fun stop(): Result<Unit>

    /**
     * Returns the current [ApiStatus] without suspending. When stopped it
     * reports `state == "stopped"` with default zero/empty fields.
     */
    fun status(): ApiStatus
}

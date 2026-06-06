package lava.apiengine

/**
 * Behaviorally-equivalent in-memory [ApiEngine] for tests and previews
 * (Anti-Bluff Pact, Third Law).
 *
 * This fake reproduces the production [NativeApiEngine] / `internal/mobile`
 * BRANCHES that affect callers — it is NOT a "simpler than reality" stub:
 *
 *  - **Single instance per engine.** [start] while already running fails with
 *    an "already running" error, exactly as the Go side returns
 *    `"mobile: server already running …"`.
 *  - **Stop is not a silent no-op.** [stop] without a running server fails with
 *    a "no server running" error, matching `internal/mobile.Stop`.
 *  - **Status reflects last start/stop.** Before any start it reports
 *    `state == "stopped"`; after [start] it reports `state == "running"` with
 *    the started config's bindAddr/port; after [stop] it reverts to stopped.
 *  - **Auth is always on while running.** [start] surfaces an auth key (the
 *    config's [ApiConfig.authSharedKey] when provided, else a deterministic
 *    generated stand-in) and reports `authEnabled == true` — there is no
 *    unauthenticated mode, matching security-review finding 1.
 *  - **Configurable error injection.** [failWith] makes the next start/stop
 *    propagate a [Result.failure], so callers' error paths are exercised.
 *
 * If the real implementation grows a branch that affects callers, this fake
 * MUST grow a matching branch or document the limitation (Third Law).
 */
class FakeApiEngine(
    private val version: String = "fake-1.0.0",
) : ApiEngine {

    private val lock = Any()
    private var running = false
    private var current: ApiStatus = stoppedStatus()
    private var injectedError: Throwable? = null
    private var requestCount: Long = 0

    /**
     * Count of [start] calls that reached the bind decision. The real embed
     * (`internal/mobile`) is a single global server guarded by a Go mutex, so a
     * correct idempotent controller binds it exactly once even under concurrent
     * start() calls; a controller TOCTOU shows up here as a value > 1. The fake
     * is synchronized so this counter and `running` are race-free — the test
     * measures the CONTROLLER, not a race inside the double (Third Law).
     */
    @Volatile
    var startAttempts: Int = 0
        private set

    /** Injects an error to be returned by the NEXT [start] or [stop] call. */
    fun failWith(error: Throwable) {
        injectedError = error
    }

    /** Simulates inbound traffic so [status] reports a non-zero count. */
    fun recordRequests(count: Long) {
        if (running) {
            requestCount += count
            current = current.copy(requestCount = requestCount)
        }
    }

    override suspend fun start(config: ApiConfig): Result<ApiStatus> = synchronized(lock) {
        startAttempts++
        injectedError?.let {
            injectedError = null
            return Result.failure(it)
        }
        if (running) {
            return Result.failure(
                ApiEngineException(
                    "mobile: server already running on ${current.bindAddr}:${current.port} (Stop first)",
                ),
            )
        }
        running = true
        requestCount = 0
        val authKey = config.authSharedKey ?: "generated-${config.port}"
        current = ApiStatus(
            state = "running",
            bindAddr = config.bindAddr,
            port = config.port,
            requestCount = 0,
            backend = "sqlite",
            version = version,
            scheme = "https",
            authEnabled = true,
            authFieldName = config.authFieldName,
            authKey = authKey,
        )
        return Result.success(current)
    }

    override suspend fun stop(): Result<Unit> = synchronized(lock) {
        injectedError?.let {
            injectedError = null
            return Result.failure(it)
        }
        if (!running) {
            return Result.failure(ApiEngineException("mobile: no server running"))
        }
        running = false
        requestCount = 0
        current = stoppedStatus()
        return Result.success(Unit)
    }

    override fun status(): ApiStatus = current

    private fun stoppedStatus(): ApiStatus =
        ApiStatus(
            state = "stopped",
            bindAddr = "",
            port = 0,
            requestCount = 0,
            backend = "sqlite",
            version = version,
            scheme = "https",
            authEnabled = false,
            authFieldName = "",
            authKey = null,
        )
}

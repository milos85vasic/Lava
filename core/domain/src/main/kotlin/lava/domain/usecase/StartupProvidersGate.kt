package lava.domain.usecase

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cold-start provider-repopulation readiness gate (LVA-093).
 *
 * ## The race this closes
 *
 * [RepopulateProvidersOnStartupUseCase] runs off the main thread, fired from
 * `LavaApplication.onCreate()` inside a fire-and-forget
 * `startupScope.launch { ... }`. It MUST run detached like that — it does a
 * network round-trip and `Application.onCreate()` runs on the main thread, so
 * blocking there would risk an ANR. Before this gate existed, NOTHING awaited
 * that coroutine's completion: the UI became interactive immediately, so a
 * user who fired a search before the coroutine finished had their search
 * resolved against whatever [lava.tracker.registry.TrackerRegistry] state
 * existed at THAT instant — the compiled-in BUNDLED clients, not the dynamic
 * API-vended ones [RepopulateProvidersOnStartupUseCase.invoke] installs via
 * `TrackerRegistry.populateFrom(...)`. That is a silent wrong-tracker-client
 * bug: no crash, no error, just the wrong (or missing) provider silently
 * used for the search.
 *
 * ## What this is
 *
 * A process-wide (`@Singleton`) readiness flag. `false` from process start
 * until [markReady] is called exactly once by
 * [RepopulateProvidersOnStartupUseCase] when its cold-start repopulation
 * ATTEMPT concludes — successfully repopulated, gracefully degraded to the
 * bundled set, or (defensively) failed outright. [awaitReady] lets a
 * consumer (the search entry point,
 * [lava.search.result.SearchResultViewModel]) suspend until that conclusion,
 * bounded by [timeoutMs] so a genuinely slow/stuck network can never hang the
 * search UI forever (the same "never block the user indefinitely" posture
 * every other network wait in this codebase already follows — see
 * `SearchResultViewModel.SEARCH_TIMEOUT_MS`). After the timeout, the caller
 * proceeds with whatever the registry currently holds rather than freeze.
 */
@Singleton
class StartupProvidersGate @Inject constructor() {
    private val _ready = MutableStateFlow(false)

    /** `true` once the cold-start repopulation attempt has concluded. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /**
     * Called exactly once, by [RepopulateProvidersOnStartupUseCase], when its
     * cold-start attempt concludes — success OR graceful degrade-to-bundled.
     * Idempotent (setting an already-`true` [MutableStateFlow] to `true` is a
     * no-op).
     */
    fun markReady() {
        _ready.value = true
    }

    /**
     * Suspends until [ready] becomes `true`, or until [timeoutMs] elapses —
     * whichever happens first. NEVER throws. A timed-out wait is a
     * deliberate, documented degradation (the caller proceeds with whatever
     * the registry currently holds) rather than an indefinite hang.
     */
    suspend fun awaitReady(timeoutMs: Long = DEFAULT_AWAIT_TIMEOUT_MS) {
        if (_ready.value) return
        withTimeoutOrNull(timeoutMs) { ready.first { it } }
    }

    companion object {
        /**
         * Bound for [awaitReady]'s wait. Deliberately independent of (and
         * shorter than) the underlying catalogue-fetch network timeout (the
         * `@Named("lan") OkHttpClient` used by `ProviderCatalogRepository`
         * has a 30s connect/read timeout, and [RepopulateProvidersOnStartupUseCase]
         * now retries once on failure — up to ~60s worst case). 5s comfortably
         * covers the common case (a fast LAN round-trip) without risking the
         * search UI feeling stuck on a genuinely slow/unreachable network.
         */
        const val DEFAULT_AWAIT_TIMEOUT_MS = 5_000L
    }
}

package lava.tracker.client

import java.util.concurrent.atomic.AtomicReference

/**
 * Dynamic Provider Discovery (spec §4.2) — process-wide holder for the ACTIVE
 * lava-api-go base URL that [ApiBackedTrackerClient]s issue `/v1/{id}/{op}`
 * against.
 *
 * Why a holder rather than a constructor arg threaded through the registry:
 * the registry's [TrackerRegistry.setApiClientFactory] builder is installed once
 * at DI time, BEFORE the user has chosen an API instance. The active base URL is
 * a per-session runtime value selected at the onboarding ApiSelection step. The
 * onboarding/data layer (plan Phase 5) calls [set] with the probed base URL just
 * before it calls `populateFrom(catalogue)`.
 *
 * PENDING-INTEGRATION: this is the minimal seam that keeps `:core:tracker:client`
 * free of a dependency on the settings/endpoint layer (held work in other
 * modules). When the active-endpoint repository is wired, the onboarding layer
 * MAY instead call `setApiClientFactory` with the base URL captured in its own
 * closure and bypass this holder; both paths are supported.
 */
object ApiBaseUrlHolder {
    private val ref = AtomicReference<String?>(null)

    /** Sets the active API base URL (no trailing slash required). */
    fun set(apiBaseUrl: String) {
        ref.set(apiBaseUrl.trimEnd('/'))
    }

    /**
     * Current active API base URL.
     *
     * @throws IllegalStateException if a dynamic client is built before the
     *   onboarding layer set the active endpoint — fail loud rather than issue a
     *   request to a bogus host (§6.J: no silent wrong-target call).
     */
    fun current(): String =
        ref.get() ?: error(
            "ApiBaseUrlHolder.current() called before set() — the active " +
                "lava-api-go endpoint must be selected (onboarding ApiSelection) " +
                "before any ApiBackedTrackerClient is built.",
        )

    /** Test/teardown hook. */
    fun reset() {
        ref.set(null)
    }
}

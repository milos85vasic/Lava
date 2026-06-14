package lava.tracker.client

import java.util.concurrent.ConcurrentHashMap

/**
 * P0-1 (2026-06-14) — process-wide, PER-PROVIDER holder for the dynamic-auth
 * provider LOGIN SESSION token (e.g. RuTracker's `bb_session=…` cookie value,
 * Kinozal's session cookie). It is the SECOND credential — distinct from the
 * per-endpoint [ApiBaseUrlHolder] Lava-Auth key — that an auth-required provider
 * needs so its `/v1/{provider}/search|browse|topic|download` request reaches the
 * upstream tracker AUTHENTICATED rather than anonymous.
 *
 * **Why a per-provider holder (not a single value like the Lava-Auth key).**
 * The Lava-Auth key authenticates the CLIENT to the active lava-api-go endpoint
 * and is the same for every provider routed through that endpoint, so
 * [ApiBaseUrlHolder] keeps one value. The login session is the credential the
 * provider's upstream site issued and is DIFFERENT per provider (RuTracker's
 * session ≠ Kinozal's session). So this holder keys by `trackerId`.
 *
 * **Why a holder (the same rationale as [ApiBaseUrlHolder]).** The
 * registry's `setApiClientFactory` builder is installed once at DI time, before
 * any login. The session token is a per-session runtime value captured when the
 * user logs in (the login screen / provider-config). The builder reads
 * [tokenFor]\(descriptor.trackerId\) LATE-BOUND at `registry.get()` (build) time —
 * `ApiBackedTrackerClient` is rebuilt on every SDK call (see
 * `DefaultPluginRegistry.get` → `f.create`), so a token written AFTER login but
 * BEFORE the user searches IS picked up on the next request.
 *
 * **Server wire format (confirmed against `lava-api-go/internal/auth`).**
 * [ApiBackedTrackerClient] composes the `Auth-Token` header value as
 * `{providerId}:cookie:{sessionToken}` — exactly what `auth.ParseAuthToken`
 * (`multiprovider.go`) parses into `provider.Credentials{Type:"cookie",
 * CookieValue:sessionToken}`, which the rutracker provider's `credToCookie`
 * (`provider.go`) reads back as the cookie string.
 *
 * **Additive (anti-bluff §6.J).** A provider with NO stored session token
 * contributes NOTHING — `withAuth()` attaches no `Auth-Token` header, so the
 * no-auth path (Internet Archive, YTS curated) is byte-for-byte unchanged.
 */
object ProviderSessionTokenHolder {
    private val tokens = ConcurrentHashMap<String, String>()

    /**
     * Records the login session token for [trackerId]. A blank/empty token CLEARS
     * the entry (logout / failed login should not leave a stale token).
     */
    fun set(trackerId: String, sessionToken: String?) {
        if (sessionToken.isNullOrBlank()) {
            tokens.remove(trackerId)
        } else {
            tokens[trackerId] = sessionToken
        }
    }

    /** The login session token for [trackerId], or null if the user is not logged in. */
    fun tokenFor(trackerId: String): String? = tokens[trackerId]

    /** Clears the stored session token for [trackerId] (logout). */
    fun clear(trackerId: String) {
        tokens.remove(trackerId)
    }

    /** Test/teardown hook — clears all stored tokens. */
    fun reset() {
        tokens.clear()
    }
}

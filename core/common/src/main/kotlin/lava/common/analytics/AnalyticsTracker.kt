package lava.common.analytics

/**
 * Project-wide telemetry contract for events, errors, and warnings.
 *
 * §6.AC Comprehensive Non-Fatal Telemetry Mandate (added 2026-05-14):
 * every catch / error / fallback path in production code MUST surface
 * to telemetry via [recordNonFatal] (for throwables) or [recordWarning]
 * (for non-throwable unexpected situations) so the operator can
 * triage real-user failures remotely. Silent fallbacks are forbidden;
 * use `// no-telemetry: <reason>` to opt out explicitly when the
 * fallback truly is benign (e.g. cancellation paths during scope teardown).
 */
interface AnalyticsTracker {
    fun event(name: String, params: Map<String, String> = emptyMap())

    fun setUserId(userId: String?)

    fun setProperty(key: String, value: String?)

    /**
     * Record a non-fatal exception caught by application code. Routes to
     * Firebase Crashlytics's non-fatal channel under the hood. [context]
     * MUST include the §6.AC mandatory attributes
     * (`feature` / `module`, `operation`, `error_class`, `error_message`,
     * `screen`) when known.
     *
     * Sensitive values (credentials / tokens / cookies) MUST be redacted
     * before passing here per §6.H. The implementation may apply
     * additional automatic redaction to known attribute names but the
     * call site is responsible for not leaking secrets in the first place.
     */
    fun recordNonFatal(throwable: Throwable, context: Map<String, String> = emptyMap())

    /**
     * Record a non-throwable warning — an unexpected situation, degraded
     * path, fallback hit, or missing-resource event that the operator
     * needs to see in telemetry. Routes via Firebase Crashlytics's
     * `log()` channel + custom-keys (under the hood, a synthetic
     * `LavaNonFatalWarning(message)` is recorded so it surfaces in the
     * non-fatal feed alongside real exceptions).
     *
     * Examples:
     *   - cache miss that fell back to a slower path
     *   - mDNS discovery returned zero results when ≥1 was expected
     *   - a tracker descriptor's capability declared but feature returned null
     *   - a test that was skipped at runtime due to environment
     */
    fun recordWarning(message: String, context: Map<String, String> = emptyMap())

    fun log(message: String)

    object Events {
        const val LOGIN_SUBMIT = "lava_login_submit"
        const val LOGIN_SUCCESS = "lava_login_success"
        const val LOGIN_FAILURE = "lava_login_failure"
        const val SEARCH_SUBMIT = "lava_search_submit"
        const val BROWSE_CATEGORY = "lava_browse_category"
        const val VIEW_TOPIC = "lava_view_topic"
        const val DOWNLOAD_TORRENT = "lava_download_torrent"
        const val DOWNLOAD_TORRENT_FAILURE = "lava_download_torrent_failure"
        const val PROVIDER_SELECTED = "lava_provider_selected"
        const val ENDPOINT_DISCOVERED = "lava_endpoint_discovered"
    }

    object Params {
        // §6.AC mandatory non-fatal attributes
        const val FEATURE = "feature"
        const val MODULE = "module"
        const val OPERATION = "operation"
        const val ERROR_CLASS = "error_class"
        const val ERROR_MESSAGE = "error_message"
        const val SCREEN = "screen"

        // Domain-specific
        const val PROVIDER = "provider"
        const val QUERY = "query"
        const val CATEGORY_ID = "category_id"
        const val TOPIC_ID = "topic_id"
        const val ERROR = "error"
        const val ENDPOINT_KIND = "endpoint_kind"
    }
}

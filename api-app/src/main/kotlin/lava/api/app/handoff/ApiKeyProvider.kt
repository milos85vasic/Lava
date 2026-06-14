package lava.api.app.handoff

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import lava.api.app.ApiApplication
import lava.api.app.BuildConfig
import lava.api.app.control.ApiControlState

/**
 * Signature-permission [ContentProvider] that exposes the on-device API's
 * access key + live loopback port to the Lava client app.
 *
 * The provider returns ONE row `{ access_key, loopback_port }` when the engine
 * is running (both key and port are non-null), and an EMPTY cursor when the
 * engine is stopped or the values are unavailable. The client reads this after
 * the API app returns to it (Direction 1 handoff, spec §5).
 *
 * **Security (§6.H):** the read is guarded by
 * `android:readPermission="digital.vasic.lava.permission.READ_API_KEY"` declared
 * as `android:protectionLevel="signature"` in the manifest. Only an app signed
 * with the SAME signing certificate (i.e. the genuine Lava client) can read the
 * provider; third-party apps are denied at the OS level before [query] is called.
 *
 * **Test seam:** production Hilt wiring sets [keyProvider] from [ApiKeyStore]
 * and [portProvider] from [ApiEngineController]; tests call [withFakes] to
 * substitute controlled lambdas and [attachInfoForTest] to initialise the
 * provider without the Android ContentProvider lifecycle.
 *
 * §6.R: no hardcoded authority literals — the authority is a BuildConfig field
 * derived from the variant applicationId (release: `digital.vasic.lava.api.keyprovider`,
 * debug: `digital.vasic.lava.api.dev.keyprovider`).
 */
class ApiKeyProvider : ContentProvider() {

    // ── Injectable seams ──────────────────────────────────────────────────

    /**
     * Returns the current access key, or `null` when the engine is not running
     * or the key store is unavailable. Set by [withFakes] in tests; the
     * production default below resolves the process-wide holders LAZILY per call.
     */
    internal var keyProvider: () -> String? = { resolveRunningKey() }

    /**
     * Returns the current loopback port the engine is listening on, or `null`
     * when the engine is not running. Set by [withFakes] in tests; the
     * production default below resolves the holders LAZILY per call.
     */
    internal var portProvider: () -> Int? = { resolveRunningPort() }

    // ── ContentProvider lifecycle ─────────────────────────────────────────

    override fun onCreate(): Boolean {
        // 2026-06-14 SEARCH KEY-HANDOFF FIX — root cause of the operator-reported
        // "search does not work in any scenario". Every auth-gated /v1/{provider}
        // request 401'd because the client's ApiKeyClient.read() received an EMPTY
        // cursor from this provider.
        //
        // The previous code CACHED the key/port lambdas HERE, gated on
        // `ApiApplication.controllerHolder/keyStoreHolder != null`, with a comment
        // claiming "ContentProvider onCreate() is called AFTER Application.onCreate()".
        // That ordering is INVERTED: Android runs ContentProvider.onCreate() BEFORE
        // Application.onCreate(), and the holders are populated in
        // ApiApplication.onCreate() (lines 46-49). So the holders were ALWAYS null
        // here, the `if` was ALWAYS skipped, the lambdas stayed at `{ null }` for the
        // whole process, and this provider served an empty cursor forever. Public
        // routes (/providers, /health) need no key so they worked; only the
        // auth-gated search/browse/topic/download 401'd. On-device pinpoint:
        // .lava-ci-evidence/search-verification/2026-06-14-keyloss-pinpoint.md.
        //
        // Fix: do NOT cache here — the default lambdas (above) resolve the holders +
        // the Running state LAZILY on every query(), so the key is returned the
        // moment the engine is Running, independent of onCreate ordering.
        return true
    }

    /** Resolve the access key from the live holders, only while the engine is Running. */
    private fun resolveRunningKey(): String? {
        val controller = ApiApplication.controllerHolder ?: return null
        val keyStore = ApiApplication.keyStoreHolder ?: return null
        return if (controller.state.value is ApiControlState.Running) keyStore.getOrCreate() else null
    }

    /** Resolve the live loopback port from the controller, only while Running. */
    private fun resolveRunningPort(): Int? =
        (ApiApplication.controllerHolder?.state?.value as? ApiControlState.Running)?.port

    // ── Query — the only supported operation ─────────────────────────────

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(arrayOf(COL_ACCESS_KEY, COL_LOOPBACK_PORT))
        val key = keyProvider()
        val port = portProvider()
        if (key != null && port != null) {
            cursor.addRow(arrayOf(key, port))
        }
        return cursor
    }

    // ── Unsupported mutations ─────────────────────────────────────────────

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    // ── Test seams ────────────────────────────────────────────────────────

    /**
     * Sets test-supplied [keyProvider] and [portProvider] lambdas and returns
     * `this` for chaining. Called before [attachInfoForTest].
     */
    fun withFakes(
        keyProvider: () -> String?,
        portProvider: () -> Int?,
    ): ApiKeyProvider {
        this.keyProvider = keyProvider
        this.portProvider = portProvider
        return this
    }

    /**
     * Calls [attachInfo] with a synthetic [android.content.pm.ProviderInfo] so
     * Robolectric tests can invoke [query] without a real ContentProvider
     * lifecycle. Must be called after [withFakes] and before [query].
     */
    fun attachInfoForTest(context: Context) {
        val info = android.content.pm.ProviderInfo().apply {
            authority = BuildConfig.API_KEY_AUTHORITY
            exported = true
            readPermission = lava.applink.AppLinkContract.PERMISSION_READ_API_KEY
        }
        attachInfo(context, info)
    }

    /**
     * The content URI callers use to query this provider. Uses the variant-
     * aware authority from [BuildConfig.API_KEY_AUTHORITY].
     */
    fun contentUri(): Uri = Uri.parse("content://${BuildConfig.API_KEY_AUTHORITY}")

    companion object {
        const val COL_ACCESS_KEY = "access_key"
        const val COL_LOOPBACK_PORT = "loopback_port"
    }
}

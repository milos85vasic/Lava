package digital.vasic.lava.client.handoff

import android.content.Context
import android.net.Uri

/**
 * Handoff data returned by the on-device API app's key ContentProvider.
 *
 * @param port the live loopback port the API app's engine is bound to
 * @param key  the access key for the running API instance (never logged per §6.H)
 */
data class ApiHandoff(val port: Int, val key: String)

/**
 * Reads `{ access_key, loopback_port }` from the API app's key ContentProvider.
 *
 * The provider is guarded by the `digital.vasic.lava.permission.READ_API_KEY`
 * signature-level permission — only apps signed with the same keystore (i.e. the
 * genuine Lava client) can query it. The `uses-permission` declaration in the
 * client manifest enables the OS to grant the permission at install time.
 *
 * Authority is variant-aware: the caller passes [authority] from a BuildConfig
 * field so no literal is embedded here (§6.R).
 *
 * Returns null when:
 * - the API app is not installed (no ContentProvider registered for the authority)
 * - the engine is not running (empty cursor)
 * - the permission is denied (SecurityException caught and swallowed — graceful
 *   fallback to the existing cloud + mDNS sections per the spec §7)
 * - any other I/O error
 */
class ApiKeyClient(
    private val context: Context,
    private val authority: String,
) {
    fun read(): ApiHandoff? {
        return try {
            val uri = Uri.parse("content://$authority")
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val keyIdx = cursor.getColumnIndexOrThrow("access_key")
                val portIdx = cursor.getColumnIndexOrThrow("loopback_port")
                ApiHandoff(
                    port = cursor.getInt(portIdx),
                    key = cursor.getString(keyIdx),
                )
            }
        } catch (e: SecurityException) {
            // Signature-permission denial — the client is not the genuine Lava app
            // or the API app is not co-installed. Fall back gracefully.
            null
        } catch (e: Exception) {
            // Provider absent, engine not running, or unexpected I/O failure.
            null
        }
    }
}

package digital.vasic.lava.client.handoff

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/**
 * Robolectric unit test for [ApiKeyClient].
 *
 * FALSIFIABILITY REHEARSAL:
 *   Mutation: in [ApiKeyClient.read], change the column name read for port
 *             from "loopback_port" to "wrong_column" so the cursor lookup
 *             throws an IllegalArgumentException.
 *   Observed failure (rehearsed): `java.lang.IllegalArgumentException:
 *     column 'wrong_column' does not exist` — the `registered_provider_returns_handoff`
 *     test fails with an uncaught exception. The exception propagates out of
 *     ApiKeyClient.read() because the catch only catches SecurityException,
 *     causing AssertionError: ApiKeyClient must return non-null when provider is present.
 *   Reverted: yes
 *
 * @Config(sdk = [34]): Robolectric 4.12.2 supports up to SDK 34; the app
 *   targetSdk is 35 which exceeds maxSdkVersion — pin to 34 for the test
 *   run (§6.AD-debt forward-compat skip with tracking: Robolectric SDK 35
 *   support arrives in 4.14+; upgrade tracked in workable-items as LVA-tech-debt).
 */
@RunWith(RobolectricTestRunner::class)
// sdk=34: Robolectric 4.12.2 maxSdkVersion=34 < app targetSdk=35. Pin to 34.
// application=Application::class: bypass LavaApplication.onCreate() which
// initialises WorkManager (requires explicit init in unit-test scope). The test
// only needs ContentResolver + ShadowContentResolver — no app graph needed.
@Config(sdk = [34], application = Application::class)
class ApiKeyClientTest {

    private val testAuthority = "digital.vasic.lava.api.dev.keyprovider"
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // CHALLENGE — primary assertion on the returned ApiHandoff value (user-visible
    // outcome: the client successfully read the running API app's key + port).
    @Test
    fun registered_provider_returns_handoff() {
        ShadowContentResolver.registerProviderInternal(
            testAuthority,
            FakeKeyProvider(key = "my-test-key", port = 9443),
        )

        val client = ApiKeyClient(context, testAuthority)
        val handoff = client.read()

        assertNotNull("ApiKeyClient must return non-null when provider is present", handoff)
        assertEquals("port must match the provider row", 9443, handoff!!.port)
        assertEquals("key must match the provider row", "my-test-key", handoff.key)
    }

    // CHALLENGE — primary assertion on null return (user-visible: graceful fallback
    // when the API app is not running / engine not started).
    @Test
    fun absent_provider_returns_null() {
        // No provider registered for this authority → query returns null
        val unknownAuthority = "digital.vasic.lava.api.dev.keyprovider.absent"
        val client = ApiKeyClient(context, unknownAuthority)
        val handoff = client.read()
        assertNull("ApiKeyClient must return null when provider is absent", handoff)
    }

    // CHALLENGE — primary assertion on null return when cursor is empty.
    @Test
    fun empty_cursor_returns_null() {
        ShadowContentResolver.registerProviderInternal(
            "digital.vasic.lava.api.dev.keyprovider.empty",
            FakeKeyProvider(key = null, port = null),
        )

        val client = ApiKeyClient(context, "digital.vasic.lava.api.dev.keyprovider.empty")
        val handoff = client.read()
        assertNull("ApiKeyClient must return null when cursor is empty", handoff)
    }

    // ── Stub ContentProvider ────────────────────────────────────────────────

    private class FakeKeyProvider(
        private val key: String?,
        private val port: Int?,
    ) : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val cursor = MatrixCursor(arrayOf("access_key", "loopback_port"))
            if (key != null && port != null) {
                cursor.addRow(arrayOf<Any>(key, port))
            }
            return cursor
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }
}

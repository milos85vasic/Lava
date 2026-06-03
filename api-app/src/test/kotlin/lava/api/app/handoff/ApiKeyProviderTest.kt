package lava.api.app.handoff

import android.app.Application
import android.content.ContentValues
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric unit test for [ApiKeyProvider].
 *
 * PRIMARY assertions are on the cursor contents — the exact `{access_key,
 * loopback_port}` values a real Lava client would read via ContentResolver.
 * The test uses the [ApiKeyProvider.withFakes] seam so no Android ContentProvider
 * lifecycle plumbing is needed: [attachInfoForTest] initialises the provider
 * against the Robolectric application context.
 *
 * `application = Application::class` overrides the default Robolectric behaviour
 * that instantiates [lava.api.app.ApiApplication] (a `@HiltAndroidApp` subclass).
 * That class injects [lava.api.app.auth.ApiKeyStore] via Hilt which internally
 * calls [androidx.security.crypto.EncryptedSharedPreferences] / AndroidKeyStore —
 * unavailable in the JVM Robolectric environment and irrelevant for these tests.
 * Using a plain [Application] keeps this test fast and focused on [ApiKeyProvider]
 * behaviour, not Hilt wiring (which is covered by instrumented Challenge Tests).
 *
 * FALSIFIABILITY REHEARSAL (documented in commit Bluff-Audit):
 *   Mutation: make [ApiKeyProvider.query] always return an empty cursor (remove
 *   the "if key != null && port != null" branch, always emit empty).
 *   Observed: `running_engine_exposes_key_and_port` fails with
 *     "expected:<1> but was:<0>"
 *   Reverted: yes
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33], application = Application::class)
class ApiKeyProviderTest {

    @Test
    fun running_engine_exposes_key_and_port() {
        val provider = ApiKeyProvider().withFakes(
            keyProvider = { "test-key-123" },
            portProvider = { 8443 },
        )
        provider.attachInfoForTest(ApplicationProvider.getApplicationContext())

        val cursor = provider.query(provider.contentUri(), null, null, null, null)!!

        assertEquals(1, cursor.count)
        cursor.moveToFirst()
        assertEquals("test-key-123", cursor.getString(cursor.getColumnIndexOrThrow("access_key")))
        assertEquals(8443, cursor.getInt(cursor.getColumnIndexOrThrow("loopback_port")))
    }

    @Test
    fun stopped_engine_exposes_empty_cursor() {
        val provider = ApiKeyProvider().withFakes(
            keyProvider = { null },
            portProvider = { null },
        )
        provider.attachInfoForTest(ApplicationProvider.getApplicationContext())

        val cursor = provider.query(provider.contentUri(), null, null, null, null)!!

        assertEquals(0, cursor.count)
    }

    @Test
    fun key_present_but_port_absent_exposes_empty_cursor() {
        val provider = ApiKeyProvider().withFakes(
            keyProvider = { "test-key-123" },
            portProvider = { null },
        )
        provider.attachInfoForTest(ApplicationProvider.getApplicationContext())

        val cursor = provider.query(provider.contentUri(), null, null, null, null)!!

        assertEquals(0, cursor.count)
    }

    @Test
    fun unsupported_mutations_are_rejected() {
        val provider = ApiKeyProvider().withFakes(
            keyProvider = { "test-key-123" },
            portProvider = { 8443 },
        )
        provider.attachInfoForTest(ApplicationProvider.getApplicationContext())
        val uri = provider.contentUri()

        // insert, update, delete must return null / 0 per the ContentProvider contract.
        assertNull(provider.getType(uri))
        assertNull(provider.insert(uri, ContentValues()))
        assertEquals(0, provider.update(uri, ContentValues(), null, null))
        assertEquals(0, provider.delete(uri, null, null))
    }
}

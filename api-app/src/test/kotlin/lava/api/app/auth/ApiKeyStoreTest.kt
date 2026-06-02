package lava.api.app.auth

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** JVM-side encoder yielding the SAME alphabet as android.util.Base64 NO_WRAP. */
private val jvmStdBase64 = KeyEncoder { Base64.getEncoder().encodeToString(it) }

private fun newStore(prefs: SharedPreferences) =
    ApiKeyStoreImpl(prefs, "Lava-Auth", jvmStdBase64)

/**
 * Verifies the per-install auth-key store generates, persists, and reuses a key
 * in the EXACT wire shape the Go embed accepts.
 *
 * The embed (`internal/auth.NewMiddleware` + `internal/mobile.buildAuthConfig`)
 * does `base64.StdEncoding.DecodeString(header)` then expects a non-empty
 * 16-byte UUID blob. So a valid key here MUST decode (standard alphabet,
 * padded) to exactly 16 bytes — which is what we assert as the PRIMARY check.
 *
 * Uses an in-memory [SharedPreferences] fake (no Android, no Robolectric) so
 * this is a fast JVM unit test. The persistence semantics it exercises (get
 * returns stored value; put + commit; reuse across instances) match the real
 * EncryptedSharedPreferences contract.
 *
 * Bluff-Audit (recorded in commit body): changing UUID_LEN to 8 makes
 * `generated key decodes to 16 bytes the embed accepts` fail with
 * "expected 16 but was 8"; making getOrCreate always regenerate makes
 * `key is reused across instances` fail with two different keys.
 */
class ApiKeyStoreTest {

    /** Minimal in-memory SharedPreferences honouring get/put/apply semantics. */
    private class FakeSharedPreferences : SharedPreferences {
        val map = HashMap<String, Any?>()

        override fun getString(key: String?, defValue: String?): String? =
            (map[key] as? String) ?: defValue

        override fun edit(): SharedPreferences.Editor = FakeEditor(this)

        override fun getAll(): MutableMap<String, *> = map
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST")
            (map[key] as? MutableSet<String>)
                ?: defValues
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private class FakeEditor(private val prefs: FakeSharedPreferences) : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private val removals = HashSet<String>()
        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            pending[key!!] = value
            return this
        }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            pending[key!!] = values
            return this
        }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            pending[key!!] = value
            return this
        }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            pending[key!!] = value
            return this
        }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            pending[key!!] = value
            return this
        }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            pending[key!!] = value
            return this
        }
        override fun remove(key: String?): SharedPreferences.Editor {
            removals += key!!
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            prefs.map.clear()
            return this
        }
        override fun commit(): Boolean {
            flush()
            return true
        }
        override fun apply() { flush() }
        private fun flush() {
            removals.forEach { prefs.map.remove(it) }
            prefs.map.putAll(pending)
        }
    }

    @Test
    fun `generated key decodes to 16 bytes the embed accepts`() {
        val store = newStore(FakeSharedPreferences())

        val key = store.getOrCreate()

        assertFalse("key must not be blank", key.isBlank())
        // PRIMARY: standard-alphabet base64 decodes to exactly the embed's
        // uuidLen (16). This is the contract internal/auth.NewMiddleware enforces.
        val decoded = Base64.getDecoder().decode(key)
        // The embed (internal/auth.NewMiddleware + internal/mobile.uuidLen=16)
        // requires a 16-byte UUID blob. Assert against the literal embed
        // contract, NOT the module's own UUID_LEN constant — so a mutation of
        // that constant is caught here rather than silently tracking it.
        assertEquals("embed expects a 16-byte UUID blob", 16, decoded.size)
        // The module's own constant must also equal the embed's width.
        assertEquals(16, ApiKeyStoreImpl.UUID_LEN)
    }

    @Test
    fun `key is reused across instances backed by the same prefs`() {
        val prefs = FakeSharedPreferences()
        val first = newStore(prefs).getOrCreate()
        // A second store over the SAME persisted prefs (simulating a later app
        // launch) MUST return the SAME key so peers stay configured.
        val second = newStore(prefs).getOrCreate()
        assertEquals(first, second)
    }

    @Test
    fun `successive calls on one instance return the same key`() {
        val store = newStore(FakeSharedPreferences())
        assertEquals(store.getOrCreate(), store.getOrCreate())
    }

    @Test
    fun `distinct installs generate distinct keys`() {
        // Two independent stores (two installs) generate cryptographically
        // distinct keys — the key is random, not a constant.
        val a = newStore(FakeSharedPreferences()).getOrCreate()
        val b = newStore(FakeSharedPreferences()).getOrCreate()
        assertNotEquals(a, b)
    }

    @Test
    fun `field name is the configured header`() {
        val store = newStore(FakeSharedPreferences())
        assertEquals("Lava-Auth", store.fieldName)
        assertTrue(ApiKeyStoreImpl.DEFAULT_FIELD_NAME == "Lava-Auth")
    }
}

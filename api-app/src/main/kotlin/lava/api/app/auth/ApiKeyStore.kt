package lava.api.app.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-install store for the Lava-Auth credential the embedded API enforces.
 *
 * The credential is the production wire shape the Go embed accepts: the base64
 * (standard alphabet, padded) encoding of a random 16-byte UUID blob. This is
 * EXACTLY what `internal/mobile.generateAuthKey` mints and what
 * `internal/auth.NewMiddleware` base64-decodes from the Lava-Auth header before
 * HMAC-ing — so a key produced here is honoured by the embed when passed via
 * [lava.apiengine.ApiConfig.authSharedKey].
 *
 * On first run [getOrCreate] generates a fresh key with [SecureRandom] and
 * persists it; every subsequent call (this run or a later install lifetime)
 * returns the SAME key so peer devices configured against it keep working.
 *
 * §6.H: the key is a credential. It is NEVER written to a log line, never
 * concatenated into an exception message, never returned from [toString]. It
 * lives encrypted at rest (EncryptedSharedPreferences) and is surfaced to the
 * user only through the deliberate UI path (D-ui) that displays it.
 */
interface ApiKeyStore {
    /**
     * Returns the persisted Lava-Auth key, generating + persisting one on the
     * first call. The returned value is base64(16 random bytes).
     */
    fun getOrCreate(): String

    /** The HTTP header name the embed reads the credential from. */
    val fieldName: String
}

/**
 * Encodes a 16-byte UUID blob into the embed's wire credential shape.
 *
 * The default ([ANDROID_STD_BASE64]) uses [android.util.Base64] with
 * `NO_WRAP` — the standard alphabet, padded, single line — which is byte-for-
 * byte what Go's `base64.StdEncoding.EncodeToString` produces and what
 * `internal/auth.NewMiddleware` decodes. It is available on all API levels
 * (minSdk 21). Injectable so pure-JVM unit tests can substitute a
 * `java.util.Base64`-backed encoder that yields the IDENTICAL alphabet.
 */
fun interface KeyEncoder {
    fun encode(blob: ByteArray): String
}

@Singleton
class ApiKeyStoreImpl(
    private val prefs: SharedPreferences,
    override val fieldName: String,
    private val encoder: KeyEncoder = ANDROID_STD_BASE64,
) : ApiKeyStore {

    @Inject
    constructor(prefs: SharedPreferences, fieldName: String) : this(prefs, fieldName, ANDROID_STD_BASE64)

    @Synchronized
    override fun getOrCreate(): String {
        prefs.getString(KEY_AUTH, null)?.let { return it }
        val generated = generateKey()
        prefs.edit().putString(KEY_AUTH, generated).apply()
        return generated
    }

    private fun generateKey(): String {
        val blob = ByteArray(UUID_LEN)
        try {
            SecureRandom().nextBytes(blob)
            return encoder.encode(blob)
        } finally {
            // §6.H: zeroise the plaintext blob; the base64 String leaves the
            // process only via the deliberate display/config path.
            blob.fill(0)
        }
    }

    companion object {
        /**
         * Matches the embed's `uuidLen` (internal/mobile.go) — a 16-byte
         * (128-bit) UUID blob is the production credential width.
         */
        const val UUID_LEN = 16

        /** Default Lava-Auth header name, matching the embed's default. */
        const val DEFAULT_FIELD_NAME = "Lava-Auth"

        private const val KEY_AUTH = "lava_api_auth_key"
        private const val PREFS_FILE = "lava_api_auth"

        /**
         * Default encoder: [android.util.Base64] NO_WRAP (standard alphabet,
         * padded). Matches Go's `base64.StdEncoding`. Available on all API
         * levels, so it is the production runtime choice.
         */
        val ANDROID_STD_BASE64 = KeyEncoder { blob -> Base64.encodeToString(blob, Base64.NO_WRAP) }

        /**
         * Builds an [ApiKeyStoreImpl] backed by [EncryptedSharedPreferences].
         * Used by DI wiring (D-ui) and producible from a real [Context] in
         * instrumented tests; pure-JVM unit tests construct [ApiKeyStoreImpl]
         * directly with an in-memory [SharedPreferences] fake instead.
         */
        fun create(
            context: Context,
            fieldName: String = DEFAULT_FIELD_NAME,
        ): ApiKeyStore {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            return ApiKeyStoreImpl(prefs, fieldName)
        }
    }
}

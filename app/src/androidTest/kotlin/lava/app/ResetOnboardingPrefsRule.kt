package lava.app

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

class ResetOnboardingPrefsRule : TestRule {

    override fun apply(base: Statement, description: Description): Statement {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // Use EncryptedSharedPreferences with commit() to synchronously flush
        // to disk before the compose rule launches the activity. The production
        // edit {} extension uses apply() (async), which means the disk write
        // races with EncryptedSharedPreferences.create() in the activity's
        // onCreate — stale encrypted data with onboardingComplete=true is read,
        // the Welcome screen never shows, and the test times out.
        // commit() guarantees the cleared encrypted data is on disk before the
        // activity reads it.
        try {
            val mainKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            listOf("settings", "signaled_auth", "account").forEach { name ->
                EncryptedSharedPreferences.create(
                    context,
                    name,
                    mainKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                ).edit().clear().commit()
            }
        } catch (_: Exception) {
            // Fallback: plain SharedPreferences for environments where the
            // Android Keystore / MasterKey is unavailable, or the encrypted
            // file is too corrupted for EncryptedSharedPreferences.create()
            // to succeed. commit() ensures the write completes synchronously.
            listOf("settings", "signaled_auth", "account").forEach { name ->
                context.getSharedPreferences(name, Context.MODE_PRIVATE)
                    .edit().clear().commit()
            }
        }

        return object : Statement() {
            override fun evaluate() {
                base.evaluate()
            }
        }
    }
}

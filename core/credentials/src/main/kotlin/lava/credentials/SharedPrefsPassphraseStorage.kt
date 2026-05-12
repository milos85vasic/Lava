package lava.credentials

import android.content.SharedPreferences
import android.util.Base64
import androidx.core.content.edit
import lava.credentials.di.CredentialsModule
import javax.inject.Inject
import javax.inject.Named

class SharedPrefsPassphraseStorage @Inject constructor(
    @Named(CredentialsModule.PASSPHRASE_PREFS) private val prefs: SharedPreferences,
) : PassphraseManager.Storage {
    override fun saveSalt(b: ByteArray) {
        prefs.edit { putString(SALT_KEY, Base64.encodeToString(b, Base64.NO_WRAP)) }
    }
    override fun getSalt(): ByteArray? =
        prefs.getString(SALT_KEY, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
    override fun saveVerifier(b: ByteArray) {
        prefs.edit { putString(VERIFIER_KEY, Base64.encodeToString(b, Base64.NO_WRAP)) }
    }
    override fun getVerifier(): ByteArray? =
        prefs.getString(VERIFIER_KEY, null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    private companion object {
        const val SALT_KEY = "credentials_kdf_salt_v1"
        const val VERIFIER_KEY = "credentials_verifier_v1"
    }
}

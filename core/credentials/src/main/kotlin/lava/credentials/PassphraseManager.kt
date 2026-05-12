package lava.credentials

import lava.credentials.crypto.CredentialsCrypto
import lava.credentials.session.CredentialsKeyHolder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PassphraseManager @Inject constructor(
    private val storage: Storage,
    private val keyHolder: CredentialsKeyHolder,
) {
    interface Storage {
        fun saveSalt(b: ByteArray)
        fun getSalt(): ByteArray?
        fun saveVerifier(b: ByteArray)
        fun getVerifier(): ByteArray?
    }

    fun isInitialized(): Boolean = storage.getSalt() != null && storage.getVerifier() != null

    suspend fun firstTimeSetup(passphrase: String) {
        val salt = CredentialsCrypto.newSalt()
        val key = CredentialsCrypto.deriveKey(passphrase, salt)
        storage.saveSalt(salt)
        storage.saveVerifier(CredentialsCrypto.makeVerifier(key))
        keyHolder.unlock(key)
    }

    suspend fun unlock(passphrase: String): Boolean {
        val salt = storage.getSalt() ?: return false
        val verifier = storage.getVerifier() ?: return false
        val key = CredentialsCrypto.deriveKey(passphrase, salt)
        if (!CredentialsCrypto.checkVerifier(key, verifier)) return false
        keyHolder.unlock(key)
        return true
    }
}

package lava.credentials

import kotlinx.coroutines.runBlocking
import lava.credentials.session.CredentialsKeyHolder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PassphraseManagerTest {
    private class FakeStore : PassphraseManager.Storage {
        private var saltBytes: ByteArray? = null
        private var verifierBytes: ByteArray? = null
        override fun saveSalt(b: ByteArray) { saltBytes = b }
        override fun getSalt(): ByteArray? = saltBytes
        override fun saveVerifier(b: ByteArray) { verifierBytes = b }
        override fun getVerifier(): ByteArray? = verifierBytes
    }

    @Test fun `firstTimeSetup persists salt and verifier`() = runBlocking {
        val store = FakeStore()
        val holder = CredentialsKeyHolder()
        val m = PassphraseManager(store, holder)
        m.firstTimeSetup("pw")
        assertTrue(store.getSalt() != null && store.getVerifier() != null)
        assertTrue(holder.isUnlocked())
    }

    @Test fun `unlock right passphrase succeeds`() = runBlocking {
        val store = FakeStore()
        val holder = CredentialsKeyHolder()
        val m = PassphraseManager(store, holder)
        m.firstTimeSetup("pw")
        holder.lock()
        assertTrue(m.unlock("pw"))
        assertTrue(holder.isUnlocked())
    }

    @Test fun `unlock wrong passphrase fails and stays locked`() = runBlocking {
        val store = FakeStore()
        val holder = CredentialsKeyHolder()
        val m = PassphraseManager(store, holder)
        m.firstTimeSetup("pw")
        holder.lock()
        assertFalse(m.unlock("nope"))
        assertFalse(holder.isUnlocked())
    }
}

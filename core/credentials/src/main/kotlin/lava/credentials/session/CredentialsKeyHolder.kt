package lava.credentials.session

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CredentialsKeyHolder @Inject constructor() {
    private val ref = AtomicReference<ByteArray?>(null)

    fun unlock(key: ByteArray) { ref.set(key) }

    fun lock() {
        val k = ref.getAndSet(null)
        k?.fill(0)
    }

    fun isUnlocked(): Boolean = ref.get() != null

    fun getOrNull(): ByteArray? = ref.get()

    fun require(): ByteArray = ref.get()
        ?: error("credentials key holder is locked — prompt user for passphrase first")
}

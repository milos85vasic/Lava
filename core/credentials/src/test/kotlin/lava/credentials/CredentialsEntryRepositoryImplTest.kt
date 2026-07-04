package lava.credentials

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import lava.credentials.crypto.CredentialsCrypto
import lava.credentials.model.CredentialSecret
import lava.credentials.model.CredentialsEntry
import lava.database.dao.CredentialsEntryDao
import lava.database.entity.CredentialsEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialsEntryRepositoryImplTest {
    private class FakeDao : CredentialsEntryDao {
        val flow = MutableStateFlow<List<CredentialsEntryEntity>>(emptyList())
        override fun observeAll() = flow
        override suspend fun get(id: String) = flow.value.firstOrNull { it.id == id && it.deletedAt == null }
        override suspend fun upsert(entity: CredentialsEntryEntity) {
            flow.value = (flow.value.filterNot { it.id == entity.id } + entity)
        }

        // SP-4 Phase G — soft-delete + hard-delete both supported.
        override suspend fun softDelete(id: String, deletedAt: Long) {
            flow.value = flow.value.map { if (it.id == id) it.copy(deletedAt = deletedAt) else it }
        }
        override suspend fun delete(id: String) { flow.value = flow.value.filterNot { it.id == id } }
    }

    private val salt = ByteArray(32) { it.toByte() }
    private val key = CredentialsCrypto.deriveKey("pass", salt)

    @Test
    fun `upsert encrypts and decrypts on read`() = runBlocking {
        val dao = FakeDao()
        val repo = CredentialsEntryRepositoryImpl(dao, { key })
        val entry = CredentialsEntry(
            id = "id-1",
            displayName = "My creds",
            secret = CredentialSecret.UsernamePassword("alice", "p"),
            createdAtUtc = 1,
            updatedAtUtc = 2,
        )
        repo.upsert(entry)
        val read = repo.list()
        assertEquals(1, read.size)
        assertEquals("My creds", read[0].displayName)
        assertEquals(CredentialSecret.UsernamePassword("alice", "p"), read[0].secret)
    }

    @Test
    fun `get returns null for unknown id`() = runBlocking {
        val dao = FakeDao()
        val repo = CredentialsEntryRepositoryImpl(dao, { key })
        assertNull(repo.get("nope"))
    }

    @Test
    fun `delete removes the row`() = runBlocking {
        val dao = FakeDao()
        val repo = CredentialsEntryRepositoryImpl(dao, { key })
        val entry = CredentialsEntry(
            id = "id-2",
            displayName = "X",
            secret = CredentialSecret.ApiKey("k"),
            createdAtUtc = 1,
            updatedAtUtc = 2,
        )
        repo.upsert(entry)
        assertNotNull(repo.get("id-2"))
        repo.delete("id-2")
        assertNull(repo.get("id-2"))
    }

    @Test
    fun `round-trips all four secret types`() = runBlocking {
        val dao = FakeDao()
        val repo = CredentialsEntryRepositoryImpl(dao, { key })
        val secrets = listOf(
            CredentialSecret.UsernamePassword("u", "p"),
            CredentialSecret.ApiKey("ak"),
            CredentialSecret.BearerToken("bt"),
            CredentialSecret.CookieSession("cs"),
        )
        secrets.forEachIndexed { i, s ->
            repo.upsert(CredentialsEntry("id-$i", "n-$i", s, 1, 2))
        }
        val read = repo.list().sortedBy { it.id }
        assertEquals(secrets, read.map { it.secret })
    }

    /**
     * Regression test for Crashlytics FATAL 58a1335272bc4ee06595bda6302a670a.
     *
     * When the [CredentialsKeyHolder] is locked (key == null) and the Room DAO
     * emits rows (e.g. during search while the credentials screen was never
     * opened), [CredentialsEntryRepositoryImpl.decode] previously threw
     * [IllegalStateException] ("credentials key holder is locked") inside the
     * [kotlinx.coroutines.flow.Flow.map] operator.  That exception propagated
     * onto the main-thread Looper via [ProviderConfigViewModel.observeAll]'s
     * [kotlinx.coroutines.flow.combine] collector → FATAL crash.
     *
     * The correct behaviour: a locked key holder is an EXPECTED state, not an
     * error condition.  [observe] MUST emit an empty list (zero credentials
     * visible to the UI) instead of throwing, so the screen degrades
     * gracefully and the user is never hard-crashed.
     *
     * FALSIFIABILITY REHEARSAL (§6.L / Seventh Law clause 1):
     * Before the fix, collecting this Flow causes an [IllegalStateException]
     * to escape the [Flow.map] operator.  The [flow.first()] terminal call
     * re-throws it, and this test fails with:
     *   "IllegalStateException: credentials key holder is locked — prompt user for passphrase first"
     * After the fix, [observe] emits an empty list and the test passes.
     */
    @Test
    fun `observe emits empty list when key holder is locked — no crash on search path`() = runBlocking {
        val dao = FakeDao()
        // Write one encrypted row using the real key while "unlocked".
        val writeRepo = CredentialsEntryRepositoryImpl(dao, { key })
        writeRepo.upsert(
            CredentialsEntry(
                id = "cred-locked-test",
                displayName = "Locked test cred",
                secret = CredentialSecret.UsernamePassword("user", "pass"),
                createdAtUtc = 1L,
                updatedAtUtc = 2L,
            ),
        )
        // Now simulate "locked" state: key provider throws exactly as
        // CredentialsModule.provideCredentialsKeyProvider() does when
        // CredentialsKeyHolder.require() is called while holder is locked.
        val lockedKeyProvider: () -> ByteArray = {
            error("credentials key holder is locked — prompt user for passphrase first")
        }
        val lockedRepo = CredentialsEntryRepositoryImpl(dao, lockedKeyProvider)

        // The DAO has one encrypted row.  Collecting observe() with a locked
        // key holder MUST NOT throw — it MUST return an empty list.
        val result = lockedRepo.observe().first()

        assertTrue(
            "Expected empty list when key holder is locked, got: $result",
            result.isEmpty(),
        )
    }

    /**
     * Regression test for Crashlytics FATAL 4e11da491ed4854f8d6390e53a09d91f
     * (firstSeen/lastSeen 1.3.12, OPEN).
     *
     * A credential row whose ciphertext cannot be decrypted (wrong key OR
     * corrupted bytes) makes [CredentialsEntryRepositoryImpl.decode] throw
     * [javax.crypto.AEADBadTagException] (a
     * [java.security.GeneralSecurityException] subclass) inside the
     * [kotlinx.coroutines.flow.Flow.map] operator.  The 1.3.10 fix DELIBERATELY
     * let that exception propagate, ASSUMING a ViewModel coroutine-scope handler
     * would turn it into a non-fatal — but Crashlytics recorded it as **FATAL**
     * (a real app crash) on a real device.  A single corrupt/undecryptable row
     * MUST NOT crash the app, and MUST NOT hide every OTHER (valid) credential.
     *
     * Correct behaviour: [observe] SKIPS the undecryptable row (recording a
     * §6.AC non-fatal for operator triage) and still returns the valid rows.
     *
     * FALSIFIABILITY REHEARSAL (§6.L / Seventh Law clause 1): revert the
     * per-row resilient decode (`rows.mapNotNull(::decodeOrNull)`) back to the
     * throwing `rows.map(::decode)` and this test fails because
     * [kotlinx.coroutines.flow.Flow.first] re-throws:
     *   "javax.crypto.AEADBadTagException: ... BAD_DECRYPT"
     * After the fix the corrupt row is skipped and `observe` returns [valid-1].
     */
    @Test
    fun `observe skips a corrupt-ciphertext row and still returns the valid rows — no crash`() = runBlocking {
        val dao = FakeDao()
        val repo = CredentialsEntryRepositoryImpl(dao, { key })
        repo.upsert(
            CredentialsEntry("valid-1", "Valid", CredentialSecret.ApiKey("good-key"), 1, 2),
        )
        repo.upsert(
            CredentialsEntry("corrupt-1", "Corrupt", CredentialSecret.ApiKey("bad"), 1, 2),
        )
        // Tamper the stored ciphertext of the "corrupt-1" row so its AES-GCM
        // auth tag no longer verifies — exactly the on-device corruption that
        // produces AEADBadTagException at decrypt time.
        dao.flow.value = dao.flow.value.map { e ->
            if (e.id == "corrupt-1") {
                val tampered = e.ciphertext.copyOf()
                val last = tampered.size - 1
                tampered[last] = (tampered[last].toInt() xor 0x01).toByte()
                e.copy(ciphertext = tampered)
            } else {
                e
            }
        }

        // Collecting observe() MUST NOT throw; it MUST drop the corrupt row and
        // still surface the valid one.
        val read = repo.observe().first()

        assertEquals("corrupt row must be skipped, valid row must survive", 1, read.size)
        assertEquals("valid-1", read[0].id)
        assertEquals(CredentialSecret.ApiKey("good-key"), read[0].secret)
    }
}

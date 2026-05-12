package lava.credentials

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import lava.credentials.crypto.CredentialsCrypto
import lava.credentials.model.CredentialSecret
import lava.credentials.model.CredentialsEntry
import lava.database.dao.CredentialsEntryDao
import lava.database.entity.CredentialsEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CredentialsEntryRepositoryImplTest {
    private class FakeDao : CredentialsEntryDao {
        val flow = MutableStateFlow<List<CredentialsEntryEntity>>(emptyList())
        override fun observeAll() = flow
        override suspend fun get(id: String) = flow.value.firstOrNull { it.id == id }
        override suspend fun upsert(entity: CredentialsEntryEntity) {
            flow.value = (flow.value.filterNot { it.id == entity.id } + entity)
        }
        override suspend fun delete(id: String) { flow.value = flow.value.filterNot { it.id == id } }
    }

    private val salt = ByteArray(32) { it.toByte() }
    private val key = CredentialsCrypto.deriveKey("pass", salt)

    @Test
    fun `upsert encrypts and decrypts on read`() = runBlocking {
        val dao = FakeDao()
        val repo = CredentialsEntryRepositoryImpl(dao) { key }
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
        val repo = CredentialsEntryRepositoryImpl(dao) { key }
        assertNull(repo.get("nope"))
    }

    @Test
    fun `delete removes the row`() = runBlocking {
        val dao = FakeDao()
        val repo = CredentialsEntryRepositoryImpl(dao) { key }
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
        val repo = CredentialsEntryRepositoryImpl(dao) { key }
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
}

package lava.credentials

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import lava.database.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Anti-bluff integration test for [CredentialsRepository].
 *
 * Uses a real in-memory Room database and the real CredentialEncryptor.
 * No mocks. This verifies that:
 * 1. CRUD operations persist correctly
 * 2. Encryption/decryption boundary is respected
 * 3. The repository actually writes to and reads from SQLite
 *
 * Constitutional compliance:
 * - Second Law: real repository + real DAO + real encryptor
 * - Third Law: in-memory Room is behaviorally equivalent to production SQLite
 * - Sixth Law: assertions on persisted database state (user-visible persistence)
 * - Bluff-Audit rehearsal: mutate save() to no-op → load() returns null, test fails. Reverted.
 *
 * Bluff-Audit: CredentialsRepositoryTest
 *   Deliberate break: commented out `dao.upsert()` in save()
 *   Failure: `assertNotNull(repository.load("rutracker"))` → expected not null but was null
 *   Reverted: yes
 */
@RunWith(RobolectricTestRunner::class)
class CredentialsRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: CredentialsRepository
    private lateinit var encryptor: CredentialEncryptor

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        encryptor = CredentialEncryptor()
        repository = CredentialsRepository(
            dao = db.providerCredentialsDao(),
            encryptor = encryptor,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `save and load roundtrip`() = runTest {
        val creds = ProviderCredentials(
            providerId = "rutracker",
            authType = "password",
            username = "user1",
            password = "secret123",
            createdAt = 1L,
            updatedAt = 1L,
        )
        repository.save(creds)

        val loaded = repository.load("rutracker")
        assertNotNull(loaded)
        assertEquals("rutracker", loaded!!.providerId)
        assertEquals("password", loaded.authType)
        assertEquals("user1", loaded.username)
        assertEquals("secret123", loaded.password)
    }

    @Test
    fun `password is encrypted in database not plaintext`() = runTest {
        val password = "my-super-secret"
        val creds = ProviderCredentials(
            providerId = "nnmclub",
            authType = "password",
            username = "u",
            password = password,
            createdAt = 1L,
            updatedAt = 1L,
        )
        repository.save(creds)

        // Peek at raw entity to verify encryption happened
        val entity = db.providerCredentialsDao().load("nnmclub")
        assertNotNull(entity)
        val encryptedPassword = entity!!.encryptedPassword
        assertNotNull(encryptedPassword)
        // The encrypted value must NOT be the plaintext password
        assertTrue(
            "Password must be encrypted in DB, not plaintext",
            encryptedPassword != password,
        )
    }

    @Test
    fun `observeAll emits saved credentials`() = runTest {
        val c1 = sampleCreds("rutracker", password = "p1")
        val c2 = sampleCreds("rutor", token = "t1")
        repository.save(c1)
        repository.save(c2)

        val all = repository.observeAll().first()
        assertEquals(2, all.size)
        assertTrue(all.any { it.providerId == "rutracker" })
        assertTrue(all.any { it.providerId == "rutor" })
    }

    @Test
    fun `delete removes credential`() = runTest {
        repository.save(sampleCreds("kinozal", password = "p"))
        assertNotNull(repository.load("kinozal"))

        repository.delete("kinozal")
        assertNull(repository.load("kinozal"))
    }

    @Test
    fun `save overwrites existing credential`() = runTest {
        repository.save(sampleCreds("archiveorg", apiKey = "key1"))
        repository.save(sampleCreds("archiveorg", apiKey = "key2"))

        val loaded = repository.load("archiveorg")
        assertEquals("key2", loaded?.apiKey)
    }

    @Test
    fun `observe emits updates`() = runTest {
        repository.save(sampleCreds("gutenberg", password = "old"))

        val first = repository.observe("gutenberg").first()
        assertEquals("old", first?.password)

        repository.save(sampleCreds("gutenberg", password = "new"))
        val second = repository.observe("gutenberg").first()
        assertEquals("new", second?.password)
    }

    @Test
    fun `token and apiKey are encrypted in database`() = runTest {
        val token = "bearer-token-abc"
        val apiKey = "api-key-xyz"
        repository.save(
            ProviderCredentials(
                providerId = "test",
                authType = "token",
                token = token,
                apiKey = apiKey,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )

        val entity = db.providerCredentialsDao().load("test")
        assertNotNull(entity)
        assertTrue(
            "Token must be encrypted",
            entity!!.encryptedToken != token,
        )
        assertTrue(
            "API key must be encrypted",
            entity.encryptedApiKey != apiKey,
        )
    }

    private fun sampleCreds(
        providerId: String,
        password: String? = null,
        token: String? = null,
        apiKey: String? = null,
    ) = ProviderCredentials(
        providerId = providerId,
        authType = when {
            password != null -> "password"
            token != null -> "token"
            apiKey != null -> "apikey"
            else -> "none"
        },
        password = password,
        token = token,
        apiKey = apiKey,
        createdAt = 1L,
        updatedAt = 1L,
    )
}

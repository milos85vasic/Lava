package lava.credentials

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import lava.database.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Anti-bluff integration test for [ProviderCredentialManager].
 *
 * Uses real CredentialsRepository + real CredentialEncryptor + real Room DAO.
 * No mocks. Verifies orchestration logic: setPassword, setApiKey, setToken,
 * clear, isAuthenticated, observeAll.
 *
 * Constitutional compliance:
 * - Second Law: real Manager → real Repository → real DAO + Encryptor
 * - Sixth Law: assertions on user-visible state (credentials persisted/removed)
 * - Bluff-Audit rehearsal: mutate clear() to no-op → assertNull after clear fails. Reverted.
 *
 * Bluff-Audit: ProviderCredentialManagerTest
 *   Deliberate break: changed clear() body to empty
 *   Failure: `assertNull(manager.getCredentials("rutracker"))` after clear → expected null but was not null
 *   Reverted: yes
 */
@RunWith(RobolectricTestRunner::class)
class ProviderCredentialManagerTest {

    private lateinit var db: AppDatabase
    private lateinit var manager: ProviderCredentialManager

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val encryptor = CredentialEncryptor()
        val repository = CredentialsRepository(
            dao = db.providerCredentialsDao(),
            encryptor = encryptor,
        )
        manager = ProviderCredentialManager(repository)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `setPassword persists username and password`() = runTest {
        manager.setPassword("rutracker", "vasya", "secret123")

        val creds = manager.getCredentials("rutracker")
        assertNotNull(creds)
        assertEquals("vasya", creds!!.username)
        assertEquals("secret123", creds.password)
        assertEquals("password", creds.authType)
    }

    @Test
    fun `setApiKey persists api key and optional secret`() = runTest {
        manager.setApiKey("archiveorg", "key-abc", "secret-xyz")

        val creds = manager.getCredentials("archiveorg")
        assertNotNull(creds)
        assertEquals("apikey", creds!!.authType)
        assertEquals("key-abc", creds.apiKey)
        assertEquals("secret-xyz", creds.apiSecret)
    }

    @Test
    fun `setToken persists token value`() = runTest {
        manager.setToken("rutor", "bearer-123")

        val creds = manager.getCredentials("rutor")
        assertNotNull(creds)
        assertEquals("token", creds!!.authType)
        assertEquals("bearer-123", creds.token)
    }

    @Test
    fun `setCookie persists cookie value`() = runTest {
        manager.setCookie("nnmclub", "phpbb2mysql_4_data=abc")

        val creds = manager.getCredentials("nnmclub")
        assertNotNull(creds)
        assertEquals("cookie", creds!!.authType)
        assertEquals("phpbb2mysql_4_data=abc", creds.cookieValue)
    }

    @Test
    fun `clear removes credentials`() = runTest {
        manager.setPassword("kinozal", "u", "p")
        assertNotNull(manager.getCredentials("kinozal"))

        manager.clear("kinozal")
        assertNull(manager.getCredentials("kinozal"))
    }

    @Test
    fun `isAuthenticated returns true when credentials exist`() = runTest {
        manager.setPassword("rutracker", "u", "p")
        assertTrue(manager.isAuthenticated("rutracker"))
    }

    @Test
    fun `isAuthenticated returns false when no credentials`() = runTest {
        assertFalse(manager.isAuthenticated("rutracker"))
    }

    @Test
    fun `isAuthenticated returns false after clear`() = runTest {
        manager.setPassword("rutracker", "u", "p")
        assertTrue(manager.isAuthenticated("rutracker"))

        manager.clear("rutracker")
        assertFalse(manager.isAuthenticated("rutracker"))
    }

    @Test
    fun `observeAll emits all credentials`() = runTest {
        manager.setPassword("rutracker", "u1", "p1")
        manager.setApiKey("archiveorg", "k1", null)
        manager.setToken("rutor", "t1")

        val all = manager.observeAll().first()
        assertEquals(3, all.size)
    }

    @Test
    fun `overwriting credentials replaces previous values`() = runTest {
        manager.setPassword("rutracker", "old-user", "old-pass")
        manager.setApiKey("rutracker", "new-key", null)

        val creds = manager.getCredentials("rutracker")
        assertEquals("apikey", creds!!.authType)
        assertEquals("new-key", creds.apiKey)
        // Password should be gone after overwrite to apikey
        assertNull(creds.password)
    }

    @Test
    fun `observeCredentials emits per-provider updates`() = runTest {
        manager.setPassword("gutenberg", "old", "old")

        val first = manager.observeCredentials("gutenberg").first()
        assertEquals("old", first?.username)

        manager.setPassword("gutenberg", "new", "new")
        val second = manager.observeCredentials("gutenberg").first()
        assertEquals("new", second?.username)
    }
}

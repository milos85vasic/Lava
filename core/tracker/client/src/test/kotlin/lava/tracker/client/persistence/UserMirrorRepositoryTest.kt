package lava.tracker.client.persistence

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import lava.database.AppDatabase
import lava.sdk.api.Protocol
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
import org.robolectric.annotation.Config

/**
 * Real-stack test of [UserMirrorRepository] against an in-memory Room DB.
 * Sixth Law type: VM-CONTRACT (no UI involved); primary assertions are on
 * persisted DB rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class UserMirrorRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: UserMirrorRepository

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = UserMirrorRepository(db.userMirrorDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    // CHALLENGE
    @Test
    fun add_persists_a_mirror_row_and_loadAll_returns_it() = runTest {
        val firstAdd = repo.add(
            trackerId = "rutracker",
            url = "https://my-mirror.example/",
            priority = 5,
            protocol = Protocol.HTTPS,
        )

        assertTrue("first add of a new url MUST report true", firstAdd)
        val rows = repo.loadAll("rutracker")
        assertEquals(1, rows.size)
        assertEquals("https://my-mirror.example/", rows[0].url)
        assertEquals(5, rows[0].priority)
        assertEquals("HTTPS", rows[0].protocol)
    }

    // CHALLENGE
    @Test
    fun add_existing_url_returns_false_and_replaces_row() = runTest {
        repo.add("rutracker", "https://m.example/", priority = 1, protocol = Protocol.HTTPS)
        val secondAdd = repo.add("rutracker", "https://m.example/", priority = 9, protocol = Protocol.HTTP)

        assertFalse("re-adding the same (tracker, url) MUST report false", secondAdd)
        val rows = repo.loadAll("rutracker")
        assertEquals(1, rows.size)
        assertEquals(9, rows[0].priority)
        assertEquals("HTTP", rows[0].protocol)
    }

    // CHALLENGE
    @Test
    fun loadAsMirrorUrls_maps_protocol_string_back_to_enum() = runTest {
        repo.add("rutor", "https://r.example/", priority = 0, protocol = Protocol.HTTP3)

        val mirrors = repo.loadAsMirrorUrls("rutor")
        assertEquals(1, mirrors.size)
        assertEquals(Protocol.HTTP3, mirrors[0].protocol)
        assertEquals("https://r.example/", mirrors[0].url)
        assertFalse(mirrors[0].isPrimary)
    }

    // CHALLENGE
    @Test
    fun remove_deletes_specific_row_and_leaves_others() = runTest {
        repo.add("rutracker", "https://a.example/", priority = 0, protocol = Protocol.HTTPS)
        repo.add("rutracker", "https://b.example/", priority = 1, protocol = Protocol.HTTPS)

        repo.remove("rutracker", "https://a.example/")

        val rows = repo.loadAll("rutracker")
        assertEquals(1, rows.size)
        assertEquals("https://b.example/", rows[0].url)
    }

    // CHALLENGE
    @Test
    fun same_url_in_different_trackers_is_allowed() = runTest {
        val rutracker = repo.add("rutracker", "https://shared.example/", priority = 0, protocol = Protocol.HTTPS)
        val rutor = repo.add("rutor", "https://shared.example/", priority = 0, protocol = Protocol.HTTPS)

        assertTrue(rutracker)
        assertTrue(rutor)
        assertEquals(1, repo.loadAll("rutracker").size)
        assertEquals(1, repo.loadAll("rutor").size)
    }

    // CHALLENGE
    @Test
    fun clear_removes_only_one_trackers_rows() = runTest {
        repo.add("rutracker", "https://a.example/", priority = 0, protocol = Protocol.HTTPS)
        repo.add("rutor", "https://b.example/", priority = 0, protocol = Protocol.HTTPS)

        repo.clear("rutracker")

        assertTrue(repo.loadAll("rutracker").isEmpty())
        assertEquals(1, repo.loadAll("rutor").size)
    }

    // CHALLENGE
    @Test
    fun loadAll_orders_by_priority_ascending() = runTest {
        repo.add("rutracker", "https://low.example/", priority = 10, protocol = Protocol.HTTPS)
        repo.add("rutracker", "https://hi.example/", priority = 0, protocol = Protocol.HTTPS)
        repo.add("rutracker", "https://mid.example/", priority = 5, protocol = Protocol.HTTPS)

        val rows = repo.loadAll("rutracker")
        assertEquals(listOf(0, 5, 10), rows.map { it.priority })
    }

    // CHALLENGE
    @Test
    fun loadAsMirrorUrls_for_unknown_tracker_returns_empty() = runTest {
        val mirrors = repo.loadAsMirrorUrls("nonexistent")
        assertTrue(mirrors.isEmpty())
        assertNotNull(mirrors)
        // sanity: assert "no nulls" so kotlin contract holds
        assertNull(mirrors.firstOrNull())
    }
}

package lava.tracker.client.persistence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import lava.database.AppDatabase
import lava.sdk.api.Protocol
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

/**
 * Tests [MirrorConfigLoader] using a mocked AssetManager (Asset reads are an
 * external boundary per Seventh Law clause 4(c)) wrapped around a real
 * Context (Robolectric) and a real in-memory Room DB-backed
 * [UserMirrorRepository]. Exercises the merge + sort path that the
 * [TrackerSettingsViewModel] (Task 4.11) and [MirrorHealthCheckWorker]
 * (Task 4.4) depend on.
 *
 * Sixth Law type: VM-CONTRACT (no UI involved); primary assertion is on the
 * resolved [MirrorUrl] list (the data the SDK feeds into MirrorManager).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MirrorConfigLoaderTest {

    private val bundledJson = """
        {
          "version": 1,
          "trackers": {
            "rutracker": {
              "expectedHealthMarker": "rutracker",
              "mirrors": [
                {"url": "https://rutracker.org", "isPrimary": true, "priority": 0, "protocol": "HTTPS"},
                {"url": "https://rutracker.net", "isPrimary": false, "priority": 1, "protocol": "HTTPS"}
              ]
            },
            "rutor": {
              "expectedHealthMarker": "RuTor",
              "mirrors": [
                {"url": "https://rutor.info", "isPrimary": true, "priority": 0, "protocol": "HTTPS"}
              ]
            }
          }
        }
    """.trimIndent()

    private lateinit var db: AppDatabase
    private lateinit var userRepo: UserMirrorRepository
    private lateinit var loader: MirrorConfigLoader

    @Before
    fun setUp() {
        val realCtx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(realCtx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        userRepo = UserMirrorRepository(db.userMirrorDao())

        // Mock only the Asset read — the lowest external boundary.
        val mockedAssets = mockk<android.content.res.AssetManager>(relaxed = true) {
            every { open(MirrorConfigLoader.ASSET_PATH) } answers {
                ByteArrayInputStream(bundledJson.toByteArray(Charsets.UTF_8))
            }
        }
        val mockedCtx = mockk<Context>(relaxed = true) {
            every { assets } returns mockedAssets
        }

        loader = MirrorConfigLoader(mockedCtx, userRepo)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // CHALLENGE
    @Test
    fun loadFor_returns_bundled_only_when_no_user_mirrors() = runTest {
        val mirrors = loader.loadFor("rutracker")
        assertEquals(2, mirrors.size)
        assertEquals("https://rutracker.org", mirrors[0].url)
        assertEquals("https://rutracker.net", mirrors[1].url)
        assertEquals(Protocol.HTTPS, mirrors[0].protocol)
    }

    // CHALLENGE
    @Test
    fun loadFor_user_mirror_supersedes_bundled_at_same_url() = runTest {
        userRepo.add("rutracker", "https://rutracker.org", priority = 99, protocol = Protocol.HTTP)

        val mirrors = loader.loadFor("rutracker")
        // Same url -> single entry, but with user-supplied priority + protocol
        val match = mirrors.first { it.url == "https://rutracker.org" }
        assertEquals(99, match.priority)
        assertEquals(Protocol.HTTP, match.protocol)
        // Total entries unchanged (still 2 distinct URLs)
        assertEquals(2, mirrors.size)
    }

    // CHALLENGE
    @Test
    fun loadFor_user_mirror_at_new_url_appends_and_sorts_by_priority() = runTest {
        userRepo.add("rutracker", "https://my-mirror.example/", priority = 5, protocol = Protocol.HTTPS)

        val mirrors = loader.loadFor("rutracker")
        assertEquals(3, mirrors.size)
        assertEquals(listOf(0, 1, 5), mirrors.map { it.priority })
        assertEquals("https://my-mirror.example/", mirrors[2].url)
    }

    // CHALLENGE
    @Test
    fun loadFor_unknown_tracker_returns_empty() = runTest {
        val mirrors = loader.loadFor("nonexistent")
        assertTrue(mirrors.isEmpty())
    }

    // CHALLENGE
    @Test
    fun bundledFor_returns_expected_mirror_set() {
        val rutor = loader.bundledFor("rutor")
        assertEquals(1, rutor.size)
        assertEquals("https://rutor.info", rutor[0].url)
    }

    // CHALLENGE
    @Test
    fun bundledMarkerFor_returns_expected_marker_or_null() {
        assertEquals("rutracker", loader.bundledMarkerFor("rutracker"))
        assertEquals("RuTor", loader.bundledMarkerFor("rutor"))
        assertEquals(null, loader.bundledMarkerFor("nonexistent"))
    }

    // CHALLENGE
    @Test
    fun loadFor_user_mirrors_for_tracker_with_no_bundled_entry_still_returned() = runTest {
        userRepo.add("custom", "https://custom.example/", priority = 0, protocol = Protocol.HTTPS)

        val mirrors = loader.loadFor("custom")
        assertEquals(1, mirrors.size)
        assertEquals("https://custom.example/", mirrors[0].url)
    }
}

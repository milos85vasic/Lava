package lava.tracker.client.persistence

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import lava.database.AppDatabase
import lava.sdk.api.HealthState
import lava.sdk.api.MirrorState
import lava.sdk.api.MirrorUrl
import lava.sdk.api.Protocol
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real-stack test of [MirrorHealthRepository] against an in-memory Room DB.
 * Per Seventh Law clause 2: the test traverses the same DAO path the
 * production [MirrorHealthCheckWorker] uses.
 *
 * Sixth Law type: VM-CONTRACT (no UI involved); primary assertions are on
 * persisted DB rows (user-visible state under clause 3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MirrorHealthRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: MirrorHealthRepository

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = MirrorHealthRepository(db.mirrorHealthDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private val mirrorA = MirrorUrl(url = "https://a.example/", priority = 0, protocol = Protocol.HTTPS)
    private val mirrorB = MirrorUrl(url = "https://b.example/", priority = 1, protocol = Protocol.HTTPS)

    // CHALLENGE
    @Test
    fun upsertAll_persists_all_states_then_loads_them_back() = runTest {
        val states = listOf(
            MirrorState(mirrorA, HealthState.HEALTHY, lastCheck = Instant.fromEpochMilliseconds(1_000), consecutiveFailures = 0),
            MirrorState(mirrorB, HealthState.UNHEALTHY, lastCheck = Instant.fromEpochMilliseconds(2_000), consecutiveFailures = 5),
        )

        repo.upsertAll("rutracker", states)

        val rows = repo.loadAll("rutracker").sortedBy { it.mirrorUrl }
        assertEquals(2, rows.size)
        assertEquals(mirrorA.url, rows[0].mirrorUrl)
        assertEquals("HEALTHY", rows[0].state)
        assertEquals(0, rows[0].consecutiveFailures)
        assertEquals(1_000L, rows[0].lastCheckAt)
        assertEquals(mirrorB.url, rows[1].mirrorUrl)
        assertEquals("UNHEALTHY", rows[1].state)
        assertEquals(5, rows[1].consecutiveFailures)
    }

    // CHALLENGE
    @Test
    fun loadStates_reconstructs_MirrorState_only_for_known_mirrors() = runTest {
        repo.upsertAll(
            "rutracker",
            listOf(
                MirrorState(mirrorA, HealthState.HEALTHY, lastCheck = null, consecutiveFailures = 0),
                MirrorState(mirrorB, HealthState.DEGRADED, lastCheck = Instant.fromEpochMilliseconds(7_000), consecutiveFailures = 2),
            ),
        )

        // Only mirrorA known to the loader (the user removed B from settings)
        val states = repo.loadStates("rutracker", listOf(mirrorA))

        assertEquals(1, states.size)
        assertEquals(mirrorA.url, states[0].mirror.url)
        assertEquals(HealthState.HEALTHY, states[0].health)
    }

    // CHALLENGE
    @Test
    fun upsert_replaces_existing_row_for_same_tracker_and_url() = runTest {
        repo.upsert("rutracker", MirrorState(mirrorA, HealthState.HEALTHY, lastCheck = null, consecutiveFailures = 0))
        repo.upsert("rutracker", MirrorState(mirrorA, HealthState.UNHEALTHY, lastCheck = Instant.fromEpochMilliseconds(42), consecutiveFailures = 9))

        val rows = repo.loadAll("rutracker")
        assertEquals(1, rows.size)
        assertEquals("UNHEALTHY", rows[0].state)
        assertEquals(9, rows[0].consecutiveFailures)
        assertEquals(42L, rows[0].lastCheckAt)
    }

    // CHALLENGE
    @Test
    fun same_url_different_trackers_are_independent_rows() = runTest {
        repo.upsert("rutracker", MirrorState(mirrorA, HealthState.HEALTHY, lastCheck = null, consecutiveFailures = 0))
        repo.upsert("rutor", MirrorState(mirrorA, HealthState.UNHEALTHY, lastCheck = null, consecutiveFailures = 7))

        val rutracker = repo.loadAll("rutracker")
        val rutor = repo.loadAll("rutor")
        assertEquals(1, rutracker.size)
        assertEquals(1, rutor.size)
        assertEquals("HEALTHY", rutracker[0].state)
        assertEquals("UNHEALTHY", rutor[0].state)
    }

    // CHALLENGE
    @Test
    fun clear_removes_only_rows_for_one_tracker() = runTest {
        repo.upsert("rutracker", MirrorState(mirrorA, HealthState.HEALTHY, lastCheck = null, consecutiveFailures = 0))
        repo.upsert("rutor", MirrorState(mirrorA, HealthState.HEALTHY, lastCheck = null, consecutiveFailures = 0))

        repo.clear("rutracker")

        assertTrue(repo.loadAll("rutracker").isEmpty())
        assertEquals(1, repo.loadAll("rutor").size)
    }

    // CHALLENGE
    @Test
    fun loadStates_returns_empty_when_no_rows_persisted() = runTest {
        val states = repo.loadStates("rutracker", listOf(mirrorA))
        assertTrue(states.isEmpty())
    }

    // CHALLENGE
    @Test
    fun null_lastCheckAt_is_preserved_through_round_trip() = runTest {
        repo.upsert("rutracker", MirrorState(mirrorA, HealthState.UNKNOWN, lastCheck = null, consecutiveFailures = 0))

        val states = repo.loadStates("rutracker", listOf(mirrorA))
        assertEquals(1, states.size)
        assertNull(states[0].lastCheck)
    }
}

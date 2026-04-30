package lava.tracker.client

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import lava.database.AppDatabase
import lava.sdk.api.HealthState
import lava.sdk.api.MirrorState
import lava.sdk.api.MirrorUrl
import lava.sdk.api.Protocol
import lava.sdk.mirror.HealthProbe
import lava.tracker.api.AuthType
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.client.persistence.LavaMirrorManagerHolder
import lava.tracker.client.persistence.MirrorConfigLoader
import lava.tracker.client.persistence.MirrorHealthRepository
import lava.tracker.client.persistence.UserMirrorRepository
import lava.tracker.registry.DefaultTrackerRegistry
import lava.tracker.registry.TrackerClientFactory
import lava.tracker.testing.FakeTrackerClient
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
 * Real-stack test for the SP-3a Phase 4 mirror-health surface added to
 * [LavaTrackerSdk] in Task 4.4: probeMirrorsFor / observeMirrorHealth /
 * initialize. Wires real Room (in-memory), real
 * [MirrorHealthRepository], real [UserMirrorRepository], real
 * [LavaMirrorManagerHolder], real [MirrorConfigLoader] and real
 * registry against a [FakeTrackerClient]. The HealthProbe is the only
 * collaborator stubbed (it represents the network boundary, allowed
 * under Seventh Law clause 4(c)).
 *
 * Bluff-Audit:
 * - Test type: CHALLENGE — primary assertions are on persisted Room
 *   rows + observable MirrorState values that the Settings UI reads.
 * - Real-stack: every layer above the network probe is the real
 *   production class.
 * - Falsifiability rehearsal: removing
 *   `mirrorHealthRepository?.upsertAll(...)` from
 *   LavaTrackerSdk.probeMirrorsFor caused
 *   probeMirrorsFor_persists_state_to_repository to fail with
 *   "expected HEALTHY in DB, got 0 rows". Reverted before commit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class LavaTrackerSdkMirrorHealthTest {

    private lateinit var db: AppDatabase
    private lateinit var healthRepo: MirrorHealthRepository
    private lateinit var userRepo: UserMirrorRepository
    private lateinit var loader: MirrorConfigLoader
    private lateinit var holder: LavaMirrorManagerHolder
    private lateinit var sdk: LavaTrackerSdk

    private val descriptor = object : TrackerDescriptor {
        override val trackerId: String = "rutracker"
        override val displayName: String = "Test Tracker"
        override val baseUrls: List<MirrorUrl> = listOf(
            MirrorUrl("https://primary.example/", isPrimary = true, priority = 0, protocol = Protocol.HTTPS),
            MirrorUrl("https://backup.example/", priority = 1, protocol = Protocol.HTTPS),
        )
        override val capabilities: Set<TrackerCapability> = setOf(TrackerCapability.SEARCH)
        override val authType: AuthType = AuthType.NONE
        override val encoding: String = "UTF-8"
        override val expectedHealthMarker: String = "rutracker"
    }

    private val bundledJson = """
        {
          "version": 1,
          "trackers": {
            "rutracker": {
              "expectedHealthMarker": "rutracker",
              "mirrors": [
                {"url": "https://primary.example/", "isPrimary": true, "priority": 0, "protocol": "HTTPS"},
                {"url": "https://backup.example/", "isPrimary": false, "priority": 1, "protocol": "HTTPS"}
              ]
            }
          }
        }
    """.trimIndent()

    @Before
    fun setUp() {
        val realCtx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(realCtx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        healthRepo = MirrorHealthRepository(db.mirrorHealthDao())
        userRepo = UserMirrorRepository(db.userMirrorDao())

        val mockedAssets = mockk<android.content.res.AssetManager>(relaxed = true) {
            every { open(MirrorConfigLoader.ASSET_PATH) } answers {
                ByteArrayInputStream(bundledJson.toByteArray(Charsets.UTF_8))
            }
        }
        val ctxWithAssets = mockk<android.content.Context>(relaxed = true) {
            every { assets } returns mockedAssets
        }
        loader = MirrorConfigLoader(ctxWithAssets, userRepo)

        val client = FakeTrackerClient(descriptor)
        val registry = DefaultTrackerRegistry().also {
            it.register(object : TrackerClientFactory {
                override val descriptor: TrackerDescriptor = client.descriptor
                override fun create(config: lava.sdk.api.PluginConfig): TrackerClient = client
            })
        }

        // Boundary stub: the HealthProbe is the network. Every probe call
        // returns HEALTHY so the in-memory MirrorManager flips both mirrors
        // to HEALTHY on probeAll. This is the lowest permitted boundary
        // under Seventh Law clause 4(c).
        val healthyProbe = object : HealthProbe {
            override suspend fun probe(endpoint: MirrorUrl): HealthState = HealthState.HEALTHY
        }
        holder = LavaMirrorManagerHolder(
            registry = registry,
            configLoader = loader,
            probeFactory = LavaMirrorManagerHolder.HealthProbeFactory { healthyProbe },
        )
        sdk = LavaTrackerSdk(
            registry = registry,
            mirrorManagerHolder = holder,
            mirrorHealthRepository = healthRepo,
            mirrorConfigLoader = loader,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // CHALLENGE
    @Test
    fun probeMirrorsFor_persists_state_to_repository() = runTest {
        sdk.probeMirrorsFor("rutracker")

        val rows = healthRepo.loadAll("rutracker").sortedBy { it.mirrorUrl }
        assertEquals(2, rows.size)
        assertEquals("HEALTHY", rows[0].state)
        assertEquals("HEALTHY", rows[1].state)
    }

    // CHALLENGE
    @Test
    fun observeMirrorHealth_emits_states_from_in_memory_manager_after_probe() = runTest {
        sdk.probeMirrorsFor("rutracker")

        val states = sdk.observeMirrorHealth("rutracker").first()
        assertEquals(2, states.size)
        assertTrue(
            "All mirrors should be HEALTHY after a healthy probe",
            states.all { it.health == HealthState.HEALTHY },
        )
    }

    // CHALLENGE
    @Test
    fun initialize_rehydrates_unhealthy_state_from_repository() = runTest {
        // Pre-populate the repository as if a previous app session had
        // persisted UNHEALTHY for the primary mirror after 5 failures.
        healthRepo.upsertAll(
            "rutracker",
            listOf(
                MirrorState(
                    mirror = descriptor.baseUrls[0],
                    health = HealthState.UNHEALTHY,
                    lastCheck = null,
                    consecutiveFailures = 5,
                ),
            ),
        )

        sdk.initialize()

        // After initialize, the manager's in-memory state for the primary
        // mirror MUST reflect UNHEALTHY so the next executeWithFallback
        // skips it.
        val states = sdk.observeMirrorHealth("rutracker").first()
        val primary = states.first { it.mirror.url == "https://primary.example/" }
        assertEquals(HealthState.UNHEALTHY, primary.health)
    }

    // CHALLENGE
    @Test
    fun initialize_with_no_persisted_state_leaves_unknown_health() = runTest {
        sdk.initialize()

        val states = sdk.observeMirrorHealth("rutracker").first()
        // No persisted snapshot ⇒ UNKNOWN until first probe runs.
        assertTrue(states.all { it.health == HealthState.UNKNOWN })
    }

    // CHALLENGE
    @Test
    fun observeMirrorHealth_returns_empty_when_manager_uninitialised() = runTest {
        // Without a probe / initialize call, the holder's internal map is
        // empty, so observeMirrorHealth must yield an empty list rather
        // than crash.
        val states = sdk.observeMirrorHealth("rutracker").first()
        assertTrue(states.isEmpty())
    }
}

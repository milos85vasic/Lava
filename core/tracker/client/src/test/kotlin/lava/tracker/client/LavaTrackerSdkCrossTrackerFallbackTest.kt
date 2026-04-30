package lava.tracker.client

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import lava.database.AppDatabase
import lava.sdk.api.HealthState
import lava.sdk.api.MirrorUrl
import lava.sdk.api.PluginConfig
import lava.sdk.api.Protocol
import lava.sdk.mirror.HealthProbe
import lava.tracker.api.AuthType
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.api.model.SearchRequest
import lava.tracker.api.model.SearchResult
import lava.tracker.api.model.TorrentItem
import lava.tracker.client.persistence.LavaMirrorManagerHolder
import lava.tracker.client.persistence.MirrorConfigLoader
import lava.tracker.client.persistence.MirrorHealthRepository
import lava.tracker.client.persistence.UserMirrorRepository
import lava.tracker.registry.DefaultTrackerRegistry
import lava.tracker.registry.TrackerClientFactory
import lava.tracker.testing.FakeTrackerClient
import lava.tracker.testing.searchRequest
import lava.tracker.testing.torrent
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
 * Integration test for SP-3a Phase 4 Task 4.7: cross-tracker fallback
 * wiring inside [LavaTrackerSdk.search]. Real-stack: real Room (in-memory),
 * real MirrorHealthRepository / UserMirrorRepository / MirrorConfigLoader
 * / LavaMirrorManagerHolder / DefaultTrackerRegistry / FakeTrackerClient
 * for both rutracker and rutor. The HealthProbe is the only stub (network
 * boundary); for the failure path it's set to always return UNHEALTHY so
 * the in-memory MirrorManager flips every rutracker mirror to UNHEALTHY,
 * causing executeWithFallback to throw MirrorUnavailableException.
 *
 * Bluff-Audit:
 * - Test type: CHALLENGE — primary assertions on the SearchOutcome the
 *   ViewModel renders (CrossTrackerFallbackProposed instance, the
 *   resumeWith result, the torrent items).
 * - Real-stack: every layer above HealthProbe is real production code.
 * - Falsifiability rehearsal: mutated CrossTrackerFallbackPolicy.proposeFallback
 *   to return null. Ran the test —
 *   search_emits_CrossTrackerFallbackProposed_when_all_rutracker_mirrors_unhealthy
 *   failed with "expected CrossTrackerFallbackProposed, got Failure".
 *   Reverted before commit; recorded in 4.8-fallback.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class LavaTrackerSdkCrossTrackerFallbackTest {

    private lateinit var db: AppDatabase
    private lateinit var sdk: LavaTrackerSdk
    private lateinit var rutorClient: FakeTrackerClient

    private val rutrackerDescriptor = object : TrackerDescriptor {
        override val trackerId: String = "rutracker"
        override val displayName: String = "RuTracker"
        override val baseUrls: List<MirrorUrl> = listOf(
            MirrorUrl("https://rutracker.example/", isPrimary = true, priority = 0, protocol = Protocol.HTTPS),
        )
        override val capabilities: Set<TrackerCapability> = setOf(TrackerCapability.SEARCH)
        override val authType: AuthType = AuthType.NONE
        override val encoding: String = "UTF-8"
        override val expectedHealthMarker: String = "rutracker"
    }

    private val rutorDescriptor = object : TrackerDescriptor {
        override val trackerId: String = "rutor"
        override val displayName: String = "RuTor"
        override val baseUrls: List<MirrorUrl> = listOf(
            MirrorUrl("https://rutor.example/", isPrimary = true, priority = 0, protocol = Protocol.HTTPS),
        )
        override val capabilities: Set<TrackerCapability> = setOf(TrackerCapability.SEARCH)
        override val authType: AuthType = AuthType.NONE
        override val encoding: String = "UTF-8"
        override val expectedHealthMarker: String = "RuTor"
    }

    private val bundledJson = """
        {
          "version": 1,
          "trackers": {
            "rutracker": {
              "expectedHealthMarker": "rutracker",
              "mirrors": [
                {"url": "https://rutracker.example/", "isPrimary": true, "priority": 0, "protocol": "HTTPS"}
              ]
            },
            "rutor": {
              "expectedHealthMarker": "RuTor",
              "mirrors": [
                {"url": "https://rutor.example/", "isPrimary": true, "priority": 0, "protocol": "HTTPS"}
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
        val healthRepo = MirrorHealthRepository(db.mirrorHealthDao())
        val userRepo = UserMirrorRepository(db.userMirrorDao())

        val mockedAssets = mockk<android.content.res.AssetManager>(relaxed = true) {
            every { open(MirrorConfigLoader.ASSET_PATH) } answers {
                ByteArrayInputStream(bundledJson.toByteArray(Charsets.UTF_8))
            }
        }
        val mockedCtx = mockk<android.content.Context>(relaxed = true) {
            every { assets } returns mockedAssets
        }
        val loader = MirrorConfigLoader(mockedCtx, userRepo)

        val rutrackerClient = FakeTrackerClient(rutrackerDescriptor)
        rutorClient = FakeTrackerClient(rutorDescriptor).also {
            it.searchResultProvider = { req, _ ->
                SearchResult(
                    items = listOf(
                        torrent {
                            torrentId = "rutor-result"
                            trackerId = "rutor"
                            title = "rutor-fallback-${req.query}"
                        },
                    ),
                    totalPages = 1,
                    currentPage = 0,
                )
            }
        }
        val registry = DefaultTrackerRegistry().also { reg ->
            reg.register(object : TrackerClientFactory {
                override val descriptor: TrackerDescriptor = rutrackerDescriptor
                override fun create(config: PluginConfig): TrackerClient = rutrackerClient
            })
            reg.register(object : TrackerClientFactory {
                override val descriptor: TrackerDescriptor = rutorDescriptor
                override fun create(config: PluginConfig): TrackerClient = rutorClient
            })
        }

        // Always-UNHEALTHY probe: drives the in-memory MirrorManager state
        // for rutracker to UNHEALTHY for its only mirror, which makes
        // executeWithFallback throw MirrorUnavailableException with no
        // attempts (the eligible-mirrors filter excludes UNHEALTHY).
        val unhealthyProbe = object : HealthProbe {
            override suspend fun probe(endpoint: MirrorUrl): HealthState = HealthState.UNHEALTHY
        }
        val holder = LavaMirrorManagerHolder(
            registry = registry,
            configLoader = loader,
            probeFactory = LavaMirrorManagerHolder.HealthProbeFactory { unhealthyProbe },
        )
        val fallbackPolicy = CrossTrackerFallbackPolicy(registry)

        sdk = LavaTrackerSdk(
            registry = registry,
            mirrorManagerHolder = holder,
            mirrorHealthRepository = healthRepo,
            mirrorConfigLoader = loader,
            crossTrackerFallback = fallbackPolicy,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // CHALLENGE
    @Test
    fun search_emits_CrossTrackerFallbackProposed_when_all_rutracker_mirrors_unhealthy() = runTest {
        // Drive every rutracker mirror to UNHEALTHY via the always-UNHEALTHY probe.
        sdk.probeMirrorsFor("rutracker")

        val outcome = sdk.search(searchRequest { query = "ubuntu" })

        assertTrue(
            "Expected CrossTrackerFallbackProposed but got: $outcome",
            outcome is SearchOutcome.CrossTrackerFallbackProposed,
        )
        outcome as SearchOutcome.CrossTrackerFallbackProposed
        assertEquals("rutracker", outcome.failedTrackerId)
        assertEquals("rutor", outcome.proposedTrackerId)
        assertEquals(TrackerCapability.SEARCH, outcome.capability)
    }

    // CHALLENGE
    @Test
    fun resumeWith_routes_search_to_proposed_tracker_and_returns_its_result() = runTest {
        sdk.probeMirrorsFor("rutracker")

        val outcome = sdk.search(searchRequest { query = "ubuntu" })
        outcome as SearchOutcome.CrossTrackerFallbackProposed

        val resumed: SearchOutcome = outcome.resumeWith()

        assertTrue(resumed is SearchOutcome.Success)
        resumed as SearchOutcome.Success
        assertEquals("rutor", resumed.viaTracker)
        assertEquals(1, resumed.result.items.size)
        assertEquals("rutor-fallback-ubuntu", resumed.result.items[0].title)
    }

    // CHALLENGE
    @Test
    fun search_returns_Failure_when_no_alternative_supports_capability() = runTest {
        // Re-init the SDK with a registry that has only rutracker.
        val realCtx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val freshDb = Room.inMemoryDatabaseBuilder(realCtx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val healthRepo = MirrorHealthRepository(freshDb.mirrorHealthDao())
        val userRepo = UserMirrorRepository(freshDb.userMirrorDao())
        val mockedAssets = mockk<android.content.res.AssetManager>(relaxed = true) {
            every { open(MirrorConfigLoader.ASSET_PATH) } answers {
                ByteArrayInputStream(bundledJson.toByteArray(Charsets.UTF_8))
            }
        }
        val mockedCtx = mockk<android.content.Context>(relaxed = true) {
            every { assets } returns mockedAssets
        }
        val loader = MirrorConfigLoader(mockedCtx, userRepo)
        val rutrackerClient = FakeTrackerClient(rutrackerDescriptor)
        val onlyRutracker = DefaultTrackerRegistry().also { reg ->
            reg.register(object : TrackerClientFactory {
                override val descriptor: TrackerDescriptor = rutrackerDescriptor
                override fun create(config: PluginConfig): TrackerClient = rutrackerClient
            })
        }
        val unhealthyProbe = object : HealthProbe {
            override suspend fun probe(endpoint: MirrorUrl): HealthState = HealthState.UNHEALTHY
        }
        val holder = LavaMirrorManagerHolder(
            registry = onlyRutracker,
            configLoader = loader,
            probeFactory = LavaMirrorManagerHolder.HealthProbeFactory { unhealthyProbe },
        )
        val sdkOnly = LavaTrackerSdk(
            registry = onlyRutracker,
            mirrorManagerHolder = holder,
            mirrorHealthRepository = healthRepo,
            mirrorConfigLoader = loader,
            crossTrackerFallback = CrossTrackerFallbackPolicy(onlyRutracker),
        )

        try {
            sdkOnly.probeMirrorsFor("rutracker")
            val outcome = sdkOnly.search(searchRequest { query = "ubuntu" })
            assertTrue(
                "Expected Failure when no alternative tracker exists, got: $outcome",
                outcome is SearchOutcome.Failure,
            )
        } finally {
            freshDb.close()
        }
    }

    // CHALLENGE
    @Test
    fun search_succeeds_normally_when_mirrors_healthy() = runTest {
        // Re-init with a HEALTHY probe so executeWithFallback can run the op.
        val realCtx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val freshDb = Room.inMemoryDatabaseBuilder(realCtx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val healthRepo = MirrorHealthRepository(freshDb.mirrorHealthDao())
        val userRepo = UserMirrorRepository(freshDb.userMirrorDao())
        val mockedAssets = mockk<android.content.res.AssetManager>(relaxed = true) {
            every { open(MirrorConfigLoader.ASSET_PATH) } answers {
                ByteArrayInputStream(bundledJson.toByteArray(Charsets.UTF_8))
            }
        }
        val mockedCtx = mockk<android.content.Context>(relaxed = true) {
            every { assets } returns mockedAssets
        }
        val loader = MirrorConfigLoader(mockedCtx, userRepo)
        val rutrackerClient = FakeTrackerClient(rutrackerDescriptor).also {
            it.searchResultProvider = { req, _ ->
                SearchResult(
                    items = listOf(
                        torrent {
                            torrentId = "rutracker-result"
                            trackerId = "rutracker"
                            title = "rutracker-direct-${req.query}"
                        },
                    ),
                    totalPages = 1,
                    currentPage = 0,
                )
            }
        }
        val registry = DefaultTrackerRegistry().also { reg ->
            reg.register(object : TrackerClientFactory {
                override val descriptor: TrackerDescriptor = rutrackerDescriptor
                override fun create(config: PluginConfig): TrackerClient = rutrackerClient
            })
        }
        val healthyProbe = object : HealthProbe {
            override suspend fun probe(endpoint: MirrorUrl): HealthState = HealthState.HEALTHY
        }
        val holder = LavaMirrorManagerHolder(
            registry = registry,
            configLoader = loader,
            probeFactory = LavaMirrorManagerHolder.HealthProbeFactory { healthyProbe },
        )
        val sdkHealthy = LavaTrackerSdk(
            registry = registry,
            mirrorManagerHolder = holder,
            mirrorHealthRepository = healthRepo,
            mirrorConfigLoader = loader,
            crossTrackerFallback = CrossTrackerFallbackPolicy(registry),
        )

        try {
            sdkHealthy.probeMirrorsFor("rutracker")
            val outcome = sdkHealthy.search(searchRequest { query = "debian" })
            assertTrue(
                "Expected Success when mirror is HEALTHY, got: $outcome",
                outcome is SearchOutcome.Success,
            )
            outcome as SearchOutcome.Success
            assertEquals("rutracker-direct-debian", outcome.result.items[0].title)
        } finally {
            freshDb.close()
        }
    }
}

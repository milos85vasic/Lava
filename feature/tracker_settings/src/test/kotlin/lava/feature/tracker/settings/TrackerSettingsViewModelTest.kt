package lava.feature.tracker.settings

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import lava.database.AppDatabase
import lava.sdk.api.HealthState
import lava.sdk.api.MirrorUrl
import lava.sdk.api.PluginConfig
import lava.sdk.api.Protocol
import lava.sdk.mirror.HealthProbe
import lava.testing.rule.MainDispatcherRule
import lava.tracker.api.AuthType
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.client.CrossTrackerFallbackPolicy
import lava.tracker.client.LavaTrackerSdk
import lava.tracker.client.persistence.LavaMirrorManagerHolder
import lava.tracker.client.persistence.MirrorConfigLoader
import lava.tracker.client.persistence.MirrorHealthRepository
import lava.tracker.client.persistence.UserMirrorRepository
import lava.tracker.registry.DefaultTrackerRegistry
import lava.tracker.registry.TrackerClientFactory
import lava.tracker.testing.FakeTrackerClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.orbitmvi.orbit.test.test
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

/**
 * Real-stack test for [TrackerSettingsViewModel]. Per Seventh Law clauses
 * 1, 2, and 4(a):
 * - The SUT is the ViewModel; mockk on it is forbidden.
 * - LavaTrackerSdk + UserMirrorRepository are real instances. The SDK in
 *   turn uses real DefaultTrackerRegistry, real LavaMirrorManagerHolder,
 *   real MirrorHealthRepository, real FakeTrackerClient at the per-tracker
 *   boundary, and a stub HealthProbe (the network — lowest permitted
 *   boundary).
 * - Each test asserts on observable ViewModel state OR persisted DB
 *   state — both surfaces the user actually observes.
 *
 * Bluff-Audit:
 * - Test type: VM-CONTRACT (state assertions on the StateFlow the screen
 *   reads + persisted DB rows). The rendered-UI Challenge gate is
 *   owed (no androidTest infra yet — see :feature:CLAUDE.md).
 * - Falsifiability rehearsal: removing `userMirrorRepo.add(...)` from
 *   TrackerSettingsViewModel.AddCustomMirror caused
 *   AddCustomMirror_persists_row_to_repository to fail with
 *   "expected 1 row, got 0". Reverted before commit.
 * - Forbidden patterns: SUT mocking is absent (the ViewModel is real),
 *   LavaTrackerSdk + UserMirrorRepository are real instances, no
 *   verify-only assertions, no @Ignore.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TrackerSettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

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

        // Verified-by-construction so the UI filter (clause 6.G) does not hide it.
        override val verified: Boolean = true

        // Phase 1 α-hotfix (2026-05-06): apiSupported gates the same UI filter.
        override val apiSupported: Boolean = true
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
        override val verified: Boolean = true
        override val apiSupported: Boolean = true
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

    private lateinit var db: AppDatabase
    private lateinit var userRepo: UserMirrorRepository
    private lateinit var sdk: LavaTrackerSdk

    @Before
    fun setUp() {
        val realCtx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(realCtx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        userRepo = UserMirrorRepository(db.userMirrorDao())
        val healthRepo = MirrorHealthRepository(db.mirrorHealthDao())

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
        val rutorClient = FakeTrackerClient(rutorDescriptor)
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
        val healthyProbe = object : HealthProbe {
            override suspend fun probe(endpoint: MirrorUrl): HealthState = HealthState.HEALTHY
        }
        val holder = LavaMirrorManagerHolder(
            registry = registry,
            configLoader = loader,
            probeFactory = LavaMirrorManagerHolder.HealthProbeFactory { healthyProbe },
        )
        sdk = LavaTrackerSdk(
            registry = registry,
            mirrorManagerHolder = holder,
            mirrorHealthRepository = healthRepo,
            mirrorConfigLoader = loader,
            crossTrackerFallback = CrossTrackerFallbackPolicy(registry),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun createViewModel() = TrackerSettingsViewModel(sdk, userRepo)

    /**
     * The orbit test container's state turbine emits one state per `reduce`.
     * `load()` reduces twice: first `loading = true` (already the initial
     * state, so often coalesced) and then the populated final state. Pull
     * states until we see loading = false.
     */
    private suspend fun org.orbitmvi.orbit.test.OrbitTestContext<TrackerSettingsState, TrackerSettingsSideEffect, TrackerSettingsViewModel>.awaitLoadFinished(): TrackerSettingsState {
        var s = awaitState()
        var attempts = 0
        while (s.loading && attempts < 5) {
            s = awaitState()
            attempts++
        }
        return s
    }

    // CHALLENGE
    @Test
    fun initial_load_populates_available_trackers_from_sdk() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = createViewModel()
        viewModel.test(this) {
            runOnCreate()
            awaitLoadFinished()
            cancelAndIgnoreRemainingItems()
        }
        val state = viewModel.container.stateFlow.value
        assertEquals(setOf("rutracker", "rutor"), state.availableTrackers.map { it.trackerId }.toSet())
        assertFalse(state.loading)
    }

    // CHALLENGE
    @Test
    fun initial_load_uses_default_active_tracker() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = createViewModel()
        viewModel.test(this) {
            runOnCreate()
            awaitLoadFinished()
            cancelAndIgnoreRemainingItems()
        }
        assertEquals("rutracker", viewModel.container.stateFlow.value.activeTrackerId)
    }

    // CHALLENGE
    @Test
    fun SwitchActive_routes_through_sdk_and_updates_state() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = createViewModel()
        viewModel.test(this) {
            runOnCreate()
            viewModel.onAction(TrackerSettingsAction.SwitchActive("rutor"))
            awaitState() // SwitchActive emits exactly one reduce
            cancelAndIgnoreRemainingItems()
        }
        assertEquals("rutor", sdk.activeTrackerId())
        assertEquals("rutor", viewModel.container.stateFlow.value.activeTrackerId)
    }

    // VM-CONTRACT
    @Test
    fun OpenAddMirrorDialog_sets_dialog_visibility_and_target_tracker() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = createViewModel()
        viewModel.test(this) {
            runOnCreate()
            viewModel.onAction(TrackerSettingsAction.OpenAddMirrorDialog("rutracker"))
            awaitState()
            cancelAndIgnoreRemainingItems()
        }
        val state = viewModel.container.stateFlow.value
        assertTrue(state.showAddMirrorDialog)
        assertEquals("rutracker", state.addMirrorTargetTracker)
    }

    // VM-CONTRACT
    @Test
    fun DismissAddMirrorDialog_clears_visibility_and_target() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = createViewModel()
        viewModel.test(this) {
            runOnCreate()
            viewModel.onAction(TrackerSettingsAction.OpenAddMirrorDialog("rutracker"))
            viewModel.onAction(TrackerSettingsAction.DismissAddMirrorDialog)
            cancelAndIgnoreRemainingItems()
        }
        val state = viewModel.container.stateFlow.value
        assertFalse(state.showAddMirrorDialog)
        assertEquals(null, state.addMirrorTargetTracker)
    }

    // CHALLENGE
    @Test
    fun AddCustomMirror_persists_row_to_repository() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = createViewModel()
        viewModel.test(this) {
            runOnCreate()
            awaitLoadFinished()
            viewModel.onAction(
                TrackerSettingsAction.AddCustomMirror(
                    trackerId = "rutracker",
                    url = "https://my-mirror.example/",
                    priority = 9,
                    protocol = Protocol.HTTPS,
                ),
            )
            // AddCustomMirror reduces (showAddMirrorDialog=false) and then load() runs.
            awaitLoadFinished()
            cancelAndIgnoreRemainingItems()
        }

        val rows = userRepo.loadAll("rutracker")
        assertEquals(1, rows.size)
        assertEquals("https://my-mirror.example/", rows[0].url)
        assertEquals(9, rows[0].priority)

        val state = viewModel.container.stateFlow.value
        assertNotNull(state.customMirrors["rutracker"])
        assertEquals(1, state.customMirrors["rutracker"]?.size)
        assertEquals("https://my-mirror.example/", state.customMirrors["rutracker"]?.get(0)?.url)
    }

    // CHALLENGE
    @Test
    fun RemoveCustomMirror_deletes_row_from_repository() = runTest(mainDispatcherRule.testDispatcher) {
        userRepo.add("rutracker", "https://to-be-removed.example/", priority = 0, protocol = Protocol.HTTPS)
        val viewModel = createViewModel()
        viewModel.test(this) {
            runOnCreate()
            viewModel.onAction(TrackerSettingsAction.RemoveCustomMirror("rutracker", "https://to-be-removed.example/"))
            cancelAndIgnoreRemainingItems()
        }
        assertTrue(userRepo.loadAll("rutracker").isEmpty())
    }

    // CHALLENGE
    @Test
    fun ProbeNow_drives_mirror_health_into_state() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = createViewModel()
        viewModel.test(this) {
            runOnCreate()
            awaitLoadFinished()
            viewModel.onAction(TrackerSettingsAction.ProbeNow("rutracker"))
            awaitLoadFinished()
            cancelAndIgnoreRemainingItems()
        }
        val state = viewModel.container.stateFlow.value
        val rutrackerHealth = state.mirrorHealthByTracker["rutracker"].orEmpty()
        assertTrue(
            "ProbeNow on rutracker MUST flip its only mirror to HEALTHY (the probe stub returns HEALTHY)",
            rutrackerHealth.any { it.health == HealthState.HEALTHY },
        )
    }
}

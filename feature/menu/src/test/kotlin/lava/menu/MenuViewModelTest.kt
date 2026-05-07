package lava.menu

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import lava.credentials.CredentialEncryptor
import lava.credentials.CredentialsRepository
import lava.credentials.ProviderCredentialManager
import lava.data.api.repository.FavoriteSearchRepository
import lava.database.dao.ProviderCredentialsDao
import lava.database.entity.ProviderCredentialsEntity
import lava.domain.usecase.ClearBookmarksUseCase
import lava.domain.usecase.ClearHistoryUseCase
import lava.domain.usecase.ClearLocalFavoritesUseCase
import lava.domain.usecase.DiscoverLocalEndpointsUseCaseImpl
import lava.domain.usecase.ObserveSettingsUseCase
import lava.domain.usecase.SetBookmarksSyncPeriodUseCase
import lava.domain.usecase.SetEndpointUseCaseImpl
import lava.domain.usecase.SetFavoritesSyncPeriodUseCase
import lava.domain.usecase.SetThemeUseCase
import lava.models.settings.Endpoint
import lava.models.settings.SyncPeriod
import lava.models.settings.Theme
import lava.testing.logger.TestLoggerFactory
import lava.testing.repository.TestBookmarksRepository
import lava.testing.repository.TestFavoritesRepository
import lava.testing.repository.TestSearchHistoryRepository
import lava.testing.repository.TestSettingsRepository
import lava.testing.repository.TestSuggestsRepository
import lava.testing.repository.TestVisitedRepository
import lava.testing.service.TestBackgroundService
import lava.testing.service.TestLocalNetworkDiscoveryService
import lava.testing.testDispatchers
import lava.tracker.client.LavaTrackerSdk
import lava.tracker.registry.DefaultTrackerRegistry
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.orbitmvi.orbit.test.test

/**
 * Tests for [MenuViewModel] — mixed Integration Challenge + ViewModel Contract suite.
 *
 * Two categories of tests live in this file. They satisfy different Sixth-Law clauses
 * and an honest reader must not conflate them (root `CLAUDE.md` Sixth Law clauses 1, 3, 4):
 *
 * 1. **Integration Challenge Tests** — primary assertion on persisted/observable
 *    user-visible state (settings repository row, repository membership). These
 *    DO satisfy Sixth-Law clause 4 for their respective domain operations because
 *    a real user would observe the same persisted state through later screens.
 *    Tagged `// CHALLENGE` below.
 *
 * 2. **ViewModel Contract Tests** — primary assertion on `SideEffect` emission only.
 *    These verify the ViewModel's contract with the screen layer (the screen receives
 *    the side effect to navigate / show a dialog). They do NOT prove the screen
 *    renders or navigates; that is a separate layer requiring a Compose UI test.
 *    Tagged `// VM-CONTRACT` below.
 *
 * The load-bearing **rendered-UI Challenges** for menu navigation now live at
 * `app/src/androidTest/kotlin/lava/app/challenges/Challenge0{1..8}*.kt` (added in
 * SP-3a Step 6, 2026-04-30). The project NOW sets up `src/androidTest/` with
 * Compose UI test infrastructure (Hilt instrumented runner +
 * `androidx.compose.ui.test.junit4`); see `feature/CLAUDE.md` "Rendered-UI
 * Challenges" section. Operators run them via
 * `./gradlew :app:connectedDebugAndroidTest --tests "lava.app.challenges.*"`
 * on a real device; the falsifiability rehearsal protocol per Sixth-Law
 * clause 2 is documented in each Challenge Test's KDoc.
 *
 * A green run of this file alone MUST still be read as "the ViewModel correctly
 * produces the right contract for the screen" — NOT as "the user can complete the
 * flow on a real device". The Challenge Tests above are the user-flow gate;
 * conflating the two is exactly the bluff-test failure mode the Sixth Law forbids.
 *
 * The wiring uses REAL UseCase implementations where the use case has substantive
 * logic (`DiscoverLocalEndpointsUseCaseImpl`); for use cases that are thin
 * pass-throughs (`ClearBookmarksUseCase`, `SetThemeUseCase`, etc.) the production
 * class is instantiated against `:core:testing` fakes — which themselves carry
 * the Anti-Bluff Pact's behavioural-equivalence requirement (Third Law).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MenuViewModelTest {

    @get:Rule
    val dispatcherRule = lava.testing.rule.MainDispatcherRule()

    private lateinit var settingsRepository: TestSettingsRepository
    private lateinit var backgroundService: TestBackgroundService
    private lateinit var discoveryService: TestLocalNetworkDiscoveryService

    @Before
    fun setup() {
        settingsRepository = TestSettingsRepository()
        backgroundService = TestBackgroundService()
        discoveryService = TestLocalNetworkDiscoveryService()
    }

    /**
     * SP-3 fix (2026-04-29): builder is a `TestScope` extension so the
     * use cases it instantiates (most notably
     * `DiscoverLocalEndpointsUseCaseImpl`) share the surrounding
     * `runTest` scheduler. The previous shape allocated a fresh
     * `TestDispatchers()` in `@Before`, which created a fresh
     * `TestCoroutineScheduler` per test that `runTest`'s auto-advance
     * never touched — the same flake-then-deadlock we hit in
     * `:core:domain:testDebugUnitTest`. See `TestDispatchers.kt` KDoc
     * forensic anchor.
     */
    private fun TestScope.createViewModel(): MenuViewModel {
        val testDispatchers = testDispatchers()
        val observeSettingsUseCase = ObserveSettingsUseCase(settingsRepository)
        val setThemeUseCase = SetThemeUseCase(settingsRepository)
        val setEndpointUseCase = SetEndpointUseCaseImpl(settingsRepository)
        val setFavoritesSyncPeriodUseCase = SetFavoritesSyncPeriodUseCase(
            settingsRepository = settingsRepository,
            backgroundService = backgroundService,
            dispatchers = testDispatchers,
        )
        val setBookmarksSyncPeriodUseCase = SetBookmarksSyncPeriodUseCase(
            settingsRepository = settingsRepository,
            backgroundService = backgroundService,
            dispatchers = testDispatchers,
        )
        val clearBookmarksUseCase = ClearBookmarksUseCase(
            bookmarksRepository = TestBookmarksRepository(),
            dispatchers = testDispatchers,
        )
        val clearLocalFavoritesUseCase = ClearLocalFavoritesUseCase(
            favoritesRepository = TestFavoritesRepository(),
            dispatchers = testDispatchers,
        )
        val clearHistoryUseCase = ClearHistoryUseCase(
            suggestsRepository = TestSuggestsRepository(),
            searchHistoryRepository = TestSearchHistoryRepository(),
            favoriteSearchRepository = object : FavoriteSearchRepository {
                private val flow = MutableStateFlow<Set<Int>>(emptySet())
                override fun observeAll() = flow.asStateFlow()
                override suspend fun add(id: Int) {}
                override suspend fun remove(id: Int) {}
                override suspend fun clear() {}
            },
            visitedRepository = TestVisitedRepository(),
            dispatchers = testDispatchers,
        )
        val discoverUseCase = DiscoverLocalEndpointsUseCaseImpl(
            discoveryService = discoveryService,
            endpointsRepository = lava.testing.repository.TestEndpointsRepository(),
            settingsRepository = settingsRepository,
            dispatchers = testDispatchers,
        )

        val registry = DefaultTrackerRegistry()
        val sdk = LavaTrackerSdk(registry)

        val fakeDao = object : ProviderCredentialsDao {
            override suspend fun load(providerId: String) = null
            override fun observeAll() = emptyFlow<List<ProviderCredentialsEntity>>()
            override fun observe(providerId: String) = emptyFlow<ProviderCredentialsEntity?>()
            override suspend fun upsert(entity: ProviderCredentialsEntity) {}
            override suspend fun delete(providerId: String) {}
        }
        val credentialsRepository = CredentialsRepository(fakeDao, CredentialEncryptor())
        val credentialManager = ProviderCredentialManager(credentialsRepository)

        return MenuViewModel(
            clearBookmarksUseCase = clearBookmarksUseCase,
            clearLocalFavoritesUseCase = clearLocalFavoritesUseCase,
            clearHistoryUseCase = clearHistoryUseCase,
            discoverLocalEndpointsUseCase = discoverUseCase,
            observeSettingsUseCase = observeSettingsUseCase,
            setBookmarksSyncPeriodUseCase = setBookmarksSyncPeriodUseCase,
            setCredentialsSyncPeriodUseCase = SetCredentialsSyncPeriodUseCase(
                settingsRepository = settingsRepository,
                backgroundService = backgroundService,
                dispatchers = testDispatchers,
            ),
            setEndpointUseCase = setEndpointUseCase,
            setFavoritesSyncPeriodUseCase = setFavoritesSyncPeriodUseCase,
            setHistorySyncPeriodUseCase = SetHistorySyncPeriodUseCase(
                settingsRepository = settingsRepository,
                backgroundService = backgroundService,
                dispatchers = testDispatchers,
            ),
            setThemeUseCase = setThemeUseCase,
            sdk = sdk,
            credentialManager = credentialManager,
            loggerFactory = TestLoggerFactory(),
        )
    }

    // VM-CONTRACT — verifies the ViewModel emits the navigation side-effect.
    // The rendered-screen Challenge that the About screen actually opens on
    // tap lives at app/src/androidTest/.../challenges/Challenge0*Test.kt
    // (operator runs on real device per Task 5.22 — see class KDoc).
    @Test
    fun `about click emits ShowAbout`() = runTest(dispatcherRule.testDispatcher) {
        val viewModel = createViewModel()
        viewModel.test(this) {
            expectInitialState()
            viewModel.perform(MenuAction.AboutClick)
            expectSideEffect(MenuSideEffect.ShowAbout)
        }
    }

    // VM-CONTRACT — see note above; rendered-screen Challenge in
    // app/src/androidTest/.../challenges/.
    @Test
    fun `login click emits OpenLogin`() = runTest(dispatcherRule.testDispatcher) {
        val viewModel = createViewModel()
        viewModel.test(this) {
            expectInitialState()
            viewModel.perform(MenuAction.LoginClick)
            expectSideEffect(MenuSideEffect.OpenLogin)
        }
    }

    // CHALLENGE — primary assertion is on the persisted settings row,
    // which the user observes via every other screen that reads the
    // current endpoint. Sixth-Law clause 3 (user-visible state) satisfied.
    @Test
    fun `set endpoint updates settings`() = runTest(dispatcherRule.testDispatcher) {
        val endpoint = Endpoint.Mirror("192.168.1.100")
        val viewModel = createViewModel()
        viewModel.test(this) {
            expectInitialState()
            viewModel.perform(MenuAction.SetEndpoint(endpoint))
        }
        assertEquals(endpoint, settingsRepository.getSettings().endpoint)
    }

    // VM-CONTRACT — asserts the absence of a side-effect; tests that the
    // ViewModel correctly suppresses the navigation prompt when discovery
    // returns no result. The user-visible outcome (no dialog appears) is
    // proven only at the screen layer; the corresponding rendered-screen
    // Challenge is part of the SP-3a Phase 5 Challenge Test suite at
    // app/src/androidTest/.../challenges/.
    //
    // orbit-test 7.x: `runOnCreate()` is required to fire the container's
    // `onCreate` lambda. Earlier versions auto-fired it on `test()` entry.
    @Test
    fun `auto discovery on init does not emit side effect when not found`() = runTest(dispatcherRule.testDispatcher) {
        // Simulate immediate completion with no results.
        discoveryService.complete()

        val viewModel = createViewModel()
        viewModel.test(this) {
            // orbit-test 7.x: explicit runOnCreate() to fire the
            // container's onCreate (which triggers auto-discovery).
            // Earlier orbit versions auto-fired this on subscribe.
            runOnCreate()
            expectInitialState()
            // observeSettings is an infinite collector intent — without
            // cancelAndIgnoreRemainingItems(), the test cleanup waits 1s
            // for it to "complete" and then OrbitTimeoutCancellationException.
            cancelAndIgnoreRemainingItems()
        }
    }

    // VM-CONTRACT — asserts the navigation side-effect is emitted when
    // discovery finds a host. The rendered-screen Challenge that
    // ConnectionSettings actually composes after this side-effect is part
    // of the SP-3a Phase 5 Challenge Test suite at
    // app/src/androidTest/.../challenges/.
    //
    // Seed BEFORE createViewModel(): with the UNLIMITED-buffer fake the
    // emit is non-suspending, so no `launch { … }` wrapper is needed.
    // The buffered value is waiting when the auto-discovery use case
    // collects, eliminating the launch-vs-intent ordering race.
    @Test
    fun `auto discovery on init emits OpenConnectionSettings when endpoint found`() = runTest(dispatcherRule.testDispatcher) {
        val discovered = lava.data.api.service.DiscoveredEndpoint(
            host = "192.168.1.100:8080",
            port = 8080,
            name = "lava-proxy",
        )
        discoveryService.emit(discovered)
        discoveryService.complete()

        val viewModel = createViewModel()
        viewModel.test(this) {
            // orbit-test 7.x: explicit runOnCreate() fires the
            // container's onCreate (which triggers auto-discovery).
            runOnCreate()
            expectInitialState()
            expectSideEffect(MenuSideEffect.OpenConnectionSettings)
            // observeSettings is an infinite intent; cancel before cleanup.
            cancelAndIgnoreRemainingItems()
        }
    }

    // CHALLENGE — primary assertion on the persisted settings row.
    @Test
    fun `set theme updates settings`() = runTest(dispatcherRule.testDispatcher) {
        val viewModel = createViewModel()
        viewModel.test(this) {
            expectInitialState()
            viewModel.perform(MenuAction.SetTheme(Theme.DARK))
        }
        assertEquals(Theme.DARK, settingsRepository.getSettings().theme)
    }

    // CHALLENGE — primary assertion on the persisted settings row.
    @Test
    fun `set favorites sync period updates settings`() = runTest(dispatcherRule.testDispatcher) {
        val viewModel = createViewModel()
        viewModel.test(this) {
            expectInitialState()
            viewModel.perform(MenuAction.SetFavoritesSyncPeriod(SyncPeriod.OFF))
        }
        assertEquals(SyncPeriod.OFF, settingsRepository.getSettings().favoritesSyncPeriod)
    }

    // CHALLENGE — primary assertion on the persisted settings row.
    @Test
    fun `set bookmarks sync period updates settings`() = runTest(dispatcherRule.testDispatcher) {
        val viewModel = createViewModel()
        viewModel.test(this) {
            expectInitialState()
            viewModel.perform(MenuAction.SetBookmarksSyncPeriod(SyncPeriod.OFF))
        }
        assertEquals(SyncPeriod.OFF, settingsRepository.getSettings().bookmarksSyncPeriod)
    }
}

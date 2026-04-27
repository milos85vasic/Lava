package lava.menu

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import lava.data.api.repository.FavoriteSearchRepository
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
import lava.testing.TestDispatchers
import lava.testing.logger.TestLoggerFactory
import lava.testing.repository.TestBookmarksRepository
import lava.testing.repository.TestFavoritesRepository
import lava.testing.repository.TestSearchHistoryRepository
import lava.testing.repository.TestSettingsRepository
import lava.testing.repository.TestSuggestsRepository
import lava.testing.repository.TestVisitedRepository
import lava.testing.service.TestBackgroundService
import lava.testing.service.TestLocalNetworkDiscoveryService
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.orbitmvi.orbit.test.test

/**
 * Integration Challenge Tests for [MenuViewModel].
 *
 * These tests wire the REAL ViewModel to REAL UseCase implementations where
 * feasible. In particular, [DiscoverLocalEndpointsUseCaseImpl] is tested
 * through its real implementation so that any discovery-layer bug propagates
 * to a test failure here.
 *
 * Other use cases (clear bookmarks, set theme, etc.) use lightweight inline
 * implementations because they are not the focus of these challenge tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MenuViewModelTest {

    @get:Rule
    val dispatcherRule = lava.testing.rule.MainDispatcherRule()

    private lateinit var settingsRepository: TestSettingsRepository
    private lateinit var backgroundService: TestBackgroundService
    private lateinit var discoveryService: TestLocalNetworkDiscoveryService
    private lateinit var testDispatchers: TestDispatchers

    @Before
    fun setup() {
        settingsRepository = TestSettingsRepository()
        backgroundService = TestBackgroundService()
        discoveryService = TestLocalNetworkDiscoveryService()
        testDispatchers = TestDispatchers()
    }

    private fun createViewModel(): MenuViewModel {
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
        return MenuViewModel(
            clearBookmarksUseCase = clearBookmarksUseCase,
            clearLocalFavoritesUseCase = clearLocalFavoritesUseCase,
            clearHistoryUseCase = clearHistoryUseCase,
            discoverLocalEndpointsUseCase = discoverUseCase,
            observeSettingsUseCase = observeSettingsUseCase,
            setBookmarksSyncPeriodUseCase = setBookmarksSyncPeriodUseCase,
            setEndpointUseCase = setEndpointUseCase,
            setFavoritesSyncPeriodUseCase = setFavoritesSyncPeriodUseCase,
            setThemeUseCase = setThemeUseCase,
            loggerFactory = TestLoggerFactory(),
        )
    }

    @Test
    fun `about click emits ShowAbout`() = runTest {
        val viewModel = createViewModel()
        viewModel.test(this) {
            expectInitialState()
            viewModel.perform(MenuAction.AboutClick)
            expectSideEffect(MenuSideEffect.ShowAbout)
        }
    }

    @Test
    fun `login click emits OpenLogin`() = runTest {
        val viewModel = createViewModel()
        viewModel.test(this) {
            expectInitialState()
            viewModel.perform(MenuAction.LoginClick)
            expectSideEffect(MenuSideEffect.OpenLogin)
        }
    }

    @Test
    fun `set endpoint updates settings`() = runTest {
        val endpoint = Endpoint.Mirror("192.168.1.100")
        val viewModel = createViewModel()
        viewModel.test(this) {
            expectInitialState()
            viewModel.perform(MenuAction.SetEndpoint(endpoint))
        }
        assertEquals(endpoint, settingsRepository.getSettings().endpoint)
    }

    @Test
    fun `auto discovery on init does not emit side effect when not found`() = runTest {
        // Simulate immediate completion with no results.
        launch { discoveryService.complete() }

        val viewModel = createViewModel()
        viewModel.test(this) {
            expectInitialState()
            // No side effect expected because discovery returned NotFound.
        }
    }

    @Test
    fun `auto discovery on init emits OpenConnectionSettings when endpoint found`() = runTest {
        val discovered = lava.data.api.service.DiscoveredEndpoint(
            host = "192.168.1.100:8080",
            port = 8080,
            name = "lava-proxy",
        )
        launch {
            discoveryService.emit(discovered)
            discoveryService.complete()
        }

        val viewModel = createViewModel()
        viewModel.test(this) {
            expectInitialState()
            expectSideEffect(MenuSideEffect.OpenConnectionSettings)
        }
    }

    @Test
    fun `set theme updates settings`() = runTest {
        val viewModel = createViewModel()
        viewModel.test(this) {
            expectInitialState()
            viewModel.perform(MenuAction.SetTheme(Theme.DARK))
        }
        assertEquals(Theme.DARK, settingsRepository.getSettings().theme)
    }

    @Test
    fun `set favorites sync period updates settings`() = runTest {
        val viewModel = createViewModel()
        viewModel.test(this) {
            expectInitialState()
            viewModel.perform(MenuAction.SetFavoritesSyncPeriod(SyncPeriod.OFF))
        }
        assertEquals(SyncPeriod.OFF, settingsRepository.getSettings().favoritesSyncPeriod)
    }

    @Test
    fun `set bookmarks sync period updates settings`() = runTest {
        val viewModel = createViewModel()
        viewModel.test(this) {
            expectInitialState()
            viewModel.perform(MenuAction.SetBookmarksSyncPeriod(SyncPeriod.OFF))
        }
        assertEquals(SyncPeriod.OFF, settingsRepository.getSettings().bookmarksSyncPeriod)
    }
}

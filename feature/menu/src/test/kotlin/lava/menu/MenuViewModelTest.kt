package lava.menu

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import lava.data.api.repository.FavoriteSearchRepository
import lava.domain.usecase.ClearBookmarksUseCase
import lava.domain.usecase.ClearHistoryUseCase
import lava.domain.usecase.ClearLocalFavoritesUseCase
import lava.domain.usecase.DiscoverLocalEndpointsResult
import lava.domain.usecase.DiscoverLocalEndpointsUseCase
import lava.domain.usecase.ObserveSettingsUseCase
import lava.domain.usecase.SetBookmarksSyncPeriodUseCase
import lava.domain.usecase.SetEndpointUseCase
import lava.domain.usecase.SetFavoritesSyncPeriodUseCase
import lava.domain.usecase.SetThemeUseCase
import lava.models.settings.Endpoint
import lava.testing.TestDispatchers
import lava.testing.logger.TestLoggerFactory
import lava.testing.repository.TestBookmarksRepository
import lava.testing.repository.TestFavoritesRepository
import lava.testing.repository.TestSearchHistoryRepository
import lava.testing.repository.TestSettingsRepository
import lava.testing.repository.TestSuggestsRepository
import lava.testing.repository.TestVisitedRepository
import lava.testing.service.TestBackgroundService
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.orbitmvi.orbit.test.test

@OptIn(ExperimentalCoroutinesApi::class)
class MenuViewModelTest {

    @get:Rule
    val dispatcherRule = lava.testing.rule.MainDispatcherRule()

    private val settingsRepository = TestSettingsRepository()
    private val backgroundService = TestBackgroundService()
    private val testDispatchers = TestDispatchers()

    private val observeSettingsUseCase = ObserveSettingsUseCase(settingsRepository)

    private val setThemeUseCase = SetThemeUseCase(settingsRepository)

    private val setEndpointUseCase = object : SetEndpointUseCase {
        override suspend fun invoke(endpoint: Endpoint) {
            settingsRepository.setEndpoint(endpoint)
        }
    }

    private val setFavoritesSyncPeriodUseCase = SetFavoritesSyncPeriodUseCase(
        settingsRepository = settingsRepository,
        backgroundService = backgroundService,
        dispatchers = testDispatchers,
    )

    private val setBookmarksSyncPeriodUseCase = SetBookmarksSyncPeriodUseCase(
        settingsRepository = settingsRepository,
        backgroundService = backgroundService,
        dispatchers = testDispatchers,
    )

    private val clearBookmarksUseCase = ClearBookmarksUseCase(
        bookmarksRepository = TestBookmarksRepository(),
        dispatchers = testDispatchers,
    )

    private val clearLocalFavoritesUseCase = ClearLocalFavoritesUseCase(
        favoritesRepository = TestFavoritesRepository(),
        dispatchers = testDispatchers,
    )

    private val clearHistoryUseCase = ClearHistoryUseCase(
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

    private fun createViewModel(
        discoverResult: DiscoverLocalEndpointsResult = DiscoverLocalEndpointsResult.NotFound,
    ): MenuViewModel {
        val discoverUseCase = object : DiscoverLocalEndpointsUseCase {
            override suspend fun invoke() = discoverResult
        }
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
    fun `no local endpoint discovery does not emit side effect`() = runTest {
        val viewModel = createViewModel(
            discoverResult = DiscoverLocalEndpointsResult.NotFound,
        )
        viewModel.test(this) {
            expectInitialState()
        }
    }

    @Test
    fun `already configured local endpoint does not emit side effect`() = runTest {
        val viewModel = createViewModel(
            discoverResult = DiscoverLocalEndpointsResult.AlreadyConfigured,
        )
        viewModel.test(this) {
            expectInitialState()
        }
    }
}

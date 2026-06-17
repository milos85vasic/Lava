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
import lava.domain.usecase.SetCredentialsSyncPeriodUseCase
import lava.domain.usecase.SetEndpointUseCaseImpl
import lava.domain.usecase.SetFavoritesSyncPeriodUseCase
import lava.domain.usecase.SetHistorySyncPeriodUseCase
import lava.domain.usecase.SetThemeUseCase
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
 * Anti-bluff coverage for [MenuViewModel.observeSettings] — the reducer that
 * maps the observed [lava.models.settings.Settings] stream into the rendered
 * [MenuState] every Menu/Settings screen reads.
 *
 * The SUT is the REAL [MenuViewModel] wired to the REAL [ObserveSettingsUseCase]
 * and the REAL set-period / set-theme use cases. Only the outermost persistence
 * boundary — the [lava.data.api.repository.SettingsRepository] — is the
 * behaviorally-equivalent in-memory [TestSettingsRepository] from `:core:testing`
 * (its `setX` methods emit a copied [lava.models.settings.Settings] into the
 * observed flow exactly like the DataStore-backed production repo). No UseCase
 * and no repository is mocked (§6.J, Second Law).
 *
 * ## Why this is a real gap (not already covered)
 * [MenuViewModelTest] has CHALLENGE tests that assert on the *repository row*
 * (`settingsRepository.getSettings().theme`) after a Set action — they never
 * assert on `vm.container.stateFlow.value`, the rendered [MenuState] the screen
 * actually consumes. The `observeSettings()` reducer copies FIVE settings fields
 * (theme, favoritesSyncPeriod, bookmarksSyncPeriod, historySyncPeriod,
 * credentialsSyncPeriod) into [MenuState]; `historySyncPeriod` and
 * `credentialsSyncPeriod` are never asserted anywhere, and NO existing test
 * proves the VM propagates ANY emitted settings into its state. A mutation that
 * drops a `state.copy(...)` arm is invisible to the existing suite.
 *
 * ## Test classification
 * CHALLENGE — the primary assertion is on the rendered [MenuState], the
 * user-visible state the Settings screen renders (Sixth-Law clause 3). The
 * action drives the real Set use case which mutates the repo, and the VM's real
 * `observeSettings` collector reduces the emitted settings back into the state.
 * The rendered-UI Compose Challenge remains owed per `feature/CLAUDE.md`.
 *
 * ## Bluff-Audit
 * See task report for the per-class mutation + observed-failure record.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MenuViewModelObserveSettingsTest {

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
        // No local endpoint discovered → onCreate's discoverLocalEndpoints()
        // resolves to NotFound and emits no side effect, keeping the stream
        // free of unrelated items.
        discoveryService.complete()
    }

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
            authService = NoopAuthService(),
            loggerFactory = TestLoggerFactory(),
            analytics = object : lava.common.analytics.AnalyticsTracker {
                override fun event(name: String, params: Map<String, String>) {}
                override fun setUserId(userId: String?) {}
                override fun setProperty(key: String, value: String?) {}
                override fun recordNonFatal(throwable: Throwable, context: Map<String, String>) {}
                override fun recordWarning(message: String, context: Map<String, String>) {}
                override fun log(message: String) {}
            },
        )
    }

    /** Minimal no-op [lava.auth.api.AuthService]; this suite never exercises auth. */
    private class NoopAuthService : lava.auth.api.AuthService {
        private val authState =
            MutableStateFlow<lava.models.auth.AuthState>(lava.models.auth.AuthState.Unauthorized)
        override fun observeAuthState() = authState.asStateFlow()
        override suspend fun isAuthorized(): Boolean = false
        override suspend fun login(
            username: String,
            password: String,
            captchaSid: String?,
            captchaCode: String?,
            captchaValue: String?,
        ): lava.models.auth.AuthResult = lava.models.auth.AuthResult.Error(Exception("noop"))
        override suspend fun logout() {}
        override suspend fun signalAuthorized(name: String, avatarUrl: String?) {}
    }

    // CHALLENGE — the rendered MenuState MUST reflect the settings the
    // repository emits. This drives the real SetThemeUseCase (DARK) +
    // SetHistorySyncPeriodUseCase (DAY) + SetCredentialsSyncPeriodUseCase
    // (WEEK) through MenuViewModel actions, then asserts the VM's real
    // observeSettings() reducer propagated all three into the state the
    // Settings screen renders. credentialsSyncPeriod + historySyncPeriod are
    // asserted here for the first time in any test.
    @Test
    fun `observeSettings reduces emitted settings into rendered MenuState`() =
        runTest(dispatcherRule.testDispatcher) {
            val viewModel = createViewModel()
            viewModel.test(this) {
                runOnCreate()
                expectInitialState()

                viewModel.perform(MenuAction.SetTheme(Theme.DARK))
                viewModel.perform(MenuAction.SetHistorySyncPeriod(SyncPeriod.DAY))
                viewModel.perform(MenuAction.SetCredentialsSyncPeriod(SyncPeriod.WEEK))

                cancelAndIgnoreRemainingItems()
            }

            val rendered = viewModel.container.stateFlow.value
            assertEquals(
                "Settings screen MUST render the theme the settings stream emitted",
                Theme.DARK,
                rendered.theme,
            )
            assertEquals(
                "Settings screen MUST render the history sync period emitted",
                SyncPeriod.DAY,
                rendered.historySyncPeriod,
            )
            assertEquals(
                "Settings screen MUST render the credentials sync period emitted",
                SyncPeriod.WEEK,
                rendered.credentialsSyncPeriod,
            )
            // Untouched fields keep their default — proves the reducer copies a
            // coherent snapshot, not stale/partial state.
            assertEquals(SyncPeriod.OFF, rendered.favoritesSyncPeriod)
            assertEquals(SyncPeriod.OFF, rendered.bookmarksSyncPeriod)
        }
}

package lava.menu

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.collectLatest
import lava.auth.api.AuthService
import lava.common.analytics.AnalyticsTracker
import lava.credentials.ProviderCredentialManager
import lava.designsystem.color.ProviderColors
import lava.domain.usecase.ClearBookmarksUseCase
import lava.domain.usecase.ClearHistoryUseCase
import lava.domain.usecase.ClearLocalFavoritesUseCase
import lava.domain.usecase.DiscoverLocalEndpointsResult
import lava.domain.usecase.DiscoverLocalEndpointsUseCase
import lava.domain.usecase.ObserveSettingsUseCase
import lava.domain.usecase.SetBookmarksSyncPeriodUseCase
import lava.domain.usecase.SetCredentialsSyncPeriodUseCase
import lava.domain.usecase.SetEndpointUseCase
import lava.domain.usecase.SetFavoritesSyncPeriodUseCase
import lava.domain.usecase.SetHistorySyncPeriodUseCase
import lava.domain.usecase.SetThemeUseCase
import lava.logger.api.LoggerFactory
import lava.models.settings.Endpoint
import lava.models.settings.SyncPeriod
import lava.models.settings.Theme
import lava.tracker.api.TrackerCapability
import lava.tracker.client.LavaTrackerSdk
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.syntax.simple.intent
import org.orbitmvi.orbit.syntax.simple.postSideEffect
import org.orbitmvi.orbit.syntax.simple.reduce
import org.orbitmvi.orbit.viewmodel.container
import javax.inject.Inject

@HiltViewModel
internal class MenuViewModel @Inject constructor(
    private val clearBookmarksUseCase: ClearBookmarksUseCase,
    private val clearLocalFavoritesUseCase: ClearLocalFavoritesUseCase,
    private val clearHistoryUseCase: ClearHistoryUseCase,
    private val discoverLocalEndpointsUseCase: DiscoverLocalEndpointsUseCase,
    private val observeSettingsUseCase: ObserveSettingsUseCase,
    private val setBookmarksSyncPeriodUseCase: SetBookmarksSyncPeriodUseCase,
    private val setCredentialsSyncPeriodUseCase: SetCredentialsSyncPeriodUseCase,
    private val setEndpointUseCase: SetEndpointUseCase,
    private val setFavoritesSyncPeriodUseCase: SetFavoritesSyncPeriodUseCase,
    private val setHistorySyncPeriodUseCase: SetHistorySyncPeriodUseCase,
    private val setThemeUseCase: SetThemeUseCase,
    private val sdk: LavaTrackerSdk,
    private val credentialManager: ProviderCredentialManager,
    private val authService: AuthService,
    loggerFactory: LoggerFactory,
    private val analytics: AnalyticsTracker,
) : ViewModel(), ContainerHost<MenuState, MenuSideEffect> {
    private val logger = loggerFactory.get("MenuViewModel")

    override val container: Container<MenuState, MenuSideEffect> = container(
        initialState = MenuState(),
        onCreate = {
            observeSettings()
            discoverLocalEndpoints()
            loadProviders()
        },
    )

    fun perform(action: MenuAction) {
        logger.d { "Perform $action" }
        when (action) {
            is MenuAction.AboutClick -> onAboutClick()
            is MenuAction.ClearBookmarksConfirmation -> onClearBookmarksConfirmation()
            is MenuAction.ClearFavoritesConfirmation -> onClearFavoritesConfirmation()
            is MenuAction.ClearHistoryConfirmation -> onClearHistoryConfirmation()
            is MenuAction.ConfirmableAction -> onConfirmableAction(action)
            is MenuAction.LoginClick -> onLoginClick()
            is MenuAction.PrivacyPolicyClick -> onPrivacyPolicyClick()
            is MenuAction.RightsClick -> onRightsClick()
            is MenuAction.SendFeedbackClick -> onSendFeedbackClick()
            is MenuAction.SetBookmarksSyncPeriod -> onSetBookmarksSyncPeriod(action.syncPeriod)
            is MenuAction.SetCredentialsSyncPeriod -> onSetCredentialsSyncPeriod(action.syncPeriod)
            is MenuAction.SetEndpoint -> onSetEndpoint(action.endpoint)
            is MenuAction.SetFavoritesSyncPeriod -> onSetFavoritesSyncPeriod(action.syncPeriod)
            is MenuAction.SetHistorySyncPeriod -> onSetHistorySyncPeriod(action.syncPeriod)
            is MenuAction.SetTheme -> onSetTheme(action.theme)
            is MenuAction.TrackerSettingsClick -> onTrackerSettingsClick()
            is MenuAction.CredentialsClick -> onCredentialsClick()
            is MenuAction.OpenProviderConfig -> onOpenProviderConfig(action.providerId)
            is MenuAction.SignOut -> onSignOut(action.providerId)
            is MenuAction.ConfirmSignOut -> onConfirmSignOut(action.providerId)
        }
    }

    private fun loadProviders() = intent {
        val descriptors = sdk.listAvailableTrackers()
        val items = descriptors.map { descriptor ->
            val isAuthenticatable = TrackerCapability.AUTH_REQUIRED in descriptor.capabilities
            val authState = if (isAuthenticatable) {
                try {
                    sdk.checkAuth(descriptor.trackerId)
                } catch (e: Throwable) {
                    analytics.recordNonFatal(e, mapOf(AnalyticsTracker.Params.PROVIDER to descriptor.trackerId))
                    null
                }
            } else {
                null
            }
            val isAuthenticated = authState is lava.tracker.api.model.AuthState.Authenticated
            val credentials = try {
                credentialManager.getCredentials(descriptor.trackerId)
            } catch (e: Throwable) {
                analytics.recordNonFatal(e, mapOf(AnalyticsTracker.Params.PROVIDER to descriptor.trackerId))
                null
            }
            val username = if (isAuthenticated) {
                credentials?.username ?: descriptor.displayName
            } else {
                null
            }
            ProviderMenuItem(
                providerId = descriptor.trackerId,
                displayName = descriptor.displayName,
                username = username,
                isAuthenticated = isAuthenticated,
                color = ProviderColors.forProvider(descriptor.trackerId),
            )
        }
        reduce {
            state.copy(activeProviders = items)
        }
    }

    /** SP-3a Phase 4 (Task 4.19). */
    private fun onTrackerSettingsClick() = intent {
        postSideEffect(MenuSideEffect.OpenTrackerSettings)
    }

    /** Multi-Provider Extension. */
    private fun onCredentialsClick() = intent {
        postSideEffect(MenuSideEffect.OpenCredentials)
    }

    /** SP-4 Phase B (Task 18). */
    private fun onOpenProviderConfig(providerId: String) = intent {
        postSideEffect(MenuSideEffect.OpenProviderConfig(providerId))
    }

    private fun onSignOut(providerId: String) = intent {
        postSideEffect(MenuSideEffect.ShowSignOutConfirmation(providerId))
    }

    private fun onConfirmSignOut(providerId: String) = intent {
        try {
            authService.logout()
        } catch (e: Throwable) {
            analytics.recordNonFatal(e, mapOf(AnalyticsTracker.Params.PROVIDER to providerId))
        }
        try {
            if (sdk.activeTrackerId() == providerId) {
                sdk.logout()
            } else {
                sdk.logout(providerId)
            }
        } catch (e: Throwable) {
            analytics.recordNonFatal(e, mapOf(AnalyticsTracker.Params.PROVIDER to providerId))
        }
        try {
            credentialManager.clear(providerId)
        } catch (e: Throwable) {
            analytics.recordNonFatal(e, mapOf(AnalyticsTracker.Params.PROVIDER to providerId))
        }
        loadProviders()
        postSideEffect(MenuSideEffect.ShowSignOutSuccess)
    }

    private fun discoverLocalEndpoints() = intent {
        when (val result = discoverLocalEndpointsUseCase()) {
            is DiscoverLocalEndpointsResult.Discovered -> {
                logger.i { "Local endpoint discovered: ${result.endpoint.host}" }
                postSideEffect(MenuSideEffect.OpenConnectionSettings)
            }
            DiscoverLocalEndpointsResult.NotFound -> {
                logger.d { "No local endpoint discovered" }
            }
            is DiscoverLocalEndpointsResult.AlreadyConfigured -> {
                logger.d { "Local endpoint already configured: ${result.endpoint.host}" }
            }
        }
    }

    private fun observeSettings() = intent {
        logger.d { "Start observing settings" }
        observeSettingsUseCase().collectLatest { settings ->
            reduce {
                logger.d { "On new settings: $settings" }
                state.copy(
                    theme = settings.theme,
                    favoritesSyncPeriod = settings.favoritesSyncPeriod,
                    bookmarksSyncPeriod = settings.bookmarksSyncPeriod,
                    historySyncPeriod = settings.historySyncPeriod,
                    credentialsSyncPeriod = settings.credentialsSyncPeriod,
                )
            }
        }
    }

    private fun onAboutClick() = intent {
        postSideEffect(MenuSideEffect.ShowAbout)
    }

    private fun onConfirmableAction(action: MenuAction.ConfirmableAction) = intent {
        postSideEffect(action.toConfirmation())
    }

    private fun onClearBookmarksConfirmation() = intent {
        clearBookmarksUseCase()
    }

    private fun onClearFavoritesConfirmation() = intent {
        clearLocalFavoritesUseCase()
    }

    private fun onClearHistoryConfirmation() = intent {
        clearHistoryUseCase()
    }

    private fun onLoginClick() = intent {
        postSideEffect(MenuSideEffect.OpenLogin)
    }

    private fun onPrivacyPolicyClick() = intent {
        postSideEffect(MenuSideEffect.OpenLink(PrivacyPolicy))
    }

    private fun onRightsClick() = intent {
        postSideEffect(MenuSideEffect.OpenLink(Copyrights))
    }

    private fun onSendFeedbackClick() = intent {
        postSideEffect(MenuSideEffect.OpenLink(DeveloperEmail))
    }

    private fun onSetBookmarksSyncPeriod(period: SyncPeriod) = intent {
        setBookmarksSyncPeriodUseCase(period)
    }

    private fun onSetCredentialsSyncPeriod(period: SyncPeriod) = intent {
        setCredentialsSyncPeriodUseCase(period)
    }

    private fun onSetEndpoint(endpoint: Endpoint) = intent {
        setEndpointUseCase(endpoint)
    }

    private fun onSetFavoritesSyncPeriod(period: SyncPeriod) = intent {
        setFavoritesSyncPeriodUseCase(period)
    }

    private fun onSetHistorySyncPeriod(period: SyncPeriod) = intent {
        setHistorySyncPeriodUseCase(period)
    }

    private fun onSetTheme(theme: Theme) = intent {
        setThemeUseCase(theme)
    }

    private fun MenuAction.ConfirmableAction.toConfirmation() =
        MenuSideEffect.ShowConfirmation(title, confirmationMessage, onConfirmAction)

    companion object {
        private const val DeveloperEmail = "mailto:vasicdigital@mail.ru"
        private const val Copyrights = "https://github.com/milos85vasic/Lava/blob/master/docs/copyright_holders.md"
        private const val PrivacyPolicy = "https://github.com/milos85vasic/Lava/blob/master/docs/privacy_policy.md"
    }
}

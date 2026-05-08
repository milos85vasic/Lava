package lava.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import lava.auth.api.AuthService
import lava.common.analytics.AnalyticsTracker
import lava.credentials.ProviderCredentialManager
import lava.logger.api.LoggerFactory
import lava.tracker.api.AuthType
import lava.tracker.api.TrackerDescriptor
import lava.tracker.api.model.AuthState
import lava.tracker.api.model.LoginRequest
import lava.tracker.client.LavaTrackerSdk
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.syntax.simple.intent
import org.orbitmvi.orbit.syntax.simple.postSideEffect
import org.orbitmvi.orbit.syntax.simple.reduce
import org.orbitmvi.orbit.viewmodel.container
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val sdk: LavaTrackerSdk,
    private val credentialManager: ProviderCredentialManager,
    private val authService: AuthService,
    loggerFactory: LoggerFactory,
    private val analytics: AnalyticsTracker,
) : ViewModel(), ContainerHost<OnboardingState, OnboardingSideEffect> {
    private val logger = loggerFactory.get("OnboardingViewModel")

    override val container: Container<OnboardingState, OnboardingSideEffect> = container(
        initialState = OnboardingState(),
        onCreate = { loadProviders() },
    )

    fun perform(action: OnboardingAction) {
        logger.d { "Perform $action" }
        when (action) {
            is OnboardingAction.NextStep -> onNextStep()
            is OnboardingAction.BackStep -> onBackStep()
            is OnboardingAction.ToggleProvider -> onToggleProvider(action.providerId)
            is OnboardingAction.UsernameChanged -> onUsernameChanged(action.value)
            is OnboardingAction.PasswordChanged -> onPasswordChanged(action.value)
            is OnboardingAction.ToggleAnonymous -> onToggleAnonymous(action.enabled)
            is OnboardingAction.TestAndContinue -> onTestAndContinue()
            is OnboardingAction.Finish -> onFinish()
        }
    }

    private fun loadProviders() = intent {
        val descriptors = sdk.listAvailableTrackers().filter {
            it.verified && it.apiSupported
        }
        val items = descriptors.map { ProviderOnboardingItem(it) }
        val configs = items.associate {
            it.descriptor.trackerId to ProviderConfigState(it.descriptor.trackerId)
        }
        reduce { state.copy(providers = items, configs = configs) }
    }

    private fun onNextStep() = intent {
        when (state.step) {
            OnboardingStep.Welcome -> reduce { state.copy(step = OnboardingStep.Providers) }
            OnboardingStep.Providers -> {
                val selected = state.providers.filter { it.selected }
                if (selected.isEmpty()) return@intent
                reduce {
                    state.copy(step = OnboardingStep.Configure, currentProviderIndex = 0)
                }
            }
            OnboardingStep.Configure -> advanceToNextProvider()
            OnboardingStep.Summary -> { /* ignore */ }
        }
    }

    private fun onBackStep() = intent {
        when (state.step) {
            OnboardingStep.Welcome -> postSideEffect(OnboardingSideEffect.Finish)
            OnboardingStep.Providers -> reduce { state.copy(step = OnboardingStep.Welcome) }
            OnboardingStep.Configure -> {
                reduce { state.copy(step = OnboardingStep.Providers) }
            }
            OnboardingStep.Summary -> { /* ignore */ }
        }
    }

    private fun onToggleProvider(providerId: String) = intent {
        reduce {
            state.copy(
                providers = state.providers.map { p ->
                    if (p.descriptor.trackerId == providerId) p.copy(selected = !p.selected) else p
                },
            )
        }
    }

    private fun onUsernameChanged(value: String) = updateCurrentConfig { it.copy(username = value) }
    private fun onPasswordChanged(value: String) = updateCurrentConfig { it.copy(password = value) }
    private fun onToggleAnonymous(enabled: Boolean) = updateCurrentConfig { it.copy(useAnonymous = enabled) }

    private fun updateCurrentConfig(transform: (ProviderConfigState) -> ProviderConfigState) = intent {
        val currentId = currentProviderId()
        if (currentId == null) return@intent
        reduce {
            state.copy(configs = state.configs + (currentId to transform(state.configs[currentId]!!)))
        }
    }

    private fun onTestAndContinue() = intent {
        val currentId = currentProviderId() ?: return@intent
        val config = state.configs[currentId] ?: return@intent
        val provider = state.providers.find { it.descriptor.trackerId == currentId }?.descriptor ?: return@intent

        reduce { state.copy(connectionTestRunning = true) }

        viewModelScope.launch {
            try {
                if (provider.authType == AuthType.NONE || config.useAnonymous) {
                    sdk.switchTracker(currentId)
                    val result = sdk.checkAuth(currentId)
                    // For AuthType.NONE providers (e.g. Internet Archive), checkAuth may
                    // return null when the tracker does not implement AuthenticatableTracker.
                    // null means "auth not applicable" — treat as success. For trackers that
                    // do implement AuthenticatableTracker and return Unauthenticated, fail.
                    if (result != null && result != AuthState.Authenticated) {
                        reduce {
                            state.copy(
                                connectionTestRunning = false,
                                configs = state.configs + (currentId to config.copy(error = "Connection failed")),
                            )
                        }
                        return@launch
                    }
                } else {
                    val loginResult = sdk.login(
                        currentId,
                        LoginRequest(
                            username = config.username,
                            password = config.password,
                        ),
                    )
                    if (loginResult == null || loginResult.state != AuthState.Authenticated) {
                        reduce {
                            state.copy(
                                connectionTestRunning = false,
                                configs = state.configs + (currentId to config.copy(error = "Invalid credentials")),
                            )
                        }
                        return@launch
                    }
                    credentialManager.setPassword(currentId, config.username, config.password)
                }

                reduce {
                    state.copy(
                        connectionTestRunning = false,
                        configs = state.configs + (currentId to config.copy(configured = true, tested = true)),
                    )
                }
                advanceToNextProvider()
            } catch (e: Exception) {
                analytics.recordNonFatal(
                    e,
                    mapOf(
                        AnalyticsTracker.Params.PROVIDER to currentId,
                        AnalyticsTracker.Params.ERROR to "connection_test_failed",
                    ),
                )
                reduce {
                    state.copy(
                        connectionTestRunning = false,
                        configs = state.configs + (currentId to config.copy(error = e.message ?: "Connection error")),
                    )
                }
            }
        }
    }

    private fun advanceToNextProvider() = intent {
        val selected = state.providers.filter { it.selected }
        val nextIndex = state.currentProviderIndex + 1
        if (nextIndex < selected.size) {
            reduce { state.copy(currentProviderIndex = nextIndex) }
        } else {
            reduce { state.copy(step = OnboardingStep.Summary) }
        }
    }

    private fun onFinish() = intent {
        val configured = state.configs.filter { it.value.configured }
        val providers = state.providers
        for ((providerId, config) in configured) {
            val descriptor = providers.find { it.descriptor.trackerId == providerId }?.descriptor
            authService.signalAuthorized(
                name = if (config.useAnonymous || descriptor?.authType == AuthType.NONE) {
                    "Anonymous (${descriptor?.displayName ?: providerId})"
                } else {
                    config.username
                },
                avatarUrl = null,
            )
        }
        postSideEffect(OnboardingSideEffect.Finish)
    }

    private fun currentProviderId(): String? {
        val s = container.stateFlow.value
        val selected = s.providers.filter { it.selected }
        return selected.getOrNull(s.currentProviderIndex)?.descriptor?.trackerId
    }

    fun currentProvider(): TrackerDescriptor? {
        val id = currentProviderId() ?: return null
        val s = container.stateFlow.value
        return s.providers.find { it.descriptor.trackerId == id }?.descriptor
    }

    fun hasSelectedProviders(): Boolean = container.stateFlow.value.providers.any { it.selected }

    fun hasConfiguredProvider(): Boolean = container.stateFlow.value.configs.values.any { it.configured }
}

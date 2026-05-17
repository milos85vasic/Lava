package lava.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import lava.auth.api.AuthService
import lava.common.analytics.AnalyticsTracker
import lava.credentials.ProviderCredentialManager
import lava.database.dao.ClonedProviderDao
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
    // Bug 2 deepest-root-cause fix (2026-05-17): onboarding MUST write a
    // config row for every configured provider so downstream consumers
    // (SearchInputViewModel chip-bar, SearchResultNavigation, future
    // surfaces) see the user's onboarded set. The prior version called
    // sdk.switchTracker (anon) / sdk.login + credentialManager.setPassword
    // (creds) but NEVER wrote to provider_configs — table stayed empty
    // after onboarding, cascading into Bug 2: SearchInputViewModel
    // selectedProviders defaulted to empty → SearchResultNavigation
    // serialized empty as null → SearchResultViewModel routed to
    // observePagingData (single-tracker rutracker path) → LoadState.Error
    // "Something went wrong" for users who only onboarded anonymous.
    private val providerConfigRepository: lava.credentials.ProviderConfigRepository,
    // Sweep Finding #8 (2026-05-17, §6.L 59th): onboarding hides
    // synthetic clones because clones are an advanced post-onboarding
    // feature configured via Provider Config — not part of first-run.
    // Pre-fix: `sdk.listAvailableTrackers()` returned base + clones; the
    // wizard rendered the clones as if they were independent trackers.
    private val clonedProviderDao: ClonedProviderDao,
) : ViewModel(), ContainerHost<OnboardingState, OnboardingSideEffect> {
    private val logger = loggerFactory.get("OnboardingViewModel")

    override val container: Container<OnboardingState, OnboardingSideEffect> = container(
        initialState = OnboardingState(),
        onCreate = { loadProviders() },
    )

    fun perform(action: OnboardingAction) {
        // Per §6.H: do not toString UsernameChanged/PasswordChanged — sealed-class
        // auto-toString includes the value, which would leak credentials to logcat.
        logger.d { "Perform ${action::class.simpleName}" }
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
        // Sweep Finding #8 closure (2026-05-17): exclude synthetic
        // clones from onboarding. The clone-detection mechanism is by
        // syntheticId membership in cloned_provider (where deletedAt IS
        // NULL); ClonedTrackerDescriptor is `internal class` in
        // :core:tracker:client so we cannot use `is ClonedTrackerDescriptor`
        // from this module. The syntheticId check is the production-
        // canonical predicate the SDK itself uses to construct the clone.
        val syntheticIds = clonedProviderDao.getAll().map { it.syntheticId }.toSet()
        val descriptors = sdk.listAvailableTrackers().filter {
            it.verified && it.apiSupported && it.trackerId !in syntheticIds
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
            // §6.AB onboarding-gate enforcement: back-from-Welcome MUST NOT
            // mark onboarding complete (the prior `Finish` post would cause
            // MainActivity to call `setOnboardingComplete(true)` and route
            // to the half-functional home screen with zero providers). Post
            // ExitApp instead — the host Activity calls `finishAffinity()`,
            // app closes, next launch re-enters onboarding because
            // `onboardingComplete` was never set.
            OnboardingStep.Welcome -> postSideEffect(OnboardingSideEffect.ExitApp)
            OnboardingStep.Providers -> reduce { state.copy(step = OnboardingStep.Welcome) }
            OnboardingStep.Configure -> {
                // Walk back through the per-provider Configure pages before
                // returning to the Providers list, so a user configuring N
                // providers can revisit prior credentials without losing them.
                if (state.currentProviderIndex > 0) {
                    reduce { state.copy(currentProviderIndex = state.currentProviderIndex - 1) }
                } else {
                    reduce { state.copy(step = OnboardingStep.Providers) }
                }
            }
            OnboardingStep.Summary -> {
                // Re-enter Configure on the last selected provider so the user
                // can amend a config they've already reviewed on the summary.
                val selectedCount = state.providers.count { it.selected }
                val lastIndex = (selectedCount - 1).coerceAtLeast(0)
                reduce {
                    state.copy(step = OnboardingStep.Configure, currentProviderIndex = lastIndex)
                }
            }
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
                    logger.d { "anon path: switchTracker($currentId)" }
                    sdk.switchTracker(currentId)
                    // Anonymous mode opts out of auth; an Unauthenticated result is the user's
                    // chosen state, not a failure — do not call checkAuth here.
                } else {
                    logger.d { "cred path: login($currentId) starting" }
                    val loginResult = sdk.login(
                        currentId,
                        LoginRequest(
                            username = config.username,
                            password = config.password,
                        ),
                    )
                    logger.d { "cred path: login($currentId) result=$loginResult" }
                    // Sweep Finding #7 closure (2026-05-17, §6.L 59th):
                    // distinguish loginResult == null (tracker does not
                    // support auth — see ProviderLoginViewModel.kt:279)
                    // from loginResult.state != Authenticated (real auth
                    // failure). The pre-fix branch lumped both into
                    // "Invalid credentials", showing a misleading
                    // credentials-wrong message when the tracker has no
                    // auth path at all (e.g. a misconfigured descriptor
                    // whose declared authType is FORM_LOGIN but whose
                    // implementation returns null from `login()`).
                    //
                    // For the null case: fall through to the anonymous
                    // path — switchTracker(currentId) + skip persisting
                    // credentials. The user's tested-OK state still
                    // advances the wizard normally.
                    if (loginResult == null) {
                        logger.d {
                            "cred path: $currentId returned null from login() — " +
                                "treating as no-auth provider (Finding #7)"
                        }
                        sdk.switchTracker(currentId)
                        // Do not persist credentials — the tracker did not
                        // accept them; persisting would mislead future code
                        // (e.g. credential rotation) into believing they're
                        // valid for an auth path that doesn't exist.
                    } else if (loginResult.state != AuthState.Authenticated) {
                        reduce {
                            state.copy(
                                connectionTestRunning = false,
                                configs = state.configs + (currentId to config.copy(error = "Invalid credentials")),
                            )
                        }
                        return@launch
                    } else {
                        credentialManager.setPassword(currentId, config.username, config.password)
                    }
                }

                // Bug 2 fix (2026-05-17): persist a default provider_configs
                // row so downstream consumers (search input chip-bar, search
                // multi-provider filter) see this as a configured provider.
                // ensureDefault is idempotent — safe to call on re-onboarding.
                providerConfigRepository.ensureDefault(currentId)

                logger.d { "test ok: advance to next/Summary for $currentId" }
                reduce {
                    state.copy(
                        connectionTestRunning = false,
                        configs = state.configs + (currentId to config.copy(configured = true, tested = true)),
                    )
                }
                advanceToNextProvider()
            } catch (e: Exception) {
                logger.e(t = e) { "Connection test failed for $currentId" }
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
        // §6.AB onboarding-gate enforcement: Finish (which causes
        // MainActivity to write `setOnboardingComplete(true)` and route to
        // the main app) MUST only fire when at least one provider has been
        // both `configured` AND `tested` — i.e. probed with success. The
        // pre-fix `onFinish()` posted Finish unconditionally; the operator-
        // reported gate-bypass on Lava-Android-1.2.20-1040 (back-from-Welcome
        // marked onboarding "complete") was made worse by this branch
        // accepting Summary→Finish from a wizard where every provider was
        // skipped. Now the wizard only completes when there's something
        // real to keep.
        val verifiedProviders = state.configs.filter { it.value.configured && it.value.tested }
        if (verifiedProviders.isEmpty()) {
            // No probed-OK provider — re-enter Configure on the last
            // selected provider so the user can finish the gate. Surface a
            // user-visible error on that provider's config so the reason is
            // discoverable. This is symmetric with the Summary→back path:
            // the wizard refuses to "complete" until the gate criterion
            // (≥1 probed provider) is met.
            val selectedCount = state.providers.count { it.selected }
            val lastIndex = (selectedCount - 1).coerceAtLeast(0)
            val lastProvider = state.providers.filter { it.selected }.getOrNull(lastIndex)
            val errorMessage = "At least one provider must be configured and probed successfully before completing onboarding."
            reduce {
                state.copy(
                    step = OnboardingStep.Configure,
                    currentProviderIndex = lastIndex,
                    configs = if (lastProvider != null) {
                        state.configs + (
                            lastProvider.descriptor.trackerId to (
                                state.configs[lastProvider.descriptor.trackerId]
                                    ?: ProviderConfigState(lastProvider.descriptor.trackerId)
                                ).copy(error = errorMessage)
                            )
                    } else {
                        state.configs
                    },
                )
            }
            return@intent
        }

        val providers = state.providers
        for ((providerId, config) in verifiedProviders) {
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

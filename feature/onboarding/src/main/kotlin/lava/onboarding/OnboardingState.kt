package lava.onboarding

import lava.models.settings.Endpoint
import lava.tracker.api.TrackerDescriptor

/**
 * Steps in the onboarding wizard.
 *
 * 60th §6.L invocation (2026-05-18): `ApiSelection` inserted between
 * Welcome and Providers per operator directive — first step before
 * checking providers is discovery + selection of APIs, gated on a
 * connectivity probe before advancing to provider configuration.
 */
enum class OnboardingStep { Welcome, ApiSelection, Providers, Configure, Summary }

data class ProviderOnboardingItem(
    val descriptor: TrackerDescriptor,
    val selected: Boolean = true,
)

data class ProviderConfigState(
    val providerId: String,
    val username: String = "",
    val password: String = "",
    val useAnonymous: Boolean = false,
    val configured: Boolean = false,
    val tested: Boolean = false,
    val error: String? = null,
)

/**
 * State of the connectivity probe for the API the user has tapped.
 *
 * `Idle` — no selection yet, or selection cleared.
 * `Testing` — `ConnectionService.isReachable` running.
 * `Failure` — probe returned false or threw; user can retry / pick another.
 * (Success is implicit via step advance, not a state value.)
 */
sealed interface ApiConnectivityState {
    data object Idle : ApiConnectivityState
    data object Testing : ApiConnectivityState
    data class Failure(val reason: String) : ApiConnectivityState
}

data class OnboardingState(
    val step: OnboardingStep = OnboardingStep.Welcome,
    val providers: List<ProviderOnboardingItem> = emptyList(),
    val configs: Map<String, ProviderConfigState> = emptyMap(),
    val currentProviderIndex: Int = 0,
    val connectionTestRunning: Boolean = false,

    // ApiSelection step (60th §6.L invocation, 2026-05-18)
    val discoveredApis: List<Endpoint> = emptyList(),
    val apiDiscoveryRunning: Boolean = false,
    val selectedApi: Endpoint? = null,
    val apiConnectivity: ApiConnectivityState = ApiConnectivityState.Idle,
)

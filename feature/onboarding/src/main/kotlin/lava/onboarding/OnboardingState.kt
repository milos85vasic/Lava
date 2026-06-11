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

    // Dynamic provider discovery (Phase 5, 2026-06-11). After the
    // ApiSelection probe succeeds, the chosen API's catalogue
    // (`GET /v1/providers`) is fetched and the registry is populated so
    // [providers] reflects the API's list. When the catalogue fetch FAILS
    // (or no FetchProvidersUseCase is wired), the wizard falls back to the
    // bundled provider list and surfaces this NON-BLOCKING notice — it is
    // never a blank screen (§6.AB rendering-correctness lesson). `null`
    // means "no notice" (fetch succeeded, or bundled-only mode).
    val providerCatalogNotice: String? = null,

    // ApiSelection step (60th §6.L invocation, 2026-05-18)
    val discoveredApis: List<Endpoint> = emptyList(),
    val apiDiscoveryRunning: Boolean = false,
    val selectedApi: Endpoint? = null,
    val apiConnectivity: ApiConnectivityState = ApiConnectivityState.Idle,

    // ApiSelection — Cloud / remote server section (2026-05-31 operator request).
    // Two sections on the "Choose your API" screen: the discovered-on-network
    // list above, plus a cloud section where the user can type a remote
    // address+port OR pick a pre-installed default. Both paths funnel into the
    // SAME SelectApi → probe → persist → advance flow, so no new persistence or
    // probe plumbing is added — only this UI-input state.
    //
    // [cloudDefaults] is populated from the build-time DEFAULT_CLOUD_API config
    // (§6.R: value lives in .env → buildConfigField, never a source literal).
    // [cloudAddressInput] is the manual-entry text field; [cloudAddressError]
    // is set when the typed address cannot be parsed into an Endpoint.GoApi.
    val cloudDefaults: List<Endpoint> = emptyList(),
    val cloudAddressInput: String = "",
    val cloudAddressError: String? = null,

    // Task 3.2 (2026-06-03): "On this device" section.
    // True when the API app is installed (tracked via OnboardingScreen's
    // side-effect handler: LaunchIntent with ACTION_MAIN = installed).
    // Drives the button label:
    //   installed  → "Open Lava API app"
    //   absent     → "Install Lava API app"
    val onDeviceApiInstalled: Boolean = false,
)

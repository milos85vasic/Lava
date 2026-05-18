package lava.onboarding

import lava.models.settings.Endpoint

sealed interface OnboardingAction {
    data object NextStep : OnboardingAction
    data object BackStep : OnboardingAction
    data class ToggleProvider(val providerId: String) : OnboardingAction
    data class UsernameChanged(val value: String) : OnboardingAction
    data class PasswordChanged(val value: String) : OnboardingAction
    data class ToggleAnonymous(val enabled: Boolean) : OnboardingAction
    data object TestAndContinue : OnboardingAction
    data object Finish : OnboardingAction

    // ApiSelection step (60th §6.L invocation, 2026-05-18)
    /** User tapped an API in the discovered list — start connectivity probe. */
    data class SelectApi(val endpoint: Endpoint) : OnboardingAction

    /** User explicitly retried the connectivity probe after a failure. */
    data object RetryApiProbe : OnboardingAction
}

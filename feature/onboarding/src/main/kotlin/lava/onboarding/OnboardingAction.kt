package lava.onboarding

sealed interface OnboardingAction {
    data object NextStep : OnboardingAction
    data object BackStep : OnboardingAction
    data class ToggleProvider(val providerId: String) : OnboardingAction
    data class UsernameChanged(val value: String) : OnboardingAction
    data class PasswordChanged(val value: String) : OnboardingAction
    data class ToggleAnonymous(val enabled: Boolean) : OnboardingAction
    data object TestAndContinue : OnboardingAction
    data object Finish : OnboardingAction
}

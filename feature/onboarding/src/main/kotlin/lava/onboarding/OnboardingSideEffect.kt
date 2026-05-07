package lava.onboarding

sealed interface OnboardingSideEffect {
    data object Finish : OnboardingSideEffect
}

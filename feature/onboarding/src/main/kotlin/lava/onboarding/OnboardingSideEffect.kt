package lava.onboarding

sealed interface OnboardingSideEffect {
    /**
     * The user has successfully completed the onboarding wizard with at
     * least one provider configured AND probed successfully. The host
     * Activity should mark `onboardingComplete = true` in persistent
     * preferences and route to the main app.
     */
    data object Finish : OnboardingSideEffect

    /**
     * The user requested to leave the wizard from its first screen
     * (Welcome) without configuring any provider. Per §6.AB-spirit
     * onboarding gate enforcement (forensic anchor: 2026-05-14
     * operator-reported gate-bypass on Lava-Android-1.2.20-1040 where
     * back-from-Welcome silently marked onboarding complete), the host
     * Activity MUST treat this as "user opted out of onboarding entirely
     * for now" and call `finishAffinity()` to close the app. On next
     * launch, the unchanged `onboardingComplete = false` will cause the
     * wizard to re-appear — the canonical "until user configures at
     * least one Provider, the app keeps prompting onboarding" gate.
     */
    data object ExitApp : OnboardingSideEffect
}

package lava.onboarding

import lava.applink.LaunchDecision

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

    // Task 3.2 (2026-06-03): "On this device" side effects.

    /**
     * The API app IS installed — launch it as an explicit-component
     * [Intent] with [lava.applink.AppLinkContract.EXTRA_START_API] +
     * [lava.applink.AppLinkContract.EXTRA_RETURN_TO] extras.
     * [decision] is a [LaunchDecision.Launch] carrying the target
     * package + the pre-built extras map.
     */
    data class LaunchApiApp(val decision: LaunchDecision) : OnboardingSideEffect

    /**
     * The API app is NOT installed (or the debug variant is absent) —
     * open the Play Store listing for the release package id. The host
     * screen tries the [marketUri] first; on [android.content.ActivityNotFoundException]
     * it falls back to [webUri].
     */
    data class OpenPlayStore(
        val marketUri: String,
        val webUri: String,
    ) : OnboardingSideEffect
}

package lava.onboarding

import android.content.Intent

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

    /**
     * Launch [intent] via [android.content.Context.startActivity].
     *
     * Covers BOTH the "API app installed — launch it" case and the
     * "API app not installed — open the Firebase download page" case.
     * The [intent] is produced by [lava.applink.SiblingAppLauncher]:
     *   - installed  → [lava.applink.SiblingAppLauncher.intentToOpen]  (with
     *     [lava.applink.AppLinkContract.EXTRA_START_API] +
     *     [lava.applink.AppLinkContract.EXTRA_RETURN_TO] extras applied by
     *     the ViewModel before emitting)
     *   - not installed → [lava.applink.SiblingAppLauncher.intentToDownload]
     *     (Firebase App Distribution URL; never `market://`)
     *
     * There is no separate "OpenPlayStore" effect — both paths produce an
     * [Intent] the screen can hand to `startActivity` without any
     * URI-scheme branching logic in the UI layer.
     */
    data class LaunchIntent(val intent: Intent) : OnboardingSideEffect
}

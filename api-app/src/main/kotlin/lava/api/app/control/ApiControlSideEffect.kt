package lava.api.app.control

import lava.applink.LaunchDecision

/**
 * One-time events the Lava API screen reacts to (snackbars, clipboard, navigation).
 *
 * Distinct from [ApiControlState] (the persistent screen state) — side effects
 * fire once and are not replayed on recomposition.
 */
sealed interface ApiControlSideEffect {
    /** The access key was copied to the clipboard; show a confirmation. */
    data object KeyCopied : ApiControlSideEffect

    /** A lifecycle action failed; surface [message] to the user. */
    data class ShowError(val message: String) : ApiControlSideEffect

    /**
     * "Back to Lava client" / "Open Lava client" tapped. [decision] is the
     * result of [lava.applink.CrossAppLauncher.decideLaunch]:
     * - [LaunchDecision.Launch] → build an explicit Intent for [LaunchDecision.Launch.targetPackage]
     *   with [LaunchDecision.Launch.extras] and call startActivity.
     * - [LaunchDecision.StoreRedirect] → open the Play Store (market:// URI with
     *   https:// web fallback on ActivityNotFoundException).
     */
    data class LaunchClient(val decision: LaunchDecision) : ApiControlSideEffect
}

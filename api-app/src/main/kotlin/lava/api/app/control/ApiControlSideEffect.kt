package lava.api.app.control

import android.content.Intent

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
     * "Back to Lava client" / "Open Lava client" tapped. [intent] is the
     * ready-to-fire [Intent] produced by [lava.applink.SiblingAppLauncher]:
     * - client installed → ACTION_MAIN launcher intent with return extras
     *   ([lava.applink.AppLinkContract.EXTRA_API_HOST] /
     *   [lava.applink.AppLinkContract.EXTRA_API_PORT] when launched from client).
     * - client not installed → ACTION_VIEW intent pointing at the Firebase
     *   App Distribution download URL (never a `market://` URI).
     *
     * The screen calls `context.startActivity(intent)` directly — no URI-scheme
     * branching needed.
     */
    data class LaunchClient(val intent: Intent) : ApiControlSideEffect
}

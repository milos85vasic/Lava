package lava.api.app.control

/**
 * User intents on the Lava API landing screen ([lava.api.app.ui.ApiControlScreen]).
 *
 * Each maps to a controller lifecycle call in [ApiControlViewModel]; the
 * resulting [ApiControlState] transition is what the screen renders.
 */
sealed interface ApiControlAction {
    /** "Start" tapped — bring the embedded API up (enabled when Stopped/Error). */
    data object StartClicked : ApiControlAction

    /** "Stop" tapped — shut the embedded API down (enabled when Running). */
    data object StopClicked : ApiControlAction

    /** "Restart" tapped — stop then start (enabled when Running). */
    data object RestartClicked : ApiControlAction

    /** "Copy" tapped on the access key — request a clipboard copy + confirmation. */
    data class CopyKeyClicked(val key: String) : ApiControlAction

    /**
     * Auto-start requested programmatically — triggered by [lava.api.app.MainActivity]
     * when the launch intent carries [lava.applink.AppLinkContract.EXTRA_START_API]=true.
     * Behaves identically to [StartClicked] but is dispatched by the Activity, not
     * the user. Separated so tests can assert on the exact dispatch path without
     * ambiguity.
     */
    data object StartRequested : ApiControlAction

    /**
     * "Back to Lava client" / "Open Lava client" button tapped. The ViewModel
     * uses [lava.applink.SiblingAppLauncher] to produce a launch [android.content.Intent]
     * (installed client) or a Firebase download-page Intent (not installed), then
     * posts a [ApiControlSideEffect.LaunchClient] side effect the screen executes.
     */
    data object OpenClient : ApiControlAction
}

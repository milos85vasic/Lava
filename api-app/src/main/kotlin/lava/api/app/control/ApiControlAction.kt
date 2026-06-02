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
}

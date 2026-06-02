package lava.api.app.control

/**
 * One-time events the Lava API screen reacts to (snackbars, clipboard).
 *
 * Distinct from [ApiControlState] (the persistent screen state) — side effects
 * fire once and are not replayed on recomposition.
 */
sealed interface ApiControlSideEffect {
    /** The access key was copied to the clipboard; show a confirmation. */
    data object KeyCopied : ApiControlSideEffect

    /** A lifecycle action failed; surface [message] to the user. */
    data class ShowError(val message: String) : ApiControlSideEffect
}

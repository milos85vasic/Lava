package lava.api.app.ui

import lava.api.app.control.ApiControlState

/**
 * Maps an [ApiControlState] to the user-visible status label keys + enabled
 * states, with NO underscores in any rendered string (the labels come from
 * title-cased resources, not `enum.name`).
 *
 * Extracted as pure Kotlin so it is unit-tested without Compose / a device —
 * [lava.api.app.ui.ApiStatusLabelTest] asserts no label contains `_` and that
 * the per-state button-enablement matches the spec (Start enabled when
 * Stopped/Error; Stop+Restart enabled when Running).
 *
 * §6.AB rendering-correctness: the label is derived deliberately per state, not
 * from `state::class.simpleName` (which is "Stopped"/"Running" by luck but
 * would regress to a class-name leak if a state were renamed).
 */
object ApiStatusLabels {

    /** Human-readable, underscore-free status word for [state]. */
    fun statusText(state: ApiControlState): String = when (state) {
        ApiControlState.Stopped -> "Stopped"
        ApiControlState.Starting -> "Starting…"
        is ApiControlState.Running -> "Running"
        ApiControlState.Stopping -> "Stopping…"
        is ApiControlState.Error -> "Error"
    }

    /** Start is offered only when there is no live server (Stopped or Error). */
    fun startEnabled(state: ApiControlState): Boolean =
        state is ApiControlState.Stopped || state is ApiControlState.Error

    /** Stop is offered only while a server is up. */
    fun stopEnabled(state: ApiControlState): Boolean = state is ApiControlState.Running

    /** Restart is offered only while a server is up. */
    fun restartEnabled(state: ApiControlState): Boolean = state is ApiControlState.Running
}

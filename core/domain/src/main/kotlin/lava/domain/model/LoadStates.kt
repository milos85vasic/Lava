package lava.domain.model

import lava.models.LoadState

data class LoadStates(
    val refresh: LoadState = LoadState.NotLoading,
    val append: LoadState = LoadState.NotLoading,
    val prepend: LoadState = LoadState.NotLoading,
) {
    companion object {
        val Idle = LoadStates()
    }
}

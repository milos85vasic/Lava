package lava.favorites

sealed interface FavoritesSideEffect {
    data class OpenTopic(val id: String) : FavoritesSideEffect
}

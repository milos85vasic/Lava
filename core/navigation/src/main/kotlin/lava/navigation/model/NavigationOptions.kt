package lava.navigation.model

data class NavigationOptions(
    val showNavigationBar: Boolean = false,
) {
    companion object {
        val Empty = NavigationOptions()
    }
}

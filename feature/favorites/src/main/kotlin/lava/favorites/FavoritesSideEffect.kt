package lava.favorites

sealed interface FavoritesSideEffect {
    // LVA-070 — providerId threads the persisted source provider so the topic
    // screen routes a favorited archiveorg/gutenberg topic to HTTP_DOWNLOAD.
    data class OpenTopic(val id: String, val providerId: String?) : FavoritesSideEffect
}

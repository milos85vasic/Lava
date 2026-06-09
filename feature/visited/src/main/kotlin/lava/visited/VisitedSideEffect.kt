package lava.visited

internal sealed interface VisitedSideEffect {
    // LVA-070 — providerId threads the persisted source provider so the topic
    // screen routes a visited archiveorg/gutenberg topic to HTTP_DOWNLOAD.
    data class OpenTopic(val id: String, val providerId: String?) : VisitedSideEffect
    data object ShowFavoriteToggleError : VisitedSideEffect
}

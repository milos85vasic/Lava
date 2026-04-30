package lava.tracker.api.model

import kotlinx.serialization.Serializable

@Serializable
data class TopicPage(
    val topic: TopicDetail,
    val totalPages: Int,
    val currentPage: Int,
)

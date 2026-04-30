package lava.tracker.api.model

import kotlinx.serialization.Serializable

@Serializable
data class CommentsPage(
    val items: List<Comment>,
    val totalPages: Int,
    val currentPage: Int,
)

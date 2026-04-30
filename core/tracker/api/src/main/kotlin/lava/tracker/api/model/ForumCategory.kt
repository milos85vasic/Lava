package lava.tracker.api.model

import kotlinx.serialization.Serializable

@Serializable
data class ForumCategory(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val children: List<ForumCategory> = emptyList(),
)

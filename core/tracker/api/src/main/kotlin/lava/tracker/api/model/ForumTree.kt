package lava.tracker.api.model

import kotlinx.serialization.Serializable

@Serializable
data class ForumTree(val rootCategories: List<ForumCategory>)

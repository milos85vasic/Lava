package lava.network.dto.user

import lava.network.dto.topic.ForumTopicDto
import kotlinx.serialization.Serializable

@Serializable
data class FavoritesDto(val topics: List<ForumTopicDto>)

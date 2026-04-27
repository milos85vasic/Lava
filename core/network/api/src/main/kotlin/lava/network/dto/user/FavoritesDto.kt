package lava.network.dto.user

import kotlinx.serialization.Serializable
import lava.network.dto.topic.ForumTopicDto

@Serializable
data class FavoritesDto(val topics: List<ForumTopicDto>)

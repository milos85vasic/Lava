package lava.tracker.api.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class Comment(
    val author: String,
    val timestamp: Instant? = null,
    val body: String,
)

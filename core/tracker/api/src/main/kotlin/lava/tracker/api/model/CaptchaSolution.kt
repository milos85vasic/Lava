package lava.tracker.api.model

import kotlinx.serialization.Serializable

@Serializable
data class CaptchaSolution(val sid: String, val code: String, val value: String)

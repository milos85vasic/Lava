package lava.tracker.api.model

import kotlinx.serialization.Serializable

@Serializable
data class CaptchaChallenge(val sid: String, val code: String, val imageUrl: String)

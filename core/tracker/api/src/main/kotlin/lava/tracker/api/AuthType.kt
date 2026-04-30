package lava.tracker.api

import kotlinx.serialization.Serializable

@Serializable
enum class AuthType { NONE, FORM_LOGIN, CAPTCHA_LOGIN, OAUTH, API_KEY }

package lava.credentials.model

sealed interface CredentialSecret {
    data class UsernamePassword(val username: String, val password: String) : CredentialSecret
    data class ApiKey(val key: String) : CredentialSecret
    data class BearerToken(val token: String) : CredentialSecret
    data class CookieSession(val cookie: String) : CredentialSecret
}

enum class CredentialType { USERNAME_PASSWORD, API_KEY, BEARER_TOKEN, COOKIE_SESSION }

fun CredentialSecret.type(): CredentialType = when (this) {
    is CredentialSecret.UsernamePassword -> CredentialType.USERNAME_PASSWORD
    is CredentialSecret.ApiKey -> CredentialType.API_KEY
    is CredentialSecret.BearerToken -> CredentialType.BEARER_TOKEN
    is CredentialSecret.CookieSession -> CredentialType.COOKIE_SESSION
}

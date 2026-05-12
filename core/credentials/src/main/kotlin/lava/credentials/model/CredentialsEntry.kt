package lava.credentials.model

data class CredentialsEntry(
    val id: String,
    val displayName: String,
    val type: CredentialType,
    val secret: CredentialSecret,
    val createdAtUtc: Long,
    val updatedAtUtc: Long,
)

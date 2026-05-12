package lava.credentials.model

data class CredentialsEntry(
    val id: String,
    val displayName: String,
    val secret: CredentialSecret,
    val createdAtUtc: Long,
    val updatedAtUtc: Long,
) {
    val type: CredentialType get() = secret.type()
}

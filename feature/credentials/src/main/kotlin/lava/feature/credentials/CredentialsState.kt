package lava.feature.credentials

/**
 * MVI state for the Credentials Management screen.
 *
 * Added in Multi-Provider Extension (Task 6.7).
 */
data class CredentialsState(
    val loading: Boolean = false,
    val credentials: List<ProviderCredentialUiModel> = emptyList(),
    val selectedProvider: String? = null,
    val dialogState: CredentialDialogState? = null,
    val error: String? = null,
)

/**
 * UI model for a single provider's credential summary.
 */
data class ProviderCredentialUiModel(
    val providerId: String,
    val displayName: String,
    val authType: String,
    val isAuthenticated: Boolean,
    val username: String?,
)

/**
 * Dialog form state for creating or editing a credential.
 */
data class CredentialDialogState(
    val providerId: String,
    val providerDisplayName: String,
    val credentialType: CredentialType = CredentialType.PASSWORD,
    val label: String = "",
    val username: String = "",
    val password: String = "",
    val token: String = "",
    val apiKey: String = "",
    val apiSecret: String = "",
    val isEditing: Boolean = false,
)

enum class CredentialType {
    PASSWORD,
    TOKEN,
    API_KEY,
}

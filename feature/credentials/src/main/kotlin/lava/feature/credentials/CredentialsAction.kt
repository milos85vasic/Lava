package lava.feature.credentials

/**
 * MVI actions for the Credentials Management screen.
 *
 * Added in Multi-Provider Extension (Task 6.8).
 */
sealed interface CredentialsAction {
    data object Load : CredentialsAction
    data class SelectProvider(val providerId: String) : CredentialsAction
    data class SavePassword(
        val providerId: String,
        val username: String,
        val password: String,
    ) : CredentialsAction
    data class SaveApiKey(
        val providerId: String,
        val apiKey: String,
    ) : CredentialsAction
    data class ClearCredentials(val providerId: String) : CredentialsAction

    // Dialog actions
    data class ShowEditDialog(val providerId: String, val providerDisplayName: String) : CredentialsAction
    data object DismissDialog : CredentialsAction
    data class SetCredentialType(val type: CredentialType) : CredentialsAction
    data class SetLabel(val label: String) : CredentialsAction
    data class SetUsername(val username: String) : CredentialsAction
    data class SetPassword(val password: String) : CredentialsAction
    data class SetToken(val token: String) : CredentialsAction
    data class SetApiKey(val apiKey: String) : CredentialsAction
    data class SetApiSecret(val apiSecret: String) : CredentialsAction
    data class SubmitDialog(val providerId: String) : CredentialsAction
}

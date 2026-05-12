package lava.credentials.manager

import lava.credentials.model.CredentialSecret

sealed interface CredentialsManagerAction {
    data class FirstTimeSetup(val passphrase: String) : CredentialsManagerAction
    data class Unlock(val passphrase: String) : CredentialsManagerAction
    data object AddNew : CredentialsManagerAction
    data class Edit(val id: String) : CredentialsManagerAction
    data class Save(val displayName: String, val secret: CredentialSecret) : CredentialsManagerAction
    data class Delete(val id: String) : CredentialsManagerAction
    data object DismissEdit : CredentialsManagerAction
}

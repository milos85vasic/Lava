package lava.credentials.manager

import lava.credentials.model.CredentialsEntry

data class CredentialsManagerState(
    val needsFirstTimeSetup: Boolean = false,
    val unlocked: Boolean = false,
    val unlockError: String? = null,
    val entries: List<CredentialsEntry> = emptyList(),
    val editing: CredentialsEntry? = null,
)

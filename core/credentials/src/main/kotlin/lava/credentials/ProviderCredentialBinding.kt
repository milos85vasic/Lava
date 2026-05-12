package lava.credentials

import kotlinx.coroutines.flow.Flow

interface ProviderCredentialBinding {
    suspend fun bind(providerId: String, credentialId: String)
    suspend fun unbind(providerId: String)
    fun observe(providerId: String): Flow<String?>
}

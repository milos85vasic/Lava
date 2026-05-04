package lava.credentials

import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level orchestrator for provider credential operations.
 *
 * Consumers (ViewModels, tracker clients) interact with this class rather
 * than the repository directly, so the encryption boundary is always respected.
 *
 * Added in Multi-Provider Extension (Task 6.4).
 */
@Singleton
class ProviderCredentialManager @Inject constructor(
    private val repository: CredentialsRepository,
) {

    suspend fun getCredentials(providerId: String): ProviderCredentials? {
        return repository.load(providerId)
    }

    fun observeCredentials(providerId: String): Flow<ProviderCredentials?> {
        return repository.observe(providerId)
    }

    fun observeAll(): Flow<List<ProviderCredentials>> {
        return repository.observeAll()
    }

    suspend fun setCookie(providerId: String, cookieValue: String) {
        val existing = repository.load(providerId)
        val updated = (existing ?: emptyCredentials(providerId)).copy(
            authType = "cookie",
            cookieValue = cookieValue,
            username = null,
            password = null,
            token = null,
            apiKey = null,
            apiSecret = null,
        )
        repository.save(updated)
    }

    suspend fun setPassword(providerId: String, username: String, password: String) {
        val existing = repository.load(providerId)
        val updated = (existing ?: emptyCredentials(providerId)).copy(
            authType = "password",
            username = username,
            password = password,
            cookieValue = null,
            token = null,
            apiKey = null,
            apiSecret = null,
        )
        repository.save(updated)
    }

    suspend fun setApiKey(providerId: String, apiKey: String, apiSecret: String? = null) {
        val existing = repository.load(providerId)
        val updated = (existing ?: emptyCredentials(providerId)).copy(
            authType = "apikey",
            apiKey = apiKey,
            apiSecret = apiSecret,
            username = null,
            password = null,
            cookieValue = null,
            token = null,
        )
        repository.save(updated)
    }

    suspend fun setToken(providerId: String, token: String) {
        val existing = repository.load(providerId)
        val updated = (existing ?: emptyCredentials(providerId)).copy(
            authType = "token",
            token = token,
            username = null,
            password = null,
            cookieValue = null,
            apiKey = null,
            apiSecret = null,
        )
        repository.save(updated)
    }

    suspend fun clear(providerId: String) {
        repository.delete(providerId)
    }

    suspend fun isAuthenticated(providerId: String): Boolean {
        val creds = repository.load(providerId)
        return creds != null && creds.authType != "none" && creds.isActive
    }

    private fun emptyCredentials(providerId: String): ProviderCredentials {
        val now = System.currentTimeMillis()
        return ProviderCredentials(
            providerId = providerId,
            authType = "none",
            createdAt = now,
            updatedAt = now,
        )
    }
}

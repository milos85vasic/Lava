package lava.credentials

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import lava.database.dao.ProviderCredentialsDao
import lava.database.entity.ProviderCredentialsEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for provider credential persistence.
 *
 * Transparently encrypts/decrypts sensitive fields via [CredentialEncryptor].
 * All public methods are suspend-safe for coroutine consumers.
 *
 * Added in Multi-Provider Extension (Task 6.3).
 */
@Singleton
class CredentialsRepository @Inject constructor(
    private val dao: ProviderCredentialsDao,
    private val encryptor: CredentialEncryptor,
) {

    suspend fun load(providerId: String): ProviderCredentials? {
        return dao.load(providerId)?.toModel()
    }

    fun observeAll(): Flow<List<ProviderCredentials>> {
        return dao.observeAll().map { list -> list.map { it.toModel() } }
    }

    fun observe(providerId: String): Flow<ProviderCredentials?> {
        return dao.observe(providerId).map { it?.toModel() }
    }

    suspend fun save(credentials: ProviderCredentials) {
        dao.upsert(credentials.toEntity())
    }

    suspend fun delete(providerId: String) {
        dao.delete(providerId)
    }

    private fun ProviderCredentialsEntity.toModel(): ProviderCredentials {
        return ProviderCredentials(
            providerId = providerId,
            authType = authType,
            username = username,
            password = encryptedPassword?.let(encryptor::decrypt),
            token = encryptedToken?.let(encryptor::decrypt),
            apiKey = encryptedApiKey?.let(encryptor::decrypt),
            apiSecret = encryptedApiSecret?.let(encryptor::decrypt),
            cookieValue = cookieValue,
            expiresAt = expiresAt,
            isActive = isActive,
            lastUsedAt = lastUsedAt,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    private fun ProviderCredentials.toEntity(): ProviderCredentialsEntity {
        val now = System.currentTimeMillis()
        return ProviderCredentialsEntity(
            providerId = providerId,
            authType = authType,
            username = username,
            encryptedPassword = password?.let(encryptor::encrypt),
            encryptedToken = token?.let(encryptor::encrypt),
            encryptedApiKey = apiKey?.let(encryptor::encrypt),
            encryptedApiSecret = apiSecret?.let(encryptor::encrypt),
            cookieValue = cookieValue,
            expiresAt = expiresAt,
            isActive = isActive,
            lastUsedAt = now,
            createdAt = createdAt ?: now,
            updatedAt = now,
        )
    }
}

/**
 * Domain model for provider credentials ( decrypted in memory).
 */
data class ProviderCredentials(
    val providerId: String,
    val authType: String,
    val username: String? = null,
    val password: String? = null,
    val token: String? = null,
    val apiKey: String? = null,
    val apiSecret: String? = null,
    val cookieValue: String? = null,
    val expiresAt: Long? = null,
    val isActive: Boolean = true,
    val lastUsedAt: Long? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
)

package lava.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Encrypted credentials for a content provider (tracker or HTTP library).
 *
 * Sensitive values (password, token, api_key) are encrypted at the field
 * level with Android Keystore AES-256-GCM before storage. This entity
 * stores only metadata and the encrypted ciphertext.
 *
 * Added in Multi-Provider Extension (Task 6.1).
 */
@Entity(tableName = "provider_credentials")
data class ProviderCredentialsEntity(
    @PrimaryKey
    @ColumnInfo("provider_id")
    val providerId: String,

    @ColumnInfo("auth_type")
    val authType: String, // "none", "cookie", "token", "apikey", "password", "oauth"

    @ColumnInfo("username")
    val username: String?,

    @ColumnInfo("encrypted_password")
    val encryptedPassword: String?,

    @ColumnInfo("encrypted_token")
    val encryptedToken: String?,

    @ColumnInfo("encrypted_api_key")
    val encryptedApiKey: String?,

    @ColumnInfo("encrypted_api_secret")
    val encryptedApiSecret: String?,

    @ColumnInfo("cookie_value")
    val cookieValue: String?,

    @ColumnInfo("expires_at")
    val expiresAt: Long?,

    @ColumnInfo("is_active")
    val isActive: Boolean = true,

    @ColumnInfo("last_used_at")
    val lastUsedAt: Long?,

    @ColumnInfo("created_at")
    val createdAt: Long,

    @ColumnInfo("updated_at")
    val updatedAt: Long,
)

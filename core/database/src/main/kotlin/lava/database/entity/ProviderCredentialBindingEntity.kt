package lava.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "provider_credential_binding")
data class ProviderCredentialBindingEntity(
    @PrimaryKey val providerId: String,
    val credentialId: String,
)

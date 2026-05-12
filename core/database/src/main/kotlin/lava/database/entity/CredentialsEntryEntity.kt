package lava.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "credentials_entry")
data class CredentialsEntryEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val type: String,
    val ciphertext: ByteArray,
    val createdAt: Long,
    val updatedAt: Long,
)

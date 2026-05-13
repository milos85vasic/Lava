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
    /**
     * SP-4 Phase G (2026-05-13). When non-null, the row is soft-deleted at
     * the recorded epoch-ms timestamp. Read paths filter rows with
     * `deletedAt IS NULL`. Phase E reads soft-deleted rows from the outbox
     * to propagate removals to other devices.
     */
    val deletedAt: Long? = null,
)

package lava.credentials

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import lava.credentials.crypto.CredentialsCrypto
import lava.credentials.model.CredentialSecret
import lava.credentials.model.CredentialsEntry
import lava.database.dao.CredentialsEntryDao
import lava.database.entity.CredentialsEntryEntity
import lava.sync.SyncOutbox
import lava.sync.SyncOutboxKind
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CredentialsEntryRepositoryImpl @Inject constructor(
    private val dao: CredentialsEntryDao,
    private val keyProvider: () -> ByteArray,
    /**
     * SP-4 Phase G (2026-05-13). Sync outbox the repository enqueues
     * soft-delete events into. Nullable so unit tests that construct
     * the repo without a sync layer keep compiling; production Hilt
     * wiring always supplies it.
     */
    private val outbox: SyncOutbox? = null,
) : CredentialsEntryRepository {

    override fun observe(): Flow<List<CredentialsEntry>> =
        dao.observeAll().map { rows -> rows.map(::decode) }

    override suspend fun list(): List<CredentialsEntry> = observe().first()

    override suspend fun get(id: String): CredentialsEntry? = dao.get(id)?.let(::decode)

    override suspend fun upsert(entry: CredentialsEntry) {
        val payload = json.encodeToString(entry.secret.toWire())
        val ct = CredentialsCrypto.encrypt(keyProvider(), payload.toByteArray(Charsets.UTF_8))
        dao.upsert(
            CredentialsEntryEntity(
                id = entry.id,
                displayName = entry.displayName,
                type = entry.type.name,
                ciphertext = ct,
                createdAt = entry.createdAtUtc,
                updatedAt = entry.updatedAtUtc,
            ),
        )
    }

    /**
     * SP-4 Phase G (2026-05-13). Soft-delete: stamps `deletedAt` so the
     * row is invisible to read paths but the marker is preserved for
     * Phase E's sync upload + backup-restore semantics. Also enqueues
     * a `CREDENTIALS` outbox event with `deleted = true` so Phase E's
     * uploader can propagate the removal to other devices.
     */
    override suspend fun delete(id: String) {
        val now = System.currentTimeMillis()
        dao.softDelete(id, now)
        outbox?.enqueue(
            SyncOutboxKind.CREDENTIALS,
            json.encodeToString(WireRemoval(id = id, deletedAt = now)),
        )
    }

    @Serializable
    private data class WireRemoval(
        val id: String,
        val deletedAt: Long,
        val deleted: Boolean = true,
    )

    private fun decode(entity: CredentialsEntryEntity): CredentialsEntry {
        val pt = CredentialsCrypto.decrypt(keyProvider(), entity.ciphertext).toString(Charsets.UTF_8)
        val secret = json.decodeFromString<WireSecret>(pt).toDomain()
        return CredentialsEntry(
            id = entity.id,
            displayName = entity.displayName,
            secret = secret,
            createdAtUtc = entity.createdAt,
            updatedAtUtc = entity.updatedAt,
        )
    }

    @Serializable
    private data class WireSecret(
        val kind: String,
        val a: String = "",
        val b: String = "",
    ) {
        fun toDomain(): CredentialSecret = when (kind) {
            "up" -> CredentialSecret.UsernamePassword(a, b)
            "ak" -> CredentialSecret.ApiKey(a)
            "bt" -> CredentialSecret.BearerToken(a)
            "cs" -> CredentialSecret.CookieSession(a)
            else -> error("unknown secret kind: $kind")
        }
    }

    private fun CredentialSecret.toWire(): WireSecret = when (this) {
        is CredentialSecret.UsernamePassword -> WireSecret("up", username, password)
        is CredentialSecret.ApiKey -> WireSecret("ak", key)
        is CredentialSecret.BearerToken -> WireSecret("bt", token)
        is CredentialSecret.CookieSession -> WireSecret("cs", cookie)
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            // SP-4 Phase G: emit fields with default values so the
            // `deleted: true` flag on WireRemoval reaches the outbox
            // payload. Phase E's API handler reads this flag to route
            // upsert vs removal.
            encodeDefaults = true
        }
    }
}

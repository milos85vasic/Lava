package lava.credentials

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import lava.common.analytics.AnalyticsTracker
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
    /**
     * §6.AC non-fatal telemetry. Nullable so unit tests that construct
     * the repo without the full DI graph keep compiling; production Hilt
     * wiring always supplies it via CredentialsModule.
     */
    private val analytics: AnalyticsTracker? = null,
) : CredentialsEntryRepository {

    /**
     * Observe all credentials entries as a decrypted [Flow].
     *
     * Fix for Crashlytics FATAL 58a1335272bc4ee06595bda6302a670a (1.3.10):
     * When the [CredentialsKeyHolder] is locked, [keyProvider] throws
     * [IllegalStateException]. Previously that exception escaped the [map]
     * operator and propagated onto the main-thread Looper via
     * [ProviderConfigViewModel]'s combine-collector → FATAL crash during search.
     *
     * A locked key holder is an EXPECTED state (the user has not yet entered
     * their passphrase, or the key was evicted after the app was backgrounded).
     * It is NOT a corruption. This path emits an empty list so every downstream
     * collector (ProviderConfigViewModel, CredentialsManagerViewModel) degrades
     * gracefully to "no credentials" rather than crashing.
     *
     * Genuine decryption failures (wrong key, corrupted ciphertext —
     * [javax.crypto.AEADBadTagException]) are NOT silenced here; they are
     * distinct [java.security.GeneralSecurityException] subclasses that do NOT
     * match the [isLockedKeyHolderError] predicate and therefore propagate
     * normally through the flow so the non-fatal channel still captures them.
     */
    override fun observe(): Flow<List<CredentialsEntry>> =
        dao.observeAll().map { rows ->
            // Try to obtain the key once for this emission; if the holder is
            // locked, short-circuit to empty list without calling decode at all.
            runCatching { keyProvider() }.fold(
                onSuccess = { _ -> rows.map(::decode) },
                onFailure = { t ->
                    if (isLockedKeyHolderError(t)) {
                        // §6.AC: record a non-fatal warning so the operator can
                        // observe how often the locked path fires in production
                        // without it being an actionable crash.
                        analytics?.recordWarning(
                            "credentials key holder locked — observe() emitting empty list",
                            mapOf(
                                AnalyticsTracker.Params.MODULE to "core:credentials",
                                AnalyticsTracker.Params.OPERATION to "observe",
                                AnalyticsTracker.Params.ERROR_CLASS to t.javaClass.simpleName,
                            ),
                        )
                        emptyList()
                    } else {
                        // Not a locked-holder error — a genuine crypto failure
                        // or unexpected state; propagate so it surfaces in
                        // Crashlytics as a non-fatal (caught by the ViewModel's
                        // coroutine scope handler) rather than being silenced.
                        throw t
                    }
                },
            )
        }

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

        /**
         * Returns true when [t] is the [IllegalStateException] thrown by
         * [lava.credentials.session.CredentialsKeyHolder.require] when the
         * holder is locked. The message is the literal string produced by
         * [kotlin.error] in `CredentialsKeyHolder.require()`:
         *   "credentials key holder is locked — prompt user for passphrase first"
         *
         * We match on type + message prefix rather than catching all
         * [IllegalStateException]s so that unrelated ISEs (e.g. from Room or
         * a programmer error inside [decode]) are NOT silently swallowed.
         */
        internal fun isLockedKeyHolderError(t: Throwable): Boolean =
            t is IllegalStateException &&
                t.message?.startsWith("credentials key holder is locked") == true
    }
}

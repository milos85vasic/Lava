package lava.network.data

import io.ktor.client.plugins.logging.Logger
import lava.logger.api.LoggerFactory
import lava.network.impl.LavaAuthBlobProvider
import javax.inject.Inject

/**
 * Ktor [Logger] implementation backed by Lava's [LoggerFactory].
 *
 * **Auth-header redaction (§5 follow-up, 2026-05-13).** When the Ktor
 * Logging plugin runs at `LogLevel.ALL`, every request + response
 * header is forwarded here as a log line — including the
 * `Lava-Auth: …` header that `AuthInterceptor` adds. Per
 * `core/CLAUDE.md`'s Auth UUID memory hygiene clause, the header
 * VALUE must NEVER appear in logs.
 *
 * Redaction strategy: read the configured header NAME (parametric per
 * §6.R; lives in `.env` as `LAVA_AUTH_FIELD_NAME` and reaches us via
 * [LavaAuthBlobProvider.getFieldName]) and replace any
 * `${fieldName}: <value>` substring with
 * `${fieldName}: <redacted>` before the line reaches the underlying
 * logger. Empty field name (e.g. `StubLavaAuthBlobProvider` until
 * codegen wires the real provider) is a no-op.
 *
 * Why this is constitutional, not consumer-restricted: the
 * BLOB-access restriction in `core/CLAUDE.md` constrains
 * [LavaAuthBlobProvider.getBlob] — the secret. The header NAME is
 * non-secret (visible on the wire). Reading [getFieldName] from
 * NetworkLogger does not broaden the BLOB consumer set.
 */
internal class NetworkLogger @Inject constructor(
    loggerFactory: LoggerFactory,
    private val authProvider: LavaAuthBlobProvider,
) : Logger {
    private val logger = loggerFactory.get("NetworkLogger")

    override fun log(message: String) = logger.i { redactAuthHeader(message) }

    /**
     * Redacts `${fieldName}: <value>` substrings. Case-insensitive
     * match on the header name; value match is non-greedy so multi-
     * line log payloads only redact the auth line, not the entire
     * message. Returns the message unchanged when no auth field name
     * is configured.
     */
    internal fun redactAuthHeader(message: String): String {
        val fieldName = authProvider.getFieldName()
        if (fieldName.isBlank()) return message
        return message.replace(
            Regex("(?i)${Regex.escape(fieldName)}\\s*:\\s*\\S+"),
            "$fieldName: <redacted>",
        )
    }
}

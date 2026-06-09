package lava.common.torrent

import java.net.URLDecoder

/**
 * Validates a `magnet:` link and extracts its info-hash.
 *
 * A magnet link is valid when it carries an `xt` parameter of the form
 * `urn:btih:<hash>`, where `<hash>` is either:
 *  - 40 hex characters (the SHA-1 info-hash printed in hex), or
 *  - 32 base32 characters (RFC 4648 alphabet, the same 20-byte hash base32-encoded).
 *
 * The extracted hash is normalized to lowercase hex so it can be compared
 * against [TorrentFileValidator]'s output and the SDK's `infoHash` fields.
 * Links carrying multiple `xt` entries are accepted as long as ONE is a valid
 * `urn:btih`.
 */
class MagnetLinkValidator {

    fun validate(uri: String): DownloadValidationResult {
        if (!uri.startsWith(MAGNET_SCHEME)) {
            return DownloadValidationResult.invalid("not a magnet URI")
        }
        val query = uri.substring(MAGNET_SCHEME.length)
        if (query.isEmpty()) {
            return DownloadValidationResult.invalid("magnet has no parameters")
        }

        val xtValues = query.split('&')
            .mapNotNull { param ->
                val eq = param.indexOf('=')
                if (eq <= 0) return@mapNotNull null
                val key = param.substring(0, eq)
                if (key != XT_KEY) return@mapNotNull null
                decode(param.substring(eq + 1))
            }

        if (xtValues.isEmpty()) {
            return DownloadValidationResult.invalid("magnet missing 'xt' parameter")
        }

        // The URN namespace prefix is case-insensitive (RFC 8141): `urn:btih:`,
        // `urn:BTIH:` and `URN:Btih:` all name the same torrent. Match the prefix
        // ignoring case but keep the hash bytes verbatim (the hash's own casing is
        // normalized later by [normalizeBtih]).
        val btih = xtValues
            .filter { it.length >= BTIH_PREFIX.length && it.regionMatches(0, BTIH_PREFIX, 0, BTIH_PREFIX.length, ignoreCase = true) }
            .map { it.substring(BTIH_PREFIX.length) }
            .firstOrNull()
            ?: return DownloadValidationResult.invalid("no 'urn:btih' info-hash in 'xt'")

        val infoHash = normalizeBtih(btih)
            ?: return DownloadValidationResult.invalid(
                "btih hash '$btih' is neither 40-char hex nor 32-char base32",
            )

        return DownloadValidationResult.ok(infoHash)
    }

    private fun normalizeBtih(raw: String): String? {
        return when (raw.length) {
            HEX_LEN -> if (raw.all { it.isHexDigit() }) raw.lowercase() else null
            BASE32_LEN -> base32ToHex(raw.uppercase())
            else -> null
        }
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /** Decodes a 32-char RFC 4648 base32 string to 20 bytes → 40 lowercase hex chars. */
    private fun base32ToHex(s: String): String? {
        var buffer = 0L
        var bitsLeft = 0
        val out = ByteArray(SHA1_LEN)
        var outIdx = 0
        for (c in s) {
            val v = BASE32_ALPHABET.indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 5) or v.toLong()
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                if (outIdx >= SHA1_LEN) return null
                out[outIdx++] = ((buffer shr bitsLeft) and 0xFF).toByte()
            }
        }
        if (outIdx != SHA1_LEN) return null
        return out.joinToString("") { "%02x".format(it) }
    }

    private fun decode(value: String): String =
        try {
            URLDecoder.decode(value, Charsets.UTF_8.name())
        } catch (_: IllegalArgumentException) {
            value
        }

    private companion object {
        const val MAGNET_SCHEME = "magnet:?"
        const val XT_KEY = "xt"
        const val BTIH_PREFIX = "urn:btih:"
        const val HEX_LEN = 40
        const val BASE32_LEN = 32
        const val SHA1_LEN = 20
        const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    }
}

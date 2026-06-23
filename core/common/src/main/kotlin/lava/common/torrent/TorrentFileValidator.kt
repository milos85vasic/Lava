package lava.common.torrent

import java.security.MessageDigest

/**
 * Result of validating a download option (a `.torrent` file or a magnet link).
 *
 * @param valid whether the input is a genuinely-usable download option.
 * @param infoHashHex lowercase 40-char hex info-hash that identifies the
 *   torrent, or `null` when validation failed. For a `.torrent` file this is
 *   the SHA-1 of the verbatim bencoded `info` dictionary; for a magnet it is
 *   the normalized btih hash. The same torrent produces the same value from
 *   both surfaces, so the two can be cross-checked.
 * @param reason a human-readable reason when [valid] is false, else `null`.
 */
data class DownloadValidationResult(
    val valid: Boolean,
    val infoHashHex: String?,
    val reason: String?,
) {
    companion object {
        fun ok(infoHashHex: String) = DownloadValidationResult(true, infoHashHex, null)
        fun invalid(reason: String) = DownloadValidationResult(false, null, reason)
    }
}

/**
 * Validates that a [ByteArray] is a genuinely-valid single- or multi-file
 * `.torrent` file and computes its info-hash.
 *
 * A `.torrent` is valid when ALL of the following hold:
 *  - the bytes are well-formed bencode with a dictionary at the top level;
 *  - that dictionary contains an `info` dictionary;
 *  - `info.name` is a (non-empty) byte string;
 *  - `info.piece length` is an integer strictly greater than 0;
 *  - `info.pieces` is a byte string whose length is a POSITIVE multiple of 20
 *    (each piece's SHA-1 is exactly 20 bytes; an empty `pieces` describes a
 *    torrent with no data and is not a real download).
 *
 * The info-hash is `SHA-1(verbatim bencoded info dict)` — computed over the
 * exact original bytes, not a re-encoding, so it matches what a real
 * BitTorrent client and the magnet-link form compute.
 */
class TorrentFileValidator {

    fun validate(bytes: ByteArray): DownloadValidationResult {
        val root = try {
            BencodeParser(bytes).parseWhole()
        } catch (e: BencodeException) {
            // no-telemetry: malformed user-supplied .torrent file failing bencode parse is an
            // expected validation outcome (not an application error); this is a pure validator
            // with no AnalyticsTracker dependency available in core:common.
            return DownloadValidationResult.invalid("malformed bencode: ${e.message}")
        }

        if (root !is BNode.BDict) {
            return DownloadValidationResult.invalid("top-level value is not a dictionary")
        }

        val infoNode = root.map["info"]
            ?: return DownloadValidationResult.invalid("missing 'info' dictionary")
        if (infoNode !is BNode.BDict) {
            return DownloadValidationResult.invalid("'info' is not a dictionary")
        }

        val name = infoNode.map["name"]
        if (name !is BNode.BStr) {
            return DownloadValidationResult.invalid("'info.name' missing or not a string")
        }
        if (name.bytes.isEmpty()) {
            return DownloadValidationResult.invalid("'info.name' is empty")
        }

        val pieceLength = infoNode.map["piece length"]
        if (pieceLength !is BNode.BInt) {
            return DownloadValidationResult.invalid("'info.piece length' missing or not an integer")
        }
        if (pieceLength.value <= 0) {
            return DownloadValidationResult.invalid("'info.piece length' must be > 0")
        }

        val pieces = infoNode.map["pieces"]
        if (pieces !is BNode.BStr) {
            return DownloadValidationResult.invalid("'info.pieces' missing or not a string")
        }
        if (pieces.bytes.isEmpty() || pieces.bytes.size % SHA1_LEN != 0) {
            return DownloadValidationResult.invalid(
                "'info.pieces' length ${pieces.bytes.size} is not a positive multiple of $SHA1_LEN",
            )
        }

        // Recover the verbatim bencoded `info` dict bytes from the original input
        // so the hash matches real clients exactly.
        val infoRange = root.valueRanges["info"]
            ?: return DownloadValidationResult.invalid("cannot locate 'info' bytes")
        val infoBytes = bytes.copyOfRange(infoRange.first, infoRange.last + 1)
        val infoHash = sha1Hex(infoBytes)
        return DownloadValidationResult.ok(infoHash)
    }

    private fun sha1Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(data)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val SHA1_LEN = 20
    }
}

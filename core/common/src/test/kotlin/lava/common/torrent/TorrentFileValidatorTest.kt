package lava.common.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Real-fixture unit tests for [TorrentFileValidator].
 *
 * These tests construct genuinely-valid bencoded .torrent ByteArrays IN the
 * test (we bencode them by hand below) so the validator is exercised against
 * the exact byte layout a real BitTorrent client would produce — not a mock,
 * not a stub. The info-hash the validator computes is cross-checked against an
 * independently-computed SHA-1 of the same bencoded `info` dict, and against a
 * magnet link built from that hash, proving the two validators agree on the
 * identity of the same torrent (Anti-Bluff: primary assertion on a real,
 * user-meaningful value — the info-hash that identifies the download).
 *
 * FALSIFIABILITY REHEARSAL (Sixth Law clause 2):
 *   Weaken [TorrentFileValidator] to accept a `pieces` byte-string whose length
 *   is NOT a multiple of 20 (e.g. drop the `% 20 == 0` check). Re-run:
 *   `piecesLengthNotMultipleOf20_isRejected` then FAILS with
 *   "expected invalid result for pieces length not a multiple of 20".
 *   Revert; re-run; green.
 */
class TorrentFileValidatorTest {

    private val validator = TorrentFileValidator()

    // ---- bencode helpers (independent of the production encoder) ----

    private fun benStr(s: String): ByteArray = benBytes(s.toByteArray(Charsets.UTF_8))

    private fun benBytes(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(b.size.toString().toByteArray(Charsets.US_ASCII))
        out.write(':'.code)
        out.write(b)
        return out.toByteArray()
    }

    private fun benInt(n: Long): ByteArray =
        ("i" + n + "e").toByteArray(Charsets.US_ASCII)

    /** Builds the bencoded `info` dict. Keys MUST be emitted in lexicographic order. */
    private fun buildInfoDict(
        name: String = "ubuntu-test.iso",
        pieceLength: Long = 16384,
        pieces: ByteArray = ByteArray(20) { 0x11 }, // exactly one 20-byte SHA-1 piece
        length: Long = 12345,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write('d'.code)
        // "length"
        out.write(benStr("length"))
        out.write(benInt(length))
        // "name"
        out.write(benStr("name"))
        out.write(benStr(name))
        // "piece length"
        out.write(benStr("piece length"))
        out.write(benInt(pieceLength))
        // "pieces"
        out.write(benStr("pieces"))
        out.write(benBytes(pieces))
        out.write('e'.code)
        return out.toByteArray()
    }

    /** Wraps an info dict into a complete top-level .torrent dict with announce. */
    private fun buildTorrent(
        infoDict: ByteArray = buildInfoDict(),
        announce: String = "http://tracker.example.invalid/announce",
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write('d'.code)
        out.write(benStr("announce"))
        out.write(benStr(announce))
        out.write(benStr("info"))
        out.write(infoDict)
        out.write('e'.code)
        return out.toByteArray()
    }

    private fun sha1Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(data)
            .joinToString("") { "%02x".format(it) }

    // ---- valid cases ----

    @Test
    fun validSingleFileTorrent_validatesAndExposesInfoHash() {
        val infoDict = buildInfoDict()
        val torrent = buildTorrent(infoDict = infoDict)

        val result = validator.validate(torrent)

        assertTrue("expected valid result, got: ${result.reason}", result.valid)
        assertNull(result.reason)
        assertNotNull(result.infoHashHex)
        // info-hash is SHA-1 of the bencoded info dict, exactly
        assertEquals(sha1Hex(infoDict), result.infoHashHex)
        // 40 lowercase hex chars
        assertEquals(40, result.infoHashHex!!.length)
        assertTrue(result.infoHashHex!!.all { it in "0123456789abcdef" })
    }

    @Test
    fun infoHash_crossChecksAgainstMagnetBuiltFromSameHash() {
        val infoDict = buildInfoDict(name = "cross-check.iso")
        val torrent = buildTorrent(infoDict = infoDict)

        val fileResult = validator.validate(torrent)
        assertTrue(fileResult.valid)

        // Build a magnet from the same hash and parse it independently.
        val magnet = "magnet:?xt=urn:btih:${fileResult.infoHashHex}&dn=cross-check.iso"
        val magnetResult = MagnetLinkValidator().validate(magnet)

        assertTrue("magnet should be valid", magnetResult.valid)
        assertEquals(fileResult.infoHashHex, magnetResult.infoHashHex)
    }

    @Test
    fun validTorrentWithMultiplePieces_validates() {
        // 3 pieces × 20 bytes = 60 bytes — a positive multiple of 20.
        val infoDict = buildInfoDict(pieces = ByteArray(60) { (it % 7).toByte() })
        val result = validator.validate(buildTorrent(infoDict = infoDict))
        assertTrue(result.valid)
        assertEquals(sha1Hex(infoDict), result.infoHashHex)
    }

    // ---- invalid cases ----

    @Test
    fun truncatedBytes_isRejected() {
        val full = buildTorrent()
        val truncated = full.copyOfRange(0, full.size - 5)
        val result = validator.validate(truncated)
        assertFalse("truncated torrent must be rejected", result.valid)
        assertNotNull(result.reason)
        assertNull(result.infoHashHex)
    }

    @Test
    fun nonDictTopLevel_isRejected() {
        // A bencoded integer at the top level instead of a dict.
        val result = validator.validate(benInt(42))
        assertFalse("non-dict top level must be rejected", result.valid)
        assertNotNull(result.reason)
    }

    @Test
    fun piecesLengthNotMultipleOf20_isRejected() {
        // 25 bytes is NOT a multiple of 20.
        val infoDict = buildInfoDict(pieces = ByteArray(25) { 0x01 })
        val result = validator.validate(buildTorrent(infoDict = infoDict))
        assertFalse(
            "expected invalid result for pieces length not a multiple of 20",
            result.valid,
        )
        assertNotNull(result.reason)
    }

    @Test
    fun emptyPieces_isRejected() {
        // 0 bytes is technically a multiple of 20 but a torrent with zero pieces
        // is not a real download. Must require a POSITIVE multiple.
        val infoDict = buildInfoDict(pieces = ByteArray(0))
        val result = validator.validate(buildTorrent(infoDict = infoDict))
        assertFalse("empty pieces must be rejected", result.valid)
    }

    @Test
    fun missingInfoDict_isRejected() {
        val out = ByteArrayOutputStream()
        out.write('d'.code)
        out.write(benStr("announce"))
        out.write(benStr("http://x.invalid/a"))
        out.write('e'.code)
        val result = validator.validate(out.toByteArray())
        assertFalse("missing info dict must be rejected", result.valid)
        assertNotNull(result.reason)
    }

    @Test
    fun missingName_isRejected() {
        val out = ByteArrayOutputStream()
        out.write('d'.code)
        out.write(benStr("piece length"))
        out.write(benInt(16384))
        out.write(benStr("pieces"))
        out.write(benBytes(ByteArray(20)))
        out.write('e'.code)
        val torrent = buildTorrent(infoDict = out.toByteArray())
        val result = validator.validate(torrent)
        assertFalse("missing name must be rejected", result.valid)
    }

    @Test
    fun pieceLengthZero_isRejected() {
        val infoDict = buildInfoDict(pieceLength = 0)
        val result = validator.validate(buildTorrent(infoDict = infoDict))
        assertFalse("piece length must be > 0", result.valid)
    }

    @Test
    fun emptyInput_isRejected() {
        val result = validator.validate(ByteArray(0))
        assertFalse("empty input must be rejected", result.valid)
        assertNotNull(result.reason)
    }
}

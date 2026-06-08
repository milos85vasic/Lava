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

    /** Bencodes a list of already-bencoded elements: `l<elements>e`. */
    private fun benList(vararg elements: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write('l'.code)
        elements.forEach { out.write(it) }
        out.write('e'.code)
        return out.toByteArray()
    }

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

    // ---- validator edge cases: structure ----

    @Test
    fun torrentWithNestedListsAndDicts_validates() {
        // Real .torrent files commonly carry an "announce-list" = list of lists
        // of strings, and a nested "httpseeds" list. The validator must accept
        // arbitrary well-formed sibling structures and still hash the info dict.
        val infoDict = buildInfoDict(name = "nested.iso")
        val out = ByteArrayOutputStream()
        out.write('d'.code)
        out.write(benStr("announce"))
        out.write(benStr("http://tracker.example.invalid/announce"))
        // announce-list: l l 4:tier e e  (list of lists of byte strings)
        out.write(benStr("announce-list"))
        out.write(
            benList(
                benList(benStr("http://a.invalid/x"), benStr("http://b.invalid/y")),
                benList(benStr("udp://c.invalid:80")),
            ),
        )
        out.write(benStr("info"))
        out.write(infoDict)
        out.write('e'.code)

        val result = validator.validate(out.toByteArray())

        assertTrue("nested structures must be accepted, got: ${result.reason}", result.valid)
        assertEquals(sha1Hex(infoDict), result.infoHashHex)
    }

    @Test
    fun trailingGarbageAfterTopDict_isRejected() {
        val torrent = buildTorrent()
        val withGarbage = torrent + "junk".toByteArray(Charsets.US_ASCII)
        val result = validator.validate(withGarbage)
        assertFalse("trailing bytes after the top-level dict must be rejected", result.valid)
        assertNotNull(result.reason)
        assertNull(result.infoHashHex)
    }

    @Test
    fun emptyInfoDict_isRejected() {
        // info = `de` (an empty dict) → no name → invalid.
        val torrent = buildTorrent(infoDict = "de".toByteArray(Charsets.US_ASCII))
        val result = validator.validate(torrent)
        assertFalse("empty info dict must be rejected", result.valid)
        assertNotNull(result.reason)
    }

    @Test
    fun infoIsNotADictionary_isRejected() {
        // info = integer instead of dict.
        val out = ByteArrayOutputStream()
        out.write('d'.code)
        out.write(benStr("info"))
        out.write(benInt(7))
        out.write('e'.code)
        val result = validator.validate(out.toByteArray())
        assertFalse("'info' that is not a dict must be rejected", result.valid)
        assertNotNull(result.reason)
    }

    @Test
    fun missingPieceLength_isRejected() {
        // info dict with name + pieces but no "piece length" (keys lex-ordered).
        val out = ByteArrayOutputStream()
        out.write('d'.code)
        out.write(benStr("name"))
        out.write(benStr("nolen.iso"))
        out.write(benStr("pieces"))
        out.write(benBytes(ByteArray(20)))
        out.write('e'.code)
        val result = validator.validate(buildTorrent(infoDict = out.toByteArray()))
        assertFalse("missing 'piece length' must be rejected", result.valid)
        assertNotNull(result.reason)
    }

    @Test
    fun pieceLengthNotAnInteger_isRejected() {
        // "piece length" present but a byte string instead of an integer.
        val out = ByteArrayOutputStream()
        out.write('d'.code)
        out.write(benStr("name"))
        out.write(benStr("badlen.iso"))
        out.write(benStr("piece length"))
        out.write(benStr("16384")) // a STRING, not an integer
        out.write(benStr("pieces"))
        out.write(benBytes(ByteArray(20)))
        out.write('e'.code)
        val result = validator.validate(buildTorrent(infoDict = out.toByteArray()))
        assertFalse("'piece length' that is not an integer must be rejected", result.valid)
    }

    @Test
    fun piecesNotAString_isRejected() {
        // "pieces" present but an integer instead of a byte string.
        val out = ByteArrayOutputStream()
        out.write('d'.code)
        out.write(benStr("name"))
        out.write(benStr("badpieces.iso"))
        out.write(benStr("piece length"))
        out.write(benInt(16384))
        out.write(benStr("pieces"))
        out.write(benInt(20))
        out.write('e'.code)
        val result = validator.validate(buildTorrent(infoDict = out.toByteArray()))
        assertFalse("'pieces' that is not a string must be rejected", result.valid)
    }

    // ---- validator edge cases: pieces-length boundary ----

    @Test
    fun piecesLengthExactly20_validates() {
        val infoDict = buildInfoDict(pieces = ByteArray(20) { 0x05 })
        val result = validator.validate(buildTorrent(infoDict = infoDict))
        assertTrue("exactly 20 bytes (one piece) must validate", result.valid)
        assertEquals(sha1Hex(infoDict), result.infoHashHex)
    }

    @Test
    fun piecesLengthExactly40_validates() {
        // 40 bytes = exactly two 20-byte SHA-1 pieces.
        val infoDict = buildInfoDict(pieces = ByteArray(40) { (it % 5).toByte() })
        val result = validator.validate(buildTorrent(infoDict = infoDict))
        assertTrue("exactly 40 bytes (two pieces) must validate", result.valid)
        assertEquals(sha1Hex(infoDict), result.infoHashHex)
    }

    @Test
    fun piecesLength21_isRejected() {
        // 21 bytes is one-past a multiple of 20.
        val infoDict = buildInfoDict(pieces = ByteArray(21) { 0x02 })
        val result = validator.validate(buildTorrent(infoDict = infoDict))
        assertFalse("21 bytes (not a multiple of 20) must be rejected", result.valid)
        assertNotNull(result.reason)
    }

    // ---- validator edge cases: name bytes ----

    @Test
    fun nonAsciiNameBytes_validatesAndHashesVerbatim() {
        // A name carrying raw non-ASCII (Cyrillic + an emoji) bytes. The
        // validator only requires a non-empty byte string; the info-hash is
        // SHA-1 of the verbatim bytes regardless of the name's encoding.
        val rawName = "Фильм-релиз 🎬".toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        out.write('d'.code)
        out.write(benStr("name"))
        out.write(benBytes(rawName))
        out.write(benStr("piece length"))
        out.write(benInt(16384))
        out.write(benStr("pieces"))
        out.write(benBytes(ByteArray(20) { 0x09 }))
        out.write('e'.code)
        val infoDict = out.toByteArray()

        val result = validator.validate(buildTorrent(infoDict = infoDict))

        assertTrue("non-ASCII name bytes must validate, got: ${result.reason}", result.valid)
        assertEquals(sha1Hex(infoDict), result.infoHashHex)
    }

    // ---- bencode parser edge cases (internal, same-module access) ----

    @Test
    fun bencode_negativeInteger_parses() {
        val node = BencodeParser("i-42e".toByteArray(Charsets.US_ASCII)).parseWhole()
        assertTrue(node is BNode.BInt)
        assertEquals(-42L, (node as BNode.BInt).value)
    }

    @Test
    fun bencode_zeroInteger_parses() {
        val node = BencodeParser("i0e".toByteArray(Charsets.US_ASCII)).parseWhole()
        assertEquals(0L, (node as BNode.BInt).value)
    }

    @Test(expected = BencodeException::class)
    fun bencode_leadingZeroInteger_isRejected() {
        // `i03e` — leading zero is malformed bencode.
        BencodeParser("i03e".toByteArray(Charsets.US_ASCII)).parseWhole()
    }

    @Test(expected = BencodeException::class)
    fun bencode_negativeZeroInteger_isRejected() {
        BencodeParser("i-0e".toByteArray(Charsets.US_ASCII)).parseWhole()
    }

    @Test(expected = BencodeException::class)
    fun bencode_negativeLeadingZeroInteger_isRejected() {
        // `i-03e` — leading zero after the minus sign.
        BencodeParser("i-03e".toByteArray(Charsets.US_ASCII)).parseWhole()
    }

    @Test(expected = BencodeException::class)
    fun bencode_byteStringLeadingZeroLength_isRejected() {
        // `01:a` — leading zero in a byte-string length prefix.
        BencodeParser("01:a".toByteArray(Charsets.US_ASCII)).parseWhole()
    }

    @Test
    fun bencode_emptyByteString_parses() {
        // `0:` is a valid empty byte string.
        val node = BencodeParser("0:".toByteArray(Charsets.US_ASCII)).parseWhole()
        assertTrue(node is BNode.BStr)
        assertEquals(0, (node as BNode.BStr).bytes.size)
    }

    @Test
    fun bencode_emptyListAndEmptyDict_parse() {
        val list = BencodeParser("le".toByteArray(Charsets.US_ASCII)).parseWhole()
        assertTrue(list is BNode.BList)
        assertTrue((list as BNode.BList).items.isEmpty())

        val dict = BencodeParser("de".toByteArray(Charsets.US_ASCII)).parseWhole()
        assertTrue(dict is BNode.BDict)
        assertTrue((dict as BNode.BDict).map.isEmpty())
    }

    @Test
    fun bencode_deeplyNestedStructure_parses() {
        // d 1:a l i1e d 1:b l i2e e e e e  → dict{a: [1, dict{b: [2]}]}
        val bytes = "d1:ali1ed1:bli2eeeee".toByteArray(Charsets.US_ASCII)
        val node = BencodeParser(bytes).parseWhole()
        assertTrue(node is BNode.BDict)
        val outer = (node as BNode.BDict).map["a"]
        assertTrue(outer is BNode.BList)
        val items = (outer as BNode.BList).items
        assertEquals(2, items.size)
        assertEquals(1L, (items[0] as BNode.BInt).value)
        val innerDict = items[1] as BNode.BDict
        val innerList = innerDict.map["b"] as BNode.BList
        assertEquals(2L, (innerList.items[0] as BNode.BInt).value)
    }

    @Test(expected = BencodeException::class)
    fun bencode_trailingBytesAfterValue_isRejected() {
        // `i1ex` — a trailing byte after a complete top-level value.
        BencodeParser("i1ex".toByteArray(Charsets.US_ASCII)).parseWhole()
    }

    @Test(expected = BencodeException::class)
    fun bencode_dictKeyNotAByteString_isRejected() {
        // `di1ei2ee` — dict key is an integer, not a byte string.
        BencodeParser("di1ei2ee".toByteArray(Charsets.US_ASCII)).parseWhole()
    }
}

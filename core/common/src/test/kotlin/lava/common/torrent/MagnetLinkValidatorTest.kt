package lava.common.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MagnetLinkValidator].
 *
 * Covers the two btih encodings BitTorrent magnets use in the wild: 40-char
 * hex and 32-char base32. The normalized info-hash MUST be lowercase hex in
 * both cases so it can be compared against the [TorrentFileValidator] output
 * and the SDK's existing `infoHash` fields.
 *
 * FALSIFIABILITY REHEARSAL (Sixth Law clause 2):
 *   Weaken [MagnetLinkValidator] to accept any xt length (drop the 40-hex /
 *   32-base32 length check). Re-run `badHashLength_isRejected` then FAILS with
 *   "magnet with bad hash length must be rejected". Revert; re-run; green.
 */
class MagnetLinkValidatorTest {

    private val validator = MagnetLinkValidator()

    @Test
    fun validHexMagnet_normalizesToLowercaseHex() {
        val hash = "0123456789ABCDEF0123456789ABCDEF01234567" // 40 hex, upper-case
        val magnet = "magnet:?xt=urn:btih:$hash&dn=Some+Name&tr=http%3A%2F%2Ftr.invalid"
        val result = validator.validate(magnet)

        assertTrue("expected valid magnet, got: ${result.reason}", result.valid)
        assertNull(result.reason)
        assertEquals(hash.lowercase(), result.infoHashHex)
        assertEquals(40, result.infoHashHex!!.length)
    }

    @Test
    fun validBase32Magnet_decodesTo40HexChars() {
        // A 32-char base32 btih. Base32 alphabet A–Z, 2–7. Decodes to 20 bytes → 40 hex.
        val base32 = "MFRGGZDFMZTWQ2LKNNWG23TPOBYXE43U" // 32 base32 chars
        val magnet = "magnet:?xt=urn:btih:$base32"
        val result = validator.validate(magnet)

        assertTrue("expected valid base32 magnet, got: ${result.reason}", result.valid)
        assertEquals(40, result.infoHashHex!!.length)
        assertTrue(result.infoHashHex!!.all { it in "0123456789abcdef" })
    }

    @Test
    fun base32AndEquivalentHex_produceSameInfoHash() {
        // "MFRGGZDFMZTWQ2LKNNWG23TPOBYXE43U" base32-decodes to the bytes of the
        // ASCII string "abcdefghijklmnopqrst" (20 bytes) — independently verifiable.
        val expectedHex = "abcdefghijklmnopqrst".toByteArray(Charsets.US_ASCII)
            .joinToString("") { "%02x".format(it) }

        val base32Magnet = "magnet:?xt=urn:btih:MFRGGZDFMZTWQ2LKNNWG23TPOBYXE43U"
        val hexMagnet = "magnet:?xt=urn:btih:$expectedHex"

        val fromBase32 = validator.validate(base32Magnet)
        val fromHex = validator.validate(hexMagnet)

        assertTrue(fromBase32.valid)
        assertTrue(fromHex.valid)
        assertEquals(expectedHex, fromBase32.infoHashHex)
        assertEquals(fromBase32.infoHashHex, fromHex.infoHashHex)
    }

    @Test
    fun missingXt_isRejected() {
        val magnet = "magnet:?dn=NoHashHere&tr=http%3A%2F%2Ftr.invalid"
        val result = validator.validate(magnet)
        assertFalse("magnet without xt must be rejected", result.valid)
        assertNotNull(result.reason)
        assertNull(result.infoHashHex)
    }

    @Test
    fun badHashLength_isRejected() {
        // 39 hex chars — not 40, not a valid base32 length either.
        val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef0123456"
        val result = validator.validate(magnet)
        assertFalse("magnet with bad hash length must be rejected", result.valid)
        assertNotNull(result.reason)
    }

    @Test
    fun notAMagnetScheme_isRejected() {
        val result = validator.validate("http://example.invalid/file.torrent")
        assertFalse("non-magnet URI must be rejected", result.valid)
        assertNotNull(result.reason)
    }

    @Test
    fun hexWithNonHexCharacters_isRejected() {
        // 40 chars but contains 'g' and 'z' which are not hex.
        val magnet = "magnet:?xt=urn:btih:zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"
        val result = validator.validate(magnet)
        assertFalse("non-hex 40-char xt must be rejected", result.valid)
    }

    @Test
    fun emptyString_isRejected() {
        val result = validator.validate("")
        assertFalse(result.valid)
        assertNotNull(result.reason)
    }

    @Test
    fun multipleXtParams_picksBtihUrn() {
        // Some magnets carry multiple xt entries (e.g. btih + btmh). We accept on btih.
        val hash = "0123456789abcdef0123456789abcdef01234567"
        val magnet = "magnet:?xt=urn:ed2k:somethingelse&xt=urn:btih:$hash&dn=X"
        val result = validator.validate(magnet)
        assertTrue("magnet with a btih among multiple xt must be accepted", result.valid)
        assertEquals(hash, result.infoHashHex)
    }
}

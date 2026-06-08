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

    // ---- additional edge cases ----

    @Test
    fun mixedCaseHexMagnet_normalizesToLowercase() {
        val mixed = "0123456789AbCdEf0123456789aBcDeF01234567" // 40 hex, mixed case
        val result = validator.validate("magnet:?xt=urn:btih:$mixed")
        assertTrue("mixed-case hex must be accepted, got: ${result.reason}", result.valid)
        assertEquals(mixed.lowercase(), result.infoHashHex)
    }

    @Test
    fun hexOnlyMagnet_withoutDnOrTr_isValid() {
        // dn and tr are optional; a bare xt=urn:btih is a complete, valid magnet.
        val hash = "89abcdef0123456789abcdef0123456789abcdef"
        val result = validator.validate("magnet:?xt=urn:btih:$hash")
        assertTrue("magnet with only xt must be valid, got: ${result.reason}", result.valid)
        assertEquals(hash, result.infoHashHex)
    }

    @Test
    fun lowercaseBase32Magnet_decodesToSameHexAsUppercase() {
        // The validator uppercases base32 before decoding, so a lowercase base32
        // xt must yield the identical info-hash as its uppercase form.
        val upper = "MFRGGZDFMZTWQ2LKNNWG23TPOBYXE43U"
        val fromUpper = validator.validate("magnet:?xt=urn:btih:$upper")
        val fromLower = validator.validate("magnet:?xt=urn:btih:${upper.lowercase()}")

        assertTrue("uppercase base32 must be valid", fromUpper.valid)
        assertTrue("lowercase base32 must be valid", fromLower.valid)
        assertEquals(fromUpper.infoHashHex, fromLower.infoHashHex)
        assertEquals(40, fromLower.infoHashHex!!.length)
    }

    @Test
    fun base32WithCharOutsideAlphabet_isRejected() {
        // 32 chars but contains '1' and '8' which are NOT in the RFC 4648 base32
        // alphabet (A-Z, 2-7) → decode fails → rejected.
        val bad = "MFRGGZDFMZTWQ2LKNNWG23TPOBYXE1U8" // 32 chars, '1' and '8' illegal
        assertEquals(32, bad.length)
        val result = validator.validate("magnet:?xt=urn:btih:$bad")
        assertFalse("base32 with out-of-alphabet chars must be rejected", result.valid)
        assertNotNull(result.reason)
    }

    @Test
    fun xtPresentButNotBtih_isRejected() {
        // An xt that is not a urn:btih → no info-hash → rejected.
        val result = validator.validate("magnet:?xt=urn:ed2k:0123456789abcdef0123456789abcdef&dn=X")
        assertFalse("xt without urn:btih must be rejected", result.valid)
        assertNotNull(result.reason)
        assertNull(result.infoHashHex)
    }

    @Test
    fun emptyXtValue_isRejected() {
        val result = validator.validate("magnet:?xt=&dn=X")
        assertFalse("empty xt value must be rejected", result.valid)
        assertNotNull(result.reason)
    }

    @Test
    fun magnetSchemeWithNoParameters_isRejected() {
        val result = validator.validate("magnet:?")
        assertFalse("magnet with no parameters must be rejected", result.valid)
        assertNotNull(result.reason)
    }

    @Test
    fun whitespaceUri_isRejected() {
        val result = validator.validate("   ")
        assertFalse("whitespace-only input must be rejected", result.valid)
        assertNotNull(result.reason)
    }

    @Test
    fun urlEncodedDn_doesNotAffectHashExtraction() {
        // A heavily URL-encoded display name must not interfere with extracting
        // the btih hash from the xt parameter.
        val hash = "fedcba9876543210fedcba9876543210fedcba98"
        val magnet =
            "magnet:?dn=My%20Movie%20%282024%29%20%5B1080p%5D&xt=urn:btih:$hash" +
                "&tr=udp%3A%2F%2Ftracker.invalid%3A80%2Fannounce"
        val result = validator.validate(magnet)
        assertTrue("url-encoded dn must not break extraction, got: ${result.reason}", result.valid)
        assertEquals(hash, result.infoHashHex)
    }

    @Test
    fun xtKeyIsCaseSensitive_uppercaseKeyRejected() {
        // The parameter key must be exactly "xt"; "XT" is a different key and
        // carries no recognized info-hash.
        val hash = "0123456789abcdef0123456789abcdef01234567"
        val result = validator.validate("magnet:?XT=urn:btih:$hash")
        assertFalse("uppercase XT key must not be treated as xt", result.valid)
        assertNotNull(result.reason)
    }

    @Test
    fun multipleBtihXt_picksFirst() {
        val first = "1111111111111111111111111111111111111111"
        val second = "2222222222222222222222222222222222222222"
        val result = validator.validate("magnet:?xt=urn:btih:$first&xt=urn:btih:$second")
        assertTrue("multiple btih xt must be accepted", result.valid)
        assertEquals(first, result.infoHashHex)
    }
}

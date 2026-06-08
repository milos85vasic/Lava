package lava.tracker.rutor.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuTorSizeParserTest {

    @Test
    fun `parses 4_5 GB with dot decimal`() {
        // 4.5 * 2^30 = 4_831_838_208
        assertEquals(4_831_838_208L, RuTorSizeParser.parse("4.5 GB"))
    }

    @Test
    fun `parses 1_5 GB with comma decimal`() {
        // 1.5 * 2^30 = 1_610_612_736
        assertEquals(1_610_612_736L, RuTorSizeParser.parse("1,5 GB"))
    }

    @Test
    fun `parses 1024 MB equals 1 GiB`() {
        assertEquals(1_073_741_824L, RuTorSizeParser.parse("1024 MB"))
    }

    @Test
    fun `parses 200 kB`() {
        assertEquals(204_800L, RuTorSizeParser.parse("200 kB"))
    }

    @Test
    fun `parses 512 B`() {
        assertEquals(512L, RuTorSizeParser.parse("512 B"))
    }

    @Test
    fun `parses 2 TB`() {
        // 2 * 2^40 = 2_199_023_255_552
        assertEquals(2_199_023_255_552L, RuTorSizeParser.parse("2 TB"))
    }

    @Test
    fun `parser is case-insensitive on the unit`() {
        assertEquals(1_073_741_824L, RuTorSizeParser.parse("1 gb"))
        assertEquals(1_048_576L, RuTorSizeParser.parse("1 mb"))
    }

    @Test
    fun `garbage input returns null`() {
        assertNull(RuTorSizeParser.parse("not a size"))
        assertNull(RuTorSizeParser.parse(""))
        assertNull(RuTorSizeParser.parse("123 PB")) // PB not supported
    }

    @Test
    fun `accepts no whitespace between number and unit`() {
        assertEquals(1_073_741_824L, RuTorSizeParser.parse("1GB"))
    }

    @Test
    fun `extracts the size embedded in a larger cell string`() {
        // Real rutor size cells arrive with surrounding markup text and trailing peers prose; the
        // parser must find the first size token rather than requiring the whole string to be one.
        assertEquals(4_563_402_752L, RuTorSizeParser.parse("Размер: 4.25 GB (примерно)"))
        assertEquals(204_800L, RuTorSizeParser.parse("200 kB всего"))
    }

    @Test
    fun `parses 0 GB as zero bytes`() {
        // A torrent the tracker renders as "0 B" must map to 0, not null — null would let a
        // size filter silently skip it.
        assertEquals(0L, RuTorSizeParser.parse("0 B"))
        assertEquals(0L, RuTorSizeParser.parse("0 GB"))
    }

    @Test
    fun `picks the first size token when several appear`() {
        // The regex matches the first occurrence; verify the contract explicitly so a future
        // refactor that switches to findAll does not silently change which token wins.
        assertEquals(1_073_741_824L, RuTorSizeParser.parse("1 GB / 2 GB"))
    }

    @Test
    fun `large integer TB has no double-rounding loss`() {
        // 3 * 2^40 is exactly representable; assert the exact boundary so a Double-precision
        // regression at the TB scale is caught.
        assertEquals(3_298_534_883_328L, RuTorSizeParser.parse("3 TB"))
    }
}

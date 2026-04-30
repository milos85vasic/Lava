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
}

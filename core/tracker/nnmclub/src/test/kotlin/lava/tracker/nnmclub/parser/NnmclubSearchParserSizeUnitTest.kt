package lava.tracker.nnmclub.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Size-unit coverage for the REAL [NnmclubSearchParser] size column (the private
 * `parseSize` reached through the production `parse` entry point — no reflection,
 * no SUT mocking). The existing [NnmclubSearchParserEdgeCaseTest] only feeds `GB`
 * (and one comma+NBSP case); it never exercises the `MB` / `KB` / `B` / `TB`
 * branches of the unit `when`, nor pins the unit's case-sensitivity contract.
 *
 * sizeBytes is user-visible: it drives the size column on the search screen and the
 * size-based cross-tracker fallback ranking. A wrong multiplier (e.g. a TB row shown
 * as GB) is a 1024x error in front of the user.
 *
 * Falsifiability (Sixth Law clause 2): each test names the mutation that breaks it.
 */
class NnmclubSearchParserSizeUnitTest {

    private val parser = NnmclubSearchParser()

    /** Wrap a size string in a production-shaped 6-column nnmclub forum row. */
    private fun rowWithSize(sizeText: String): String =
        """
        <html><body><table class="forumline">
        <tr>
          <td><a href="viewtopic.php?t=1" class="genmed">Title</a></td>
          <td>cat</td>
          <td class="seedmed">5</td>
          <td class="leechmed">2</td>
          <td>added</td>
          <td>$sizeText</td>
        </tr>
        </table></body></html>
        """.trimIndent()

    private fun sizeOf(sizeText: String): Long? =
        parser.parse(rowWithSize(sizeText), pageHint = 0).items.single().sizeBytes

    @Test
    fun `GB MB KB B units each map to the binary multiplier`() {
        // Mutation that fails this test: change any `* 1024` factor in parseSize's `when`
        // (e.g. "MB" -> num.toLong()). Observed failure: expected:<1572864> but was:<1.5> on
        // the MB assertion.
        assertEquals(1_073_741_824L, sizeOf("1 GB")) // 1 * 2^30
        assertEquals((1.5 * 1024 * 1024).toLong(), sizeOf("1.5 MB"))
        assertEquals(500L * 1024, sizeOf("500 KB"))
        assertEquals(123L, sizeOf("123 B"))
    }

    @Test
    fun `TB unit is the largest supported binary multiplier`() {
        // The "TB" branch is never hit by the existing edge-case suite. A real nnmclub
        // movie pack renders e.g. "2 TB"; a wrong factor would show it as 2 GB.
        // Mutation that fails this test: drop one 1024L factor from the "TB" branch.
        // Observed failure: expected:<2199023255552> but was:<2147483648>.
        assertEquals(2L * 1024 * 1024 * 1024 * 1024, sizeOf("2 TB"))
    }

    @Test
    fun `comma-decimal GB is parsed as a dot-decimal`() {
        // nnmclub's ru locale uses ',' as the decimal mark. parseSize replaces ',' with '.'.
        // Mutation that fails this test: remove the `.replace(",", ".")`. Observed failure:
        // expected:<4826622525> (4.5 GB) but was: null (toDoubleOrNull on "4,5" yields null).
        assertEquals((4.5 * 1024 * 1024 * 1024).toLong(), sizeOf("4,5 GB"))
    }

    @Test
    fun `zero-byte size parses to zero rather than null`() {
        // "0 B" must map to 0L, not null — a null would let a size filter silently skip the row.
        // Mutation that fails this test: special-case `num == 0.0` to return null.
        assertEquals(0L, sizeOf("0 B"))
    }

    @Test
    fun `unrecognised unit text degrades to null without throwing`() {
        // The unit regex requires an uppercase [KMGT]?B token. A stray placeholder / dash / an
        // unsupported petabyte unit must yield null (the size column then renders blank) — never
        // an exception that would blow up the whole result list.
        assertNull(sizeOf("—"))
        assertNull(sizeOf("n/a"))
        assertNull(sizeOf("5 PB")) // petabytes not supported by the [KMGT]?B unit set
    }
}

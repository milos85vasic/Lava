package lava.tracker.rutor.parser

/**
 * Parses RuTor's torrent size strings ("4.5 GB", "1,5 GB", "1024 MB",
 * "200 kB", ...) into a byte count. Returns null when [s] is unparsable.
 *
 * Notes:
 *   - Comma-decimal is supported (RuTor's locale uses ',' as the decimal
 *     separator more often than '.').
 *   - Multipliers are powers of 1024 (binary), matching what the tracker
 *     itself displays — "1 GB" on RuTor is 2^30 bytes, not 10^9.
 *   - Recognised units: B, kB, MB, GB, TB (case-insensitive).
 */
object RuTorSizeParser {
    private val pattern = Regex(
        """(\d+(?:[.,]\d+)?)\s*(GB|MB|kB|B|TB)""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(s: String): Long? {
        val m = pattern.find(s) ?: return null
        val (number, unit) = m.destructured
        val value = number.replace(',', '.').toDoubleOrNull() ?: return null
        val multiplier = when (unit.uppercase()) {
            "B" -> 1L
            "KB" -> 1_024L
            "MB" -> 1_024L * 1_024L
            "GB" -> 1_024L * 1_024L * 1_024L
            "TB" -> 1_024L * 1_024L * 1_024L * 1_024L
            else -> return null
        }
        return (value * multiplier).toLong()
    }
}

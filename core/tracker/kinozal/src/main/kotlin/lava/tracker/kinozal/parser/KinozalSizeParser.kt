package lava.tracker.kinozal.parser

/**
 * Parses Kinozal torrent size strings ("1.5 GB", "1,5 GB", "1024 MB",
 * "200 KB", ...) into a byte count. Returns null when [s] is unparsable.
 *
 * LVA-027: KinozalSearchParser parsed the size string into a local var but then
 * emitted `sizeBytes = null`, so every Kinozal search row dropped its size
 * (size sort/filter + cross-tracker ranking went blind to Kinozal). This mirrors
 * RuTorSizeParser: binary (1024) multipliers matching what the tracker displays,
 * comma-or-dot decimals, case-insensitive Latin units. Cyrillic units (КБ/МБ/ГБ)
 * are also accepted defensively (kinozal.tv is a Russian site; the checked-in
 * fixtures use Latin units, so the Cyrillic branch is belt-and-suspenders —
 * tracked separately if a Cyrillic-unit fixture is captured).
 */
object KinozalSizeParser {
    private val pattern = Regex(
        """(\d+(?:[.,]\d+)?)\s*(ГБ|МБ|КБ|ТБ|Б|GB|MB|KB|TB|B)""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(s: String): Long? {
        val m = pattern.find(s) ?: return null
        val (number, unit) = m.destructured
        val value = number.replace(',', '.').toDoubleOrNull() ?: return null
        val multiplier = when (unit.uppercase()) {
            "B", "Б" -> 1L
            "KB", "КБ" -> 1_024L
            "MB", "МБ" -> 1_024L * 1_024L
            "GB", "ГБ" -> 1_024L * 1_024L * 1_024L
            "TB", "ТБ" -> 1_024L * 1_024L * 1_024L * 1_024L
            else -> return null
        }
        return (value * multiplier).toLong()
    }
}

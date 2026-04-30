package lava.tracker.rutracker.mapper

/**
 * LF-6 RESOLVED (2026-04-30) — parser for the formatted size strings the
 * legacy rutracker scraper produces. Inverse of
 * `lava.tracker.rutracker.domain.formatSize` (which renders byte counts as
 * `"%.1f KB" / "%.1f MB" / ...` at the 1024-based boundary it picks via
 * `ln(sizeBytes)/ln(1024)`).
 *
 * Inputs encountered in production:
 *   - `"4.7 GB"` / `"4,7 GB"`  (rutracker prefers a period; the comma-decimal
 *      form appears occasionally on third-party mirrors)
 *   - `"1.2 MB"` / `"500 KB"` / `"123 B"` / `"5.4 TB"` / `"1024 MB"`
 *   - `"1 GB"` (no decimal)
 *
 * Multiplier convention: **binary** (1 KB = 1024, 1 MB = 1024^2, ...). This
 * matches the rendering function above and matches rutracker.org's own
 * presentation; using decimal SI multipliers would round-trip incorrectly
 * (a torrent the site shows as "1.0 GB" is 2^30 bytes, not 10^9).
 *
 * Returns null on unparseable input — the caller MUST tolerate null
 * (legacy DTO already shipped null `sizeBytes` for all rutracker rows
 * before this parser landed; the `metadata["rutracker.size_text"]` key
 * remains populated as the user-visible fallback).
 *
 * Forensic anchor: the LF-6 fix in `:core:tracker:rutracker:mapper`
 * intentionally mirrors `:core:tracker:rutor:parser:RuTorSizeParser` rather
 * than sharing a single class — the two trackers' formatted strings are
 * NOT bit-identical (rutor uses ` KB` / ` MB`, rutracker uses ` KB` /
 * ` MB` but with non-breaking-space-stripping happening upstream in
 * `GetCategoryPageUseCase` etc.). Sharing the parser would couple the
 * two scrapers in a way that drift would silently break; per the
 * Decoupled Reusable Architecture rule each tracker module owns its own
 * parser, identical-by-coincidence is acceptable.
 */
internal object RuTrackerSizeParser {

    private val pattern = Regex(
        """(\d+(?:[.,]\d+)?)\s*(GB|MB|KB|B|TB)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Parses [s] (e.g. `"4.7 GB"`) into a binary byte count. Returns null
     * if the string cannot be parsed. A null or blank input also returns
     * null without throwing — the forward mappers pass values straight
     * through `metadata["rutracker.size_text"]` and we want the parser
     * to fail soft.
     */
    fun parse(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        // Normalise non-breaking spaces (some mirrors leave   between
        // the number and the unit; `GetCategoryPageUseCase` already strips
        // these but the parser is also called on `metadata["rutracker.size_text"]`
        // which preserves whatever the scraper stored).
        val normalised = s.replace(' ', ' ')
        val m = pattern.find(normalised) ?: return null
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

package lava.onboarding.util

import lava.tracker.api.AuthType

/**
 * Render an [AuthType] enum value as a human-readable subtitle.
 *
 * Operator directive 2026-05-18 (§6.L 60th invocation): provider
 * subtitles MUST NOT show raw enum names with underscores. `FORM_LOGIN`
 * → "Form Login", `API_KEY` → "Api Key", `NONE` → "None".
 *
 * Falsifiability anchor (§6.J): break this by returning [AuthType.name]
 * directly and the unit + Challenge tests both fail with assertion
 * "subtitle contains underscore".
 */
internal fun AuthType.displayLabel(): String =
    name
        .replace('_', ' ')
        .lowercase()
        .split(' ')
        .joinToString(" ") { word ->
            word.replaceFirstChar { ch -> ch.titlecase() }
        }

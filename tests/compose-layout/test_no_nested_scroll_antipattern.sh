#!/usr/bin/env bash
# tests/compose-layout/test_no_nested_scroll_antipattern.sh
#
# §6.Q Compose Layout Antipattern Guard — project-wide heuristic.
#
# Scans every Kotlin file under feature/ and core/ that defines
# Composable functions, and rejects any file that contains BOTH:
#   - `Modifier.verticalScroll(`        (in non-comment code)
#   - `LazyColumn(`                     (in non-comment code)
#
# Same heuristic horizontally:
#   - `Modifier.horizontalScroll(`
#   - `LazyRow(`
#
# When the same file has both, the LazyColumn is structurally likely
# to be a child of the verticalScroll Column, which is the canonical
# IllegalStateException-at-measure-time crash. The fix is one of:
#   1. Replace LazyColumn with a plain Column (when the list is bounded).
#   2. Bound the LazyColumn's height with `Modifier.heightIn(max = X.dp)`
#      and add a comment + per-feature Compose UI test.
#   3. Move the LazyColumn outside the verticalScroll parent.
#
# Allowlist: a file MAY have both symbols if it carries a marker
# `// §6.Q-allow:` comment line citing the resolution (option 2 above).
# Test files (those under src/test/ or src/androidTest/) are also
# exempt — they may exercise BOTH patterns deliberately.
#
# Forensic anchor: 2026-05-05 23:51 operator-reported crash, closure
# log at .lava-ci-evidence/crashlytics-resolved/2026-05-05-tracker-settings-nested-scroll.md.
#
# Falsifiability rehearsal: re-add `LazyColumn(...)` somewhere inside
# `feature/tracker_settings/.../TrackerSettingsScreen.kt` (which already
# contains `verticalScroll`) — this test fails pointing at the file.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT"

violations=()

# Build the candidate file list: production Kotlin files under feature/ + core/.
# Strip src/test/ and src/androidTest/ paths.
candidates=$(find feature core app/src/main -name '*.kt' 2>/dev/null \
    | grep -v '/src/test/' \
    | grep -v '/src/androidTest/' \
    | grep -v '/build/' || true)

for f in $candidates; do
    # Strip lines that are obviously comments (//, /*, *, /**) before
    # scanning. `grep -v` with a regex that matches comment-prefixed lines.
    code=$(grep -vE '^\s*(//|/\*|\*|/\*\*)' "$f" 2>/dev/null || true)

    has_vscroll=false
    has_lazy_col=false
    has_hscroll=false
    has_lazy_row=false

    # Match both `LazyColumn(...)` (parens) and `LazyColumn { }`
    # (trailing-lambda) styles — Kotlin allows omitting parens when a
    # composable's only argument is a trailing lambda.
    grep -qE '\.verticalScroll\(' <<< "$code" && has_vscroll=true
    grep -qE 'LazyColumn[ ({]' <<< "$code" && has_lazy_col=true
    grep -qE '\.horizontalScroll\(' <<< "$code" && has_hscroll=true
    grep -qE 'LazyRow[ ({]' <<< "$code" && has_lazy_row=true

    # Allowlist marker — operator-acknowledged scoped exception.
    if grep -qE '§6\.Q-allow:' "$f"; then
        continue
    fi

    if $has_vscroll && $has_lazy_col; then
        violations+=("$f: contains BOTH verticalScroll AND LazyColumn")
    fi
    if $has_hscroll && $has_lazy_row; then
        violations+=("$f: contains BOTH horizontalScroll AND LazyRow")
    fi
done

if (( ${#violations[@]} > 0 )); then
    echo "FAIL §6.Q: Compose Layout Antipattern Guard — files containing nested scroll + lazy layout:"
    printf '  %s\n' "${violations[@]}"
    echo ""
    echo "Fix one of:"
    echo "  1. Replace LazyColumn with plain Column (best for bounded lists ≤ ~10 entries)"
    echo "  2. Add Modifier.heightIn(max = X.dp) to the LazyColumn AND a"
    echo "     '// §6.Q-allow: <reason>' comment in the same file linking"
    echo "     to a per-feature Compose UI Challenge Test that proves the"
    echo "     bounded layout renders correctly on the §6.I matrix."
    echo "  3. Move the LazyColumn outside the verticalScroll parent."
    echo ""
    echo "See .lava-ci-evidence/crashlytics-resolved/2026-05-05-tracker-settings-nested-scroll.md"
    exit 1
fi

# ──────────────────────────────────────────────────────────────────────────────
# CHECK 2: LazyList with no modifier (unbounded height in Column).
#
# Forensic anchor: §6.Q second crash site 2026-06-24 —
# SearchInputScreen.kt used LazyList(contentPadding = ...) with NO modifier
# inside Column { ProviderChipBar(); LazyList(...) } inside a Scaffold content
# lambda. The Column propagated unbounded height to the LazyList, triggering
# "Vertically scrollable component was measured with an infinite maximum height
# constraint" (Crashlytics c7c8cccad09f..., 2 events, versions 1.2.3→1.3.10).
#
# Rule: every LazyList( call MUST pass `modifier =` as its first named
# argument so that the caller explicitly controls size constraints.
# A LazyList whose first argument is not `modifier =` has no explicit
# size-bounding intent and is likely to receive unbounded height from a
# plain Column parent.
#
# Allowlist: a file MAY have a no-modifier LazyList if it carries a marker
# `// §6.Q-allow-no-modifier:` comment line citing the resolution (e.g.
# the LazyList IS the outermost composable / entire screen root and is
# bounded by the NavHost / Activity window).
#
# Falsifiability rehearsal: remove `modifier = Modifier.weight(1f)` from
# SearchInputScreen.kt's LazyList call — this test fails pointing at that file.
# ──────────────────────────────────────────────────────────────────────────────

unbounded_violations=()

for f in $candidates; do
    # Skip files with the explicit allowlist marker.
    if grep -qE '§6\.Q-allow-no-modifier:' "$f"; then
        continue
    fi

    # Find lines with LazyList( that are NOT followed (within 2 lines) by
    # `modifier =` as the first named argument.
    # Strategy: for each LazyList( occurrence, check if the immediately
    # following non-empty line starts with `modifier =`.
    while IFS= read -r lineno_and_content; do
        lineno="${lineno_and_content%%:*}"
        same_line=$(sed -n "${lineno}p" "$f")

        # Skip function DEFINITIONS — `fun LazyList(` or `private fun LazyList(`
        # These declare `modifier: Modifier = Modifier` as a parameter, which
        # is correct; they are not call sites.
        if echo "$same_line" | grep -qE '\bfun\s+LazyList\s*\('; then
            continue
        fi

        # Skip if modifier is already on the same line as the call.
        if echo "$same_line" | grep -qE 'modifier\s*='; then
            continue  # modifier on same line — fine
        fi

        # Get the immediately following non-empty argument line.
        next_line=$(sed -n "$((lineno + 1))p" "$f" | sed 's/^[[:space:]]*//')
        if echo "$next_line" | grep -qE '^modifier\s*='; then
            continue  # modifier is first argument on next line — fine
        fi

        unbounded_violations+=("$f:$lineno: LazyList( has no 'modifier =' first argument — explicit size bound required to prevent unbounded height in Column parent")
    done < <(grep -n 'LazyList(' "$f" 2>/dev/null | grep -vE '^\s*(//|/\*|\*|/\*\*)' || true)
done

if (( ${#unbounded_violations[@]} > 0 )); then
    echo "FAIL §6.Q-2: Compose Layout Antipattern Guard — LazyList calls without explicit modifier:"
    printf '  %s\n' "${unbounded_violations[@]}"
    echo ""
    echo "Fix: add 'modifier = Modifier.weight(1f)' (inside a Column with weights) or"
    echo "     'modifier = Modifier.fillMaxSize()' (as a top-level screen composable)"
    echo "     as the first argument to each flagged LazyList( call."
    echo "     If the LazyList IS the screen root (bounded by NavHost), add:"
    echo "     // §6.Q-allow-no-modifier: screen root, bounded by NavHost"
    echo ""
    echo "See .lava-ci-evidence/crashlytics-resolved/ for forensic anchor."
    exit 1
fi

echo "[compose-layout] OK: no nested-scroll antipattern detected in feature/ + core/ + app/src/main/"
exit 0

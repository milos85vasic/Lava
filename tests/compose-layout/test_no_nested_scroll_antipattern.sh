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

echo "[compose-layout] OK: no nested-scroll antipattern detected in feature/ + core/ + app/src/main/"
exit 0

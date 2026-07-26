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

# ──────────────────────────────────────────────────────────────────────────────
# CHECK 3: Dialog content Column hosting a lazy layout with NO height bound.
#
# Forensic anchor: §6.Q third crash site — Crashlytics issue
# c7c8cccad09f72bd7bb95455226109b8 (FATAL, firstSeen 1.2.3 → lastSeen 1.3.12),
# fixed 2026-07-04 (fix commit f1a2c362). CategorySelectionDialog.kt wrapped
# PagesScreen in a plain `Column(modifier = Modifier.clip(...))` with NO height
# bound and NO weight on the PagesScreen child. A plain Column measures its
# non-weighted child with maxHeight = Infinity; PagesScreen's inner
# Scaffold/HorizontalPager propagated that infinity to the
# CategorySelectionList / CategorySelectionScreen LazyLists (which use
# fillMaxHeight()/fillMaxSize()), throwing
# "Vertically scrollable component was measured with an infinity maximum
# height constraints". Fix: Modifier.fillMaxHeight(0.9f) on the Column +
# Modifier.weight(1f) on PagesScreen.
#
# Rule: inside a `Dialog(` block (brace-balanced), any lazy layout call
# (LazyList( / LazyColumn( / PagesScreen() requires at least one height-bound
# token in the SAME Dialog block: fillMaxHeight / fillMaxSize / heightIn /
# .height( / .weight(. A Dialog content Column hosting a lazy layout with none
# of these propagates infinite height into the lazy child.
#
# Allowlist: a file MAY carry a marker `// §6.Q-allow-dialog-unbounded:`
# comment line citing the resolution (e.g. the lazy layout inside the dialog
# is already bounded via heightIn(max = …) documented in-file).
#
# Falsifiability rehearsal: revert CategorySelectionDialog.kt to the pre-fix
# shape (`Column(modifier = Modifier.clip(AppTheme.shapes.large))` +
# PagesScreen with no `modifier =`) — this test fails pointing at that file.
# ──────────────────────────────────────────────────────────────────────────────

dialog_violations=()

for f in $candidates; do
    # Skip files with the explicit allowlist marker.
    if grep -qE '§6\.Q-allow-dialog-unbounded:' "$f"; then
        continue
    fi

    # Extract every `Dialog(` block with brace balancing and emit
    # "STARTLINE<TAB>block-text" records, one per block. A block starts at a
    # line calling Dialog( (word-boundary — excludes FooDialog( call sites and
    # fun Dialog( definitions in other files are still fine to examine) and
    # ends when the braces opened since the start line balance back to zero.
    # Extract every `Dialog(` block with brace balancing and check each block
    # in the same awk pass (blocks are multi-line, so the analysis cannot be
    # split across a line-oriented `read` loop):
    #   - A block starts at a line calling Dialog( (word-boundary — excludes
    #     FooDialog( call sites) and ends when the braces opened since the
    #     start line balance back to zero.
    #   - Comment lines are stripped before the analysis (same convention as
    #     CHECK 1) so an explanatory comment citing e.g. `fillMaxHeight` does
    #     not masquerade as a real height bound.
    #   - Only blocks hosting a lazy layout (LazyList/LazyColumn/PagesScreen)
    #     are examined — Dialogs containing only TextFields / Buttons (e.g.
    #     the credentials dialogs) cannot hit the infinite-height lazy
    #     measure path.
    #   - A hosting block MUST contain at least one height-bound token:
    #     fillMaxHeight / fillMaxSize / heightIn / .height( / .weight(.
    while IFS= read -r violation; do
        [[ -z "$violation" ]] && continue
        dialog_violations+=("$violation")
    done < <(awk '
        function check_block() {
            if (code ~ /LazyList[ ({]|LazyColumn[ ({]|PagesScreen[ ({]/ &&
                code !~ /fillMaxHeight|fillMaxSize|heightIn|\.height\(|\.weight\(/) {
                printf "%s:%d: Dialog content hosts a lazy layout (LazyList/LazyColumn/PagesScreen) with NO height bound (fillMaxHeight/heightIn/weight) in the Dialog block — infinite-height lazy measure risk\n", FILENAME, startline
            }
        }
        /(^|[^A-Za-z0-9_])Dialog\(/ {
            if (!active) { active=1; depth=0; started=0; startline=NR; code="" }
        }
        active {
            # Comment-prefixed lines (//, /*, *, /**) count toward brace
            # balancing but are excluded from the pattern analysis.
            if ($0 !~ /^[[:space:]]*(\/\/|\/\*|\*)/) {
                code = code $0 "\n"
            }
            line = $0
            opens = gsub(/\{/, "{", line)
            closes = gsub(/\}/, "}", line)
            depth += opens - closes
            if (opens > 0) started = 1
            if (started && depth <= 0) {
                check_block()
                active = 0
            }
        }
        END { if (active) check_block() }
    ' "$f" 2>/dev/null || true)
done

if (( ${#dialog_violations[@]} > 0 )); then
    echo "FAIL §6.Q-3: Compose Layout Antipattern Guard — Dialog content hosting an unbounded lazy layout:"
    printf '  %s\n' "${dialog_violations[@]}"
    echo ""
    echo "Fix: bound the Dialog content height — e.g. Modifier.fillMaxHeight(0.9f) on the"
    echo "     content Column AND Modifier.weight(1f) on the lazy child (or"
    echo "     Modifier.heightIn(max = X.dp) on the lazy layout itself) — so the lazy"
    echo "     layout is measured with finite constraints. If the layout is already"
    echo "     bounded by a documented mechanism, add:"
    echo "     // §6.Q-allow-dialog-unbounded: <reason>"
    echo ""
    echo "Forensic anchor: Crashlytics c7c8cccad09f72bd7bb95455226109b8 — see"
    echo ".lava-ci-evidence/crashlytics-resolved/2026-07-26-nested-scroll-category-selection-dialog.md"
    exit 1
fi

echo "[compose-layout] OK: no nested-scroll antipattern detected in feature/ + core/ + app/src/main/"
exit 0

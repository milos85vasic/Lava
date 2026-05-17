#!/usr/bin/env bash
# scripts/check-commit-docs-exists.sh — CM-COMMIT-DOCS-EXISTS gate
# per HelixConstitution §11.4.x + Lava §6.AD-debt closure.
#
# Scans the body of one or more git commits and verifies every file
# path referenced in the message resolves to a real file at HEAD.
#
# Why this gate exists: a commit message that cites "evidence at
# .lava-ci-evidence/X.json" when X.json was never created (or was
# deleted later) is a §6.J spirit violation — the claim is
# unfalsifiable because the referenced evidence does not exist.
#
# Usage:
#   bash scripts/check-commit-docs-exists.sh                  # check HEAD
#   bash scripts/check-commit-docs-exists.sh HEAD~5..HEAD      # range
#   bash scripts/check-commit-docs-exists.sh @{u}..HEAD        # unpushed
#   LAVA_COMMIT_RANGE='HEAD~3..HEAD' bash scripts/check-commit-docs-exists.sh
#   LAVA_REPO_ROOT=/path bash scripts/check-commit-docs-exists.sh
#
# Heuristics (intentionally conservative to avoid false positives):
#   - Path candidate: contains at least one '/' AND ends with a known
#     extension (md, json, sh, kt, go, kts, gradle, xml, yaml, yml,
#     toml, conf, txt, tsv).
#   - Strip backtick-quoted spans before regex match (`code` is not
#     a citation, it's prose markup).
#   - Strip ~~strikethrough~~ spans (closure-mark convention; paths
#     inside are intentional historical references).
#   - Skip paths inside parentheses if also inside a backtick span.
#   - Skip lines that begin with '> ' (markdown blockquote — typically
#     verbatim operator-quote in §6.L cycle bodies).
#
# Exit codes:
#   0 — every referenced path resolves
#   1 — at least one orphan reference (printed to stderr with commit SHA)
#
# Classification: project-specific (the convention is universal per
# HelixConstitution; the path layout is Lava-specific).

set -uo pipefail

REPO_ROOT="${LAVA_REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
cd "$REPO_ROOT"

range="${1:-${LAVA_COMMIT_RANGE:-HEAD~0..HEAD}}"

# Normalize: HEAD~0..HEAD → just HEAD (single commit)
if [[ "$range" == "HEAD~0..HEAD" ]] || [[ "$range" == "HEAD" ]]; then
    commits=("$(git rev-parse HEAD 2>/dev/null)")
else
    mapfile -t commits < <(git rev-list "$range" 2>/dev/null)
fi

if [[ ${#commits[@]} -eq 0 ]] || [[ -z "${commits[0]}" ]]; then
    echo "CM-COMMIT-DOCS-EXISTS: no commits in range '$range' — skipping"
    exit 0
fi

# Extension whitelist — paths must end with one of these to be considered
# a "doc reference". This filters out version IDs (1.2.30-1050) and
# package names that happen to contain dots.
declare -a EXTS=(md json sh kt go kts gradle xml yaml yml toml conf txt tsv)
ext_pattern="$(IFS='|'; echo "${EXTS[*]}")"

# Path-prefix whitelist — paths must START with one of these to be
# considered intentional (filters out arbitrary slash-containing strings
# like URLs and version numbers).
declare -a PREFIXES=(
    docs/ scripts/ tests/ .lava-ci-evidence/ submodules/ constitution/
    app/ core/ feature/ proxy/ lava-api-go/ tools/ buildSrc/
    config/ keystores/ releases/ Upstreams/ \\.githooks/
)
prefix_pattern="$(IFS='|'; echo "${PREFIXES[*]}")"

orphan_count=0
declare -a orphan_reports=()

for sha in "${commits[@]}"; do
    [[ -z "$sha" ]] && continue
    body=$(git log -1 --format=%B "$sha" 2>/dev/null)
    [[ -z "$body" ]] && continue

    # Strip per-line:
    #   1. blockquote lines (lead '> ')
    #   2. 2+ space indented lines (Bluff-Audit body — Mutation/
    #      Observed-Failure/Reverted entries, quoted output,
    #      code-block-like prose. Column-0 bullets `- path` remain;
    #      nested bullets `  - path` are skipped — uncommon in commits).
    #   3. backtick-spans
    #   4. ~~strikethrough~~ spans
    stripped=$(printf '%s\n' "$body" | awk '
        /^> / { next }
        /^  / { next }
        {
            line = $0
            # Strip backtick spans (single + double)
            gsub(/`[^`]*`/, "", line)
            # Strip strikethrough ~~...~~
            gsub(/~~[^~]*~~/, "", line)
            print line
        }
    ')

    # Extract candidate paths
    mapfile -t candidates < <(
        printf '%s\n' "$stripped" \
        | grep -oE "($prefix_pattern)[A-Za-z0-9_./*-]+\\.($ext_pattern)" \
        | sort -u
    )

    for path in "${candidates[@]}"; do
        # Strip AT MOST ONE trailing punctuation char (comma, paren, colon, etc.)
        # Use single % (shortest-match-from-end) — never strip on '.' since
        # multi-extension paths like 'foo.sh.md' would lose '.md'.
        clean="${path%[,\):;\]\}]}"
        # Skip empties + wildcards / glob patterns
        [[ -z "$clean" ]] && continue
        [[ "$clean" == *'*'* ]] && continue
        # Existence check — exact path first
        if [[ -e "$clean" ]]; then
            continue
        fi
        # Fuzzy fallback: short-form prose references like
        # `feature/search_result/SearchPageState.kt` resolve to the actual
        # `feature/search_result/src/main/kotlin/.../SearchPageState.kt`.
        # Strategy: extract the leading prefix dir + the basename and
        # search for the basename under the prefix tree.
        prefix_dir="${clean%%/*}"
        basename_part="${clean##*/}"
        if [[ -d "$prefix_dir" ]]; then
            matches=$(find "$prefix_dir" -name "$basename_part" -not -path '*/build/*' -not -path '*/.git/*' -not -path '*/.claude/*' 2>/dev/null | head -2)
            match_count=$(echo "$matches" | grep -c .)
            if [[ "$match_count" -ge 1 ]]; then
                continue
            fi
        fi
        orphan_reports+=("commit ${sha:0:8}: $clean")
        ((orphan_count++))
    done
done

if [[ $orphan_count -gt 0 ]]; then
    echo "CM-COMMIT-DOCS-EXISTS VIOLATION:" >&2
    echo "  Commit messages reference paths that do not exist at HEAD:" >&2
    printf '    %s\n' "${orphan_reports[@]}" >&2
    echo "  → Either restore the missing file OR amend the commit message" >&2
    echo "    to remove / strikethrough the stale reference." >&2
    exit 1
fi

echo "CM-COMMIT-DOCS-EXISTS gate clean: ${#commits[@]} commit(s) scanned, all referenced paths resolve."

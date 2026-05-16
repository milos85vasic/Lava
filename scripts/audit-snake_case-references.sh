#!/usr/bin/env bash
# scripts/audit-snake_case-references.sh — §11.4.29 rename-blast-radius audit.
#
# Purpose:
#   Read-only audit that counts references in tracked files to the
#   pre-rename names targeted by HelixConstitution §11.4.29 (Lowercase-
#   Snake_Case-Naming Mandate, 2026-05-15) Phase 6 of Lava's
#   constitution-compliance migration plan.
#
#   The script is the baseline-capture tool for the Phase 6.0
#   research+plan cycle: it lets the operator (and reviewers) gauge the
#   rename blast radius BEFORE approving any actual rename. It is also
#   the per-phase regression-detector for Phases 6a + 6b: rerun after a
#   rename to confirm zero stale references survive (§11.4.29's
#   reference-drift clause is severity-equivalent to PASS-bluffs).
#
# Usage:
#   bash scripts/audit-snake_case-references.sh                # plain TSV (default)
#   bash scripts/audit-snake_case-references.sh --format=md    # Markdown table
#   bash scripts/audit-snake_case-references.sh --names-only   # list names + counts only
#   bash scripts/audit-snake_case-references.sh --top-files=N  # also list top-N files per name
#
# Output:
#   Tab-separated table written to stdout:
#     NAME<TAB>REFS<TAB>FILES
#     Auth<TAB>16<TAB>9
#     ...
#     TOTAL_Submodules<TAB>806<TAB>124
#
#   Exit status:
#     0 — audit ran cleanly (regardless of reference counts; this is
#         read-only and never fails on "too many references")
#     2 — argument / environment error
#
# Inputs:
#   - tracked-file set (git ls-files)
#   - hard-coded NAMES array (the 17 owned-by-us submodules + the
#     bare-prefix "Submodules" itself)
#
# Outputs:
#   - stdout: TSV (or markdown when --format=md)
#   - no files written, no git state mutated
#
# Side-effects:
#   None. Read-only.
#
# Dependencies:
#   - git (for `git grep`, `git ls-files`)
#   - awk, sort, uniq (POSIX)
#
# Cross-references:
#   - HelixConstitution §11.4.29 (the rename mandate this script supports)
#   - HelixConstitution §11.4.18 (script docs; companion at docs/scripts/...)
#   - docs/plans/2026-05-16-snake_case-migration.md (the plan this script
#     enables)
#   - docs/plans/2026-05-15-constitution-compliance.md Phase 6
#   - tests/check-constitution/test_audit_snake_case_references.sh
#
# Classification: project-specific (the NAMES list IS Lava's 17 owned-by-us
# submodules; the audit-for-blast-radius-before-rename discipline is
# universal per HelixConstitution §11.4.29).

set -uo pipefail

REPO_ROOT="${LAVA_REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
cd "$REPO_ROOT"

FORMAT="tsv"
NAMES_ONLY=0
TOP_FILES=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --format=tsv)   FORMAT="tsv"; shift ;;
        --format=md)    FORMAT="md"; shift ;;
        --names-only)   NAMES_ONLY=1; shift ;;
        --top-files=*)  TOP_FILES="${1#*=}"; shift ;;
        -h|--help)      sed -n '3,50p' "$0"; exit 0 ;;
        *)              echo "ERROR: unknown argument: $1" >&2; exit 2 ;;
    esac
done

# Verify git is available + we're inside a repo
if ! command -v git >/dev/null 2>&1; then
    echo "ERROR: git not on PATH" >&2
    exit 2
fi
if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "ERROR: not inside a git repository" >&2
    exit 2
fi

# Owned-by-us submodule names (16 vasic-digital + 1 HelixDevelopment).
# These are the CURRENT names; the migration's target is snake_case.
NAMES=(
    "Auth"
    "Cache"
    "Challenges"
    "Concurrency"
    "Config"
    "Containers"
    "Database"
    "Discovery"
    "HelixQA"
    "HTTP3"
    "Mdns"
    "Middleware"
    "Observability"
    "RateLimiter"
    "Recovery"
    "Security"
    "Tracker-SDK"
)

# Count references using git grep against tracked files only.
# Pattern matches BOTH "submodules/<name>" (pre-Phase-6a) AND
# "submodules/<name>" (post-Phase-6a). The CamelCase child name is
# what Phase 6b targets — so any submodules/<CamelCase> ref after
# Phase 6a is still a §11.4.29 violation until Phase 6b renames it.
# We count case-insensitively on the parent dir but case-sensitively
# on the child name (we WANT to surface "submodules/cache" exactly
# because Phase 6b will rename Cache → cache).
count_refs_for_name() {
    local name=$1
    # Match both submodules/<name> AND submodules/<name>
    local pattern="[Ss]ubmodules/${name}"
    local refs files
    # git grep -c reports "<file>:<count>" lines; sum the counts.
    refs=$(git grep -c -E "$pattern" 2>/dev/null \
        | awk -F: '{s += $NF} END {print s+0}')
    files=$(git grep -l -E "$pattern" 2>/dev/null | wc -l | awk '{print $1+0}')
    echo "${refs}\t${files}"
}

# Aggregate "submodules/" references (the bare-prefix without a specific
# submodule name) — case-sensitive on capital-S because that's what
# Phase 6a is migrating away from. After Phase 6a this should be 0.
count_refs_for_bare_submodules() {
    local pattern="submodules/"
    local refs files
    refs=$(git grep -c "$pattern" 2>/dev/null \
        | awk -F: '{s += $NF} END {print s+0}')
    files=$(git grep -l "$pattern" 2>/dev/null | wc -l | awk '{print $1+0}')
    echo "${refs}\t${files}"
}

# Top-N files for a given name (case-insensitive on parent)
list_top_files_for_name() {
    local name=$1
    local n=$2
    local pattern="[Ss]ubmodules/${name}"
    git grep -c -E "$pattern" 2>/dev/null \
        | sort -t: -k2 -rn \
        | head -n "$n"
}

emit_header() {
    case "$FORMAT" in
        tsv) printf 'NAME\tREFS\tFILES\n' ;;
        md)  printf '| Name | Refs | Files |\n|---|---:|---:|\n' ;;
    esac
}

emit_row() {
    local name=$1 refs=$2 files=$3
    case "$FORMAT" in
        tsv) printf '%s\t%s\t%s\n' "$name" "$refs" "$files" ;;
        md)  printf '| %s | %s | %s |\n' "$name" "$refs" "$files" ;;
    esac
}

main() {
    emit_header

    for name in "${NAMES[@]}"; do
        IFS=$'\t' read -r refs files <<<"$(echo -e "$(count_refs_for_name "$name")")"
        emit_row "$name" "$refs" "$files"

        if [[ "$TOP_FILES" -gt 0 ]]; then
            if [[ "$FORMAT" == "md" ]]; then
                echo ""
                echo "<details><summary>Top ${TOP_FILES} files for ${name}</summary>"
                echo ""
                echo '```'
            fi
            list_top_files_for_name "$name" "$TOP_FILES"
            if [[ "$FORMAT" == "md" ]]; then
                echo '```'
                echo "</details>"
                echo ""
            fi
        fi
    done

    # Bare-prefix submodules/ aggregate
    IFS=$'\t' read -r refs files <<<"$(echo -e "$(count_refs_for_bare_submodules)")"
    emit_row "TOTAL_Submodules" "$refs" "$files"
}

if [[ "$NAMES_ONLY" -eq 1 ]]; then
    emit_header
    for name in "${NAMES[@]}"; do
        IFS=$'\t' read -r refs files <<<"$(echo -e "$(count_refs_for_name "$name")")"
        emit_row "$name" "$refs" "$files"
    done
else
    main
fi

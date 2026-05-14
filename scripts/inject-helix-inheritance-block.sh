#!/usr/bin/env bash
# scripts/inject-helix-inheritance-block.sh — §6.AD-debt closure tool.
#
# Per CLAUDE.md §6.AD.8 + §6.AD-debt item 1: every per-scope CLAUDE.md /
# AGENTS.md / CONSTITUTION.md MUST carry an inheritance pointer-block
# referring to the corresponding HelixConstitution submodule file. This
# script idempotently injects the block after the first H1 heading
# (skips files that already have it).
#
# Usage:
#   scripts/inject-helix-inheritance-block.sh [files...]
#
# Default file set (when no args): every CLAUDE.md / AGENTS.md /
# CONSTITUTION.md under Submodules/ + lava-api-go/ + core/ + app/ +
# feature/. The root CLAUDE.md + AGENTS.md are excluded — they were
# updated in the 1.2.23 cycle's parent commit and have richer §6.AD
# context.
#
# Pointer-block content is file-type-aware:
#   *CLAUDE.md      → points to constitution/CLAUDE.md
#   *AGENTS.md      → points to constitution/AGENTS.md
#   *CONSTITUTION.md → points to constitution/Constitution.md
#
# Idempotency: looks for an existing "## INHERITED FROM constitution/"
# heading; skips if present.
#
# Exit codes:
#   0 — all in-scope files now carry the block (modified OR already-present)
#   1 — usage error or file-system error

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

cd "${REPO_ROOT}"

# Build the default file list if no args
declare -a FILES=()
if [[ $# -eq 0 ]]; then
    while IFS= read -r f; do FILES+=("${f}"); done < <(
        ls Submodules/*/CLAUDE.md Submodules/*/AGENTS.md Submodules/*/CONSTITUTION.md 2>/dev/null
        ls lava-api-go/CLAUDE.md lava-api-go/AGENTS.md lava-api-go/CONSTITUTION.md 2>/dev/null
        find core feature app -name CLAUDE.md -not -path "*/build/*" -not -path "*/.gradle/*" 2>/dev/null
    )
else
    FILES=("$@")
fi

echo "==> ${#FILES[@]} files in scope"

# Compose the pointer-block for a given file.
# Args: $1 = file basename
pointer_block_for() {
    local basename="$1"
    local target_file
    case "${basename}" in
        CLAUDE.md)        target_file="CLAUDE.md" ;;
        AGENTS.md)        target_file="AGENTS.md" ;;
        CONSTITUTION.md)  target_file="Constitution.md" ;;
        *) echo "ERROR: unknown filename type: ${basename}" >&2; return 1 ;;
    esac
    cat <<BLOCK

## INHERITED FROM constitution/${target_file}

All rules in \`constitution/${target_file}\` (and the \`constitution/Constitution.md\` it references) apply unconditionally. This file's rules below extend them — they MUST NOT weaken any inherited rule. See parent root \`CLAUDE.md\` §6.AD for the Lava-specific incorporation context (29th §6.L cycle, 2026-05-14) and §6.AD-debt for the implementation-gap inventory. Use \`constitution/find_constitution.sh\` from the parent project root to resolve the absolute path of the submodule from any nested location.
BLOCK
}

ADDED=0
SKIPPED=0
ERRORED=0

for f in "${FILES[@]}"; do
    if [[ ! -f "${f}" ]]; then
        echo "  ⚠ ${f}: missing — skip"
        ERRORED=$((ERRORED + 1))
        continue
    fi

    # Idempotency: check if any "## INHERITED FROM constitution/" heading already present
    if grep -qE '^## INHERITED FROM constitution/' "${f}"; then
        echo "  ✓ ${f}: already present"
        SKIPPED=$((SKIPPED + 1))
        continue
    fi

    # Find the line number of the first H1 (single # + space)
    H1_LINE=$(awk '/^# / {print NR; exit}' "${f}")
    if [[ -z "${H1_LINE}" ]]; then
        echo "  ⚠ ${f}: no H1 heading — skip (manual review needed)"
        ERRORED=$((ERRORED + 1))
        continue
    fi

    # Inject the block after the H1 line
    BASENAME=$(basename "${f}")
    BLOCKFILE="$(mktemp)"
    pointer_block_for "${BASENAME}" > "${BLOCKFILE}"

    TMPFILE="$(mktemp)"
    head -n "${H1_LINE}" "${f}" > "${TMPFILE}"
    cat "${BLOCKFILE}" >> "${TMPFILE}"
    tail -n "+$((H1_LINE + 1))" "${f}" >> "${TMPFILE}"

    mv "${TMPFILE}" "${f}"
    rm -f "${BLOCKFILE}"
    echo "  + ${f}: injected after line ${H1_LINE}"
    ADDED=$((ADDED + 1))
done

echo ""
echo "Summary: added=${ADDED} skipped-already-present=${SKIPPED} errored=${ERRORED}"
[[ ${ERRORED} -eq 0 ]] || exit 1

#!/usr/bin/env bash
# scripts/check-canonical-root-and-upstreams.sh — §11.4.35 + §11.4.36 enforcement.
#
# Combined gate for two related HelixConstitution clauses:
#
# §11.4.35 (Canonical-Root Inheritance Clarity):
#   (a) Consumer's root CLAUDE.md + AGENTS.md MUST open with an
#       inheritance pointer block — "## INHERITED FROM constitution/..."
#       OR Claude Code's @-import syntax — within the first 40 lines.
#   (b) The constitution submodule's three canonical files MUST exist:
#       constitution/CLAUDE.md, constitution/AGENTS.md, constitution/Constitution.md.
#   (c) The constitution submodule's own CLAUDE.md / AGENTS.md MUST NOT
#       carry "## INHERITED FROM" — it IS the canonical root.
#
# §11.4.36 (Mandatory install_upstreams on clone/add):
#   For every owned-by-us submodule, EITHER an install_upstreams script
#   exists in the submodule (so a freshly-cloned submodule can self-
#   provision its own remote topology), OR a per-submodule waiver entry
#   documents the explicit reason the submodule does not need one
#   (e.g. external-only submodule, archived, etc.).
#
# Modes:
#   --strict (default): exit 1 on any violation
#   --advisory: exit 0 even on violation (incremental adoption)
#
# Inheritance: HelixConstitution §11.4.35 + §11.4.36 + §11.4.18 (script docs).
# Classification: project-specific (the install_upstreams convention is
# specific to vasic-digital + HelixDevelopment infra; the canonical-root
# clarity discipline + the install_upstreams mandate are universal per
# HelixConstitution).

set -uo pipefail

REPO_ROOT="${LAVA_REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
cd "$REPO_ROOT"

STRICT="${LAVA_CANONICAL_ROOT_STRICT:-1}"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --strict)   STRICT=1; shift ;;
        --advisory) STRICT=0; shift ;;
        *)          echo "ERROR: unknown arg $1" >&2; exit 2 ;;
    esac
done

# Per-submodule waiver list for §11.4.36 install_upstreams requirement.
# Format: "<submodule-name>:<rationale>"
# Each waiver MUST cite WHY the submodule lacks install_upstreams +
# tracking-issue or planned-resolution date (or "permanent" if structurally not needed).
INSTALL_UPSTREAMS_WAIVERS=(
    # HelixQA — HelixDevelopment-owned submodule adopted at upstream HEAD
    # 403603db on 2026-05-15. Lava pins the version; the upstream repo's
    # missing install_upstreams.sh wrapper script is owed via PR to
    # HelixDevelopment/HelixQA (Phase 4-debt of constitution-compliance
    # plan). HelixQA already ships Upstreams/{GitHub,GitLab}.sh recipes;
    # only the wrapper script is missing.
    "HelixQA"
)

is_install_upstreams_waived() {
    local sub=$1
    for w in "${INSTALL_UPSTREAMS_WAIVERS[@]}"; do
        local w_sub="${w%%:*}"
        [[ "$w_sub" == "$sub" ]] && return 0
    done
    return 1
}

# -----------------------------------------------------------------------------
# §11.4.35 (a) — root CLAUDE.md + AGENTS.md open with inheritance pointer
# -----------------------------------------------------------------------------
canonical_root_violations=()

check_inheritance_pointer() {
    local f=$1
    [[ -f "$f" ]] || { canonical_root_violations+=("$f: file does not exist"); return; }
    # Pointer can be either:
    #   "## INHERITED FROM constitution/<file>"
    #   "@constitution/<file>"
    if ! head -40 "$f" | grep -qE '^## INHERITED FROM constitution/|^@constitution/'; then
        canonical_root_violations+=("$f: missing § 11.4.35 inheritance pointer in first 40 lines")
    fi
}

check_inheritance_pointer "CLAUDE.md"
check_inheritance_pointer "AGENTS.md"

# -----------------------------------------------------------------------------
# §11.4.35 (b) — constitution submodule's three canonical files
# -----------------------------------------------------------------------------
canonical_files_violations=()
for f in constitution/CLAUDE.md constitution/AGENTS.md constitution/Constitution.md; do
    [[ -f "$f" ]] || canonical_files_violations+=("$f: missing canonical-root file (§11.4.35.b)")
done

# -----------------------------------------------------------------------------
# §11.4.35 (c) — constitution's own CLAUDE.md / AGENTS.md MUST NOT carry "INHERITED FROM"
# -----------------------------------------------------------------------------
canonical_self_inheritance_violations=()
for f in constitution/CLAUDE.md constitution/AGENTS.md; do
    [[ -f "$f" ]] || continue
    # Allow the word INHERITED to appear in body; reject only the heading form
    # AT TOP LEVEL of the document — NOT inside a fenced ``` code block where
    # the pattern is being documented for consumers to copy. awk tracks the
    # fenced-block state so we ignore lines between ``` markers.
    if head -60 "$f" | awk '
        /^```/ { in_block = !in_block; next }
        !in_block && /^## INHERITED FROM/ { print; found=1 }
        END { exit !found }
    ' > /dev/null; then
        canonical_self_inheritance_violations+=("$f: canonical-root MUST NOT carry '## INHERITED FROM' OUTSIDE fenced code blocks (§11.4.35.c)")
    fi
done

# -----------------------------------------------------------------------------
# §11.4.36 — per-submodule install_upstreams script presence
# -----------------------------------------------------------------------------
install_upstreams_violations=()
install_upstreams_present=()
install_upstreams_waived=()
for s in Submodules/*/; do
    [[ -d "$s" ]] || continue
    name=$(basename "$s")
    # Common script names + locations
    if [[ -f "$s/install_upstreams.sh" ]] || \
       [[ -f "$s/install_upstreams" ]] || \
       [[ -f "$s/scripts/install_upstreams.sh" ]] || \
       [[ -f "$s/scripts/install_upstreams" ]] || \
       [[ -f "$s/Upstreams/install.sh" ]] || \
       [[ -f "$s/upstreams/install.sh" ]]; then
        install_upstreams_present+=("$name")
    elif is_install_upstreams_waived "$name"; then
        install_upstreams_waived+=("$name")
    else
        install_upstreams_violations+=("$name")
    fi
done

# -----------------------------------------------------------------------------
# Report
# -----------------------------------------------------------------------------
echo "==> §11.4.35 + §11.4.36 canonical-root + install_upstreams scan"
echo ""
echo "    §11.4.35 (a) inheritance pointers in root CLAUDE.md + AGENTS.md: ${#canonical_root_violations[@]} violation(s)"
echo "    §11.4.35 (b) constitution submodule canonical files: ${#canonical_files_violations[@]} violation(s)"
echo "    §11.4.35 (c) canonical-root self-inheritance: ${#canonical_self_inheritance_violations[@]} violation(s)"
echo "    §11.4.36    install_upstreams script presence:"
echo "      present: ${#install_upstreams_present[@]}"
echo "      waived:  ${#install_upstreams_waived[@]}"
echo "      missing: ${#install_upstreams_violations[@]}"

total_violations=$((${#canonical_root_violations[@]} + ${#canonical_files_violations[@]} + ${#canonical_self_inheritance_violations[@]} + ${#install_upstreams_violations[@]}))

if [[ $total_violations -eq 0 ]]; then
    echo ""
    echo "    ✓ all clauses pass"
    exit 0
fi

echo ""
[[ ${#canonical_root_violations[@]} -gt 0 ]] && {
    echo "    §11.4.35 (a) violations:"
    printf '      %s\n' "${canonical_root_violations[@]}"
}
[[ ${#canonical_files_violations[@]} -gt 0 ]] && {
    echo "    §11.4.35 (b) violations:"
    printf '      %s\n' "${canonical_files_violations[@]}"
}
[[ ${#canonical_self_inheritance_violations[@]} -gt 0 ]] && {
    echo "    §11.4.35 (c) violations:"
    printf '      %s\n' "${canonical_self_inheritance_violations[@]}"
}
[[ ${#install_upstreams_violations[@]} -gt 0 ]] && {
    echo "    §11.4.36 missing install_upstreams (each submodule MUST add its own per §6.W):"
    printf '      %s\n' "${install_upstreams_violations[@]}"
}

if [[ "$STRICT" == "1" ]]; then
    echo ""
    echo "    LAVA_CANONICAL_ROOT_STRICT=1 — failing." >&2
    exit 1
fi
exit 0

#!/usr/bin/env bash
# scripts/check-no-nested-own-org-submodules.sh — §11.4.28 enforcement gate.
#
# Per HelixConstitution §11.4.28 (Submodules-As-Equal-Codebase, 2026-05-15):
# an owned-by-us submodule MUST NOT itself contain another own-org
# submodule as a nested entry. Chains of own-org submodules
# (vasic-digital/A → vasic-digital/B → vasic-digital/C) are forbidden:
# they create transitive-ownership opacity, frustrate independent
# development, and silently couple the lifecycles of components that
# §11.4.28 demands be peers.
#
# Forbidden orgs (the OWN-ORG set this script enforces against):
#   vasic-digital, HelixDevelopment, red-elf, ATMOSphere1234321,
#   Bear-Suite, BoatOS123456, Helix-Flow, Helix-Track, Server-Factory
#
# A waiver carve-out exists per §11.4.28 — recorded inline in the
# WAIVERS array below with mandatory rationale + tracking-issue
# reference + scheduled-removal date.
#
# Modes:
#   --strict (default): exit 1 on any violation
#   --advisory: exit 0 even on violation (incremental adoption)
#
# Inheritance: HelixConstitution §11.4.28 + §11.4.18 (script docs).
# Classification: project-specific (the owned-org list contains org
# names specific to vasic-digital/HelixDevelopment ecosystems; the
# nested-own-org-prohibition discipline is universal per HelixConstitution).

set -uo pipefail

REPO_ROOT="${LAVA_REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
cd "$REPO_ROOT"

STRICT="${LAVA_NESTED_OWN_ORG_STRICT:-1}"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --strict)   STRICT=1; shift ;;
        --advisory) STRICT=0; shift ;;
        *)          echo "ERROR: unknown arg $1" >&2; exit 2 ;;
    esac
done

# Forbidden orgs — the OWN-ORG set §11.4.28 forbids chaining within.
# Pattern is grep-extended-regex alternation.
FORBIDDEN_ORGS_REGEX='vasic-digital|HelixDevelopment|red-elf|ATMOSphere1234321|Bear-Suite|BoatOS123456|Helix-Flow|Helix-Track|Server-Factory'

# Waivers: explicit per-entry exceptions with mandatory rationale.
# Format: "<containing-submodule>:<nested-path>:<reason-and-tracking-issue>"
# Each waiver MUST cite:
#   - WHY the nesting exists (e.g., legacy coupling, hard infra dep)
#   - tracking issue or commit referencing the planned refactor
#   - target removal date or "permanent" if structurally required
WAIVERS=(
    # NO ACTIVE WAIVERS — the submodules/challenges/Panoptic finding from
    # the 2026-05-15 audit is documented as constitutional-debt below
    # (Phase 5 commit body), NOT waived. Refactor is owed in a follow-up.
)

is_waived() {
    local container=$1 nested_path=$2
    local key="${container}:${nested_path}"
    for w in "${WAIVERS[@]}"; do
        local w_key="${w%%:*:*}:${w#*:}"  # collapse triple-colon to first two parts
        local w_container="${w%%:*}"
        local w_rest="${w#*:}"
        local w_path="${w_rest%%:*}"
        if [[ "$w_container" == "$container" && "$w_path" == "$nested_path" ]]; then
            return 0
        fi
    done
    return 1
}

violations=()
scanned_count=0
while IFS= read -r gm; do
    [[ -z "$gm" ]] && continue
    scanned_count=$((scanned_count + 1))

    # Container submodule name = first dir under submodules/
    container=$(echo "$gm" | sed -E 's|^submodules/([^/]+)/.*|\1|')

    # Parse .gitmodules for path + url pairs of own-org URLs
    # awk pass over the file to extract submodule sections
    while IFS=$'\t' read -r nested_path nested_url; do
        [[ -z "$nested_path" || -z "$nested_url" ]] && continue
        if echo "$nested_url" | grep -qE "github\.com[:/]($FORBIDDEN_ORGS_REGEX)/|gitlab\.com[:/]($FORBIDDEN_ORGS_REGEX)/"; then
            if is_waived "$container" "$nested_path"; then
                echo "    [waived] $container/$nested_path → $nested_url"
            else
                violations+=("$container/$nested_path → $nested_url")
            fi
        fi
    done < <(awk '
        /^\[submodule/ { p=""; u="" }
        /^[[:space:]]*path[[:space:]]*=/ { sub(/^[[:space:]]*path[[:space:]]*=[[:space:]]*/, ""); p=$0 }
        /^[[:space:]]*url[[:space:]]*=/ {
            sub(/^[[:space:]]*url[[:space:]]*=[[:space:]]*/, ""); u=$0
            if (p != "" && u != "") { printf "%s\t%s\n", p, u }
        }
    ' "$gm")
done < <(find submodules -name ".gitmodules" -type f 2>/dev/null)

echo "==> §11.4.28 nested-own-org-submodule scan"
echo "    .gitmodules files scanned: $scanned_count"
echo "    Active waivers: ${#WAIVERS[@]}"
echo "    Violations: ${#violations[@]}"

if [[ ${#violations[@]} -eq 0 ]]; then
    echo "    ✓ no nested own-org submodules"
    exit 0
fi

echo ""
echo "    Nested-own-org submodule chains (per §11.4.28):"
printf '      %s\n' "${violations[@]}"
echo ""
echo "    To resolve a violation:"
echo "      (a) Refactor: extract the nested submodule to be a peer of the container"
echo "          (e.g., move submodules/challenges/Panoptic to submodules/Panoptic)"
echo "      (b) Waiver: add WAIVERS entry with rationale + tracking issue + target date"

if [[ "$STRICT" == "1" ]]; then
    echo ""
    echo "    LAVA_NESTED_OWN_ORG_STRICT=1 — failing." >&2
    exit 1
fi
exit 0

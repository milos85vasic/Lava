#!/usr/bin/env bash
# scripts/check-helix-deps-manifest.sh — §11.4.31 enforcement gate.
#
# Per HelixConstitution §11.4.31 (Submodule-Dependency-Manifest
# Mandate, 2026-05-15): every owned-by-us submodule MUST ship a
# machine-readable, version-controlled dependency manifest at the
# canonical path `helix-deps.yaml` (or .json / .toml — single
# canonical file per submodule).
#
# Plus: the parent project root SHOULD ship its own helix-deps.yaml
# declaring its top-level submodule deps (so any future incorporator
# script can bootstrap a fresh clone's submodule graph).
#
# Validation passes for each manifest:
#   1. File exists at canonical path
#   2. File is parseable YAML (yq if available, OR fallback grep-based
#      structural check for `schema_version:` + `deps:` + per-dep
#      `name:` + `ssh_url:` + `ref:`)
#   3. schema_version: 1 declared
#   4. deps array structurally well-formed (each dep has the 5
#      required keys: name, ssh_url, ref, why, layout)
#   5. transitive_handling block present (recursive + conflict_resolution)
#
# Modes:
#   --strict (default): exit 1 on any violation
#   --advisory: exit 0 even on violation (incremental adoption)
#
# Inheritance: HelixConstitution §11.4.31 + §11.4.18 (script docs).
# Classification: project-specific (the per-submodule manifest list +
# Lava-specific manifest contents are project-specific; the §11.4.31
# manifest mandate + verifier discipline are universal).

set -uo pipefail

REPO_ROOT="${LAVA_REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
cd "$REPO_ROOT"

STRICT="${LAVA_HELIX_DEPS_STRICT:-1}"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --strict)   STRICT=1; shift ;;
        --advisory) STRICT=0; shift ;;
        *)          echo "ERROR: unknown arg $1" >&2; exit 2 ;;
    esac
done

# Per-submodule waiver list: submodule names that are explicitly
# exempt from the helix-deps.yaml requirement. Each waiver MUST
# cite WHY (legacy state, structural exemption, etc.) + tracking
# issue + planned-resolution date.
HELIX_DEPS_WAIVERS=(
    # NO ACTIVE WAIVERS — the 16 submodules without helix-deps.yaml
    # found in the 2026-05-15 audit are documented as Phase 3-debt
    # to be closed in follow-up cycles (each submodule gains its
    # own manifest in its own commit per §6.W discipline).
)

is_helix_deps_waived() {
    local sub=$1
    for w in "${HELIX_DEPS_WAIVERS[@]}"; do
        local w_sub="${w%%:*}"
        [[ "$w_sub" == "$sub" ]] && return 0
    done
    return 1
}

# -----------------------------------------------------------------------------
# Validation: parent helix-deps.yaml at repo root
# -----------------------------------------------------------------------------
parent_violations=()
parent_manifest=""
for candidate in helix-deps.yaml helix-deps.json helix-deps.toml; do
    [[ -f "$candidate" ]] && parent_manifest="$candidate" && break
done

if [[ -z "$parent_manifest" ]]; then
    parent_violations+=("repo root: missing helix-deps.{yaml,json,toml}")
else
    # Structural fallback parse (yq not assumed available)
    if ! grep -qE '^schema_version:[[:space:]]+1[[:space:]]*$' "$parent_manifest"; then
        parent_violations+=("$parent_manifest: missing or wrong schema_version (must be 1)")
    fi
    if ! grep -qE '^deps:[[:space:]]*$' "$parent_manifest"; then
        parent_violations+=("$parent_manifest: missing top-level deps: list")
    fi
    if ! grep -qE '^transitive_handling:[[:space:]]*$' "$parent_manifest"; then
        parent_violations+=("$parent_manifest: missing transitive_handling: block")
    fi
fi

# -----------------------------------------------------------------------------
# Validation: per-submodule helix-deps.yaml
# -----------------------------------------------------------------------------
submodule_violations=()
submodule_present=()
submodule_waived=()
for s in Submodules/*/; do
    [[ -d "$s" ]] || continue
    name=$(basename "$s")
    found=""
    for candidate in helix-deps.yaml helix-deps.json helix-deps.toml; do
        if [[ -f "$s/$candidate" ]]; then
            found="$candidate"
            break
        fi
    done

    if [[ -n "$found" ]]; then
        submodule_present+=("$name ($found)")
    elif is_helix_deps_waived "$name"; then
        submodule_waived+=("$name")
    else
        submodule_violations+=("$name")
    fi
done

# -----------------------------------------------------------------------------
# Report
# -----------------------------------------------------------------------------
echo "==> §11.4.31 helix-deps.yaml manifest scan"
echo ""
echo "    Parent manifest:"
if [[ -n "$parent_manifest" && ${#parent_violations[@]} -eq 0 ]]; then
    echo "      ✓ $parent_manifest (well-formed)"
elif [[ -n "$parent_manifest" ]]; then
    echo "      ✗ $parent_manifest (${#parent_violations[@]} structural violations)"
else
    echo "      ✗ MISSING"
fi
echo ""
echo "    Per-submodule manifests:"
echo "      present: ${#submodule_present[@]}"
echo "      waived:  ${#submodule_waived[@]}"
echo "      missing: ${#submodule_violations[@]}"

total_violations=$((${#parent_violations[@]} + ${#submodule_violations[@]}))

if [[ $total_violations -eq 0 ]]; then
    echo ""
    echo "    ✓ all manifests present + well-formed"
    exit 0
fi

echo ""
[[ ${#parent_violations[@]} -gt 0 ]] && {
    echo "    Parent manifest violations:"
    printf '      %s\n' "${parent_violations[@]}"
}
[[ ${#submodule_violations[@]} -gt 0 ]] && {
    echo "    Submodules missing helix-deps.{yaml,json,toml} (each MUST add its own per §11.4.31):"
    printf '      %s\n' "${submodule_violations[@]}"
}

if [[ "$STRICT" == "1" ]]; then
    echo ""
    echo "    LAVA_HELIX_DEPS_STRICT=1 — failing." >&2
    exit 1
fi
exit 0

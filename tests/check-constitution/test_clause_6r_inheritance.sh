#!/usr/bin/env bash
# Asserts §6.R inheritance reference appears in every Submodules/*/CLAUDE.md.
#
# Heading-anchored pattern (`## §6.R — No-Hardcoding Mandate`) — a
# passing mention of the bare phrase in a notes/history paragraph MUST
# NOT satisfy the §6.F inheritance gate. The 16 submodule paragraphs
# all use this exact heading prefix; tightening here keeps test, checker
# and clause body in lockstep.
set -euo pipefail
cd "$(dirname "$0")/../.."

# HelixDevelopment-owned submodules are exempt from Lava-specific clause
# heading inheritance — they ship the canonical-root §INHERITED FROM
# Helix Constitution pointer block instead. Mirrors the HELIX_DEV_OWNED
# list in scripts/check-constitution.sh.
HELIX_DEV_OWNED=("HelixQA")
is_helix_dev_owned() {
  local path=$1
  for owned in "${HELIX_DEV_OWNED[@]}"; do
    [[ "$path" == *"/$owned/"* ]] && return 0
    [[ "$path" == *"/$owned"* ]] && return 0
  done
  return 1
}

missing=()
for sub in Submodules/*/CLAUDE.md; do
  is_helix_dev_owned "$sub" && continue
  if ! grep -qF '## §6.R — No-Hardcoding Mandate' "$sub"; then
    missing+=("$sub")
  fi
done
if [[ ${#missing[@]} -eq 0 ]]; then
  echo "PASS test_clause_6r_inheritance"
  exit 0
fi
echo "FAIL test_clause_6r_inheritance: missing in:" >&2
printf '  %s\n' "${missing[@]}" >&2
exit 1

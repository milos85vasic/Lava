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
missing=()
for sub in Submodules/*/CLAUDE.md; do
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

#!/usr/bin/env bash
# Asserts §6.R inheritance reference appears in every Submodules/*/CLAUDE.md.
set -euo pipefail
cd "$(dirname "$0")/../.."
missing=()
for sub in Submodules/*/CLAUDE.md; do
  if ! grep -qF '6.R — No-Hardcoding Mandate' "$sub"; then
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

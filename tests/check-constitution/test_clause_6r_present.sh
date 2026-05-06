#!/usr/bin/env bash
# Asserts §6.R clause is present in root CLAUDE.md.
set -euo pipefail
cd "$(dirname "$0")/../.."
if grep -qF '##### 6.R — No-Hardcoding Mandate' CLAUDE.md; then
  echo "PASS test_clause_6r_present"
  exit 0
fi
echo "FAIL test_clause_6r_present: §6.R missing from CLAUDE.md" >&2
exit 1

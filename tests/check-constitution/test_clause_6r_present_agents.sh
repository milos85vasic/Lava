#!/usr/bin/env bash
# Asserts §6.R clause is present in AGENTS.md.
#
# Mirror of test_clause_6r_present.sh. The §6.R spec mandates the clause
# appears in BOTH CLAUDE.md and AGENTS.md (the project keeps them in
# lockstep so that any agent or contributor reading either file sees the
# same constitutional surface). A regression that drops §6.R from one
# but keeps it in the other is itself a §6.J/§6.L bluff vector — the
# checker would still pass for CLAUDE.md while AGENTS.md silently lost
# the rule.
set -euo pipefail
cd "$(dirname "$0")/../.."
if grep -qF '##### 6.R — No-Hardcoding Mandate' AGENTS.md; then
  echo "PASS test_clause_6r_present_agents"
  exit 0
fi
echo "FAIL test_clause_6r_present_agents: §6.R missing from AGENTS.md" >&2
exit 1

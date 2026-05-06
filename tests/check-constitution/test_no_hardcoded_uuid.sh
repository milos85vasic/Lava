#!/usr/bin/env bash
# Asserts no 36-char UUIDs in tracked source outside the §6.R exemption set.
#
# Calls scripts/scan-no-hardcoded-uuid.sh DIRECTLY (Approach A from the
# code review). The previous form invoked check-constitution.sh and had
# a silent-PASS fall-through that reported green when the checker failed
# on an UNRELATED earlier gate AND a real UUID violation existed — i.e.
# the gate this test claims to enforce was never evaluated, but the test
# reported PASS anyway. That is the canonical §6.J bluff: tests must
# guarantee the rule they cover holds; "checker happened to fail
# upstream so we couldn't tell" must be SKIP, never PASS.
#
# By calling the standalone scanner, this test ALWAYS evaluates exactly
# the §6.R UUID rule. Exit codes pass through unchanged: 0 = no UUIDs;
# non-zero = violation; the test surface mirrors that semantics.
set -euo pipefail
cd "$(dirname "$0")/../.."

out=$(mktemp)
trap 'rm -f "$out"' EXIT

if bash scripts/scan-no-hardcoded-uuid.sh > "$out" 2>&1; then
  echo "PASS test_no_hardcoded_uuid"
  exit 0
fi

# Scanner exited non-zero. The only documented non-zero is "UUID violation
# found" (exit 1). Treat anything non-zero as failure of the §6.R rule.
echo "FAIL test_no_hardcoded_uuid:" >&2
cat "$out" >&2
exit 1

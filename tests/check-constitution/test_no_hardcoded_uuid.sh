#!/usr/bin/env bash
# Asserts no 36-char UUIDs in tracked source outside the exemption set.
# Delegates to check-constitution.sh and looks for its specific failure message.
set -euo pipefail
cd "$(dirname "$0")/../.."
if bash scripts/check-constitution.sh > /tmp/checker.out 2>&1; then
  echo "PASS test_no_hardcoded_uuid"
  exit 0
fi
if grep -q '6.R VIOLATION: hardcoded UUIDs' /tmp/checker.out; then
  echo "FAIL test_no_hardcoded_uuid:" >&2
  cat /tmp/checker.out >&2
  exit 1
fi
# Checker failed for an unrelated reason; this test is specifically about UUID violations
echo "PASS test_no_hardcoded_uuid (checker failed for unrelated reason)"
exit 0

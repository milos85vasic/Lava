#!/usr/bin/env bash
# scripts/bluff-hunt.sh — Seventh Law clause 5 (Recurring Bluff Hunt).
#
# Picks 5 random *Test.kt files, prompts the operator to apply a deliberate
# mutation to the production code each one claims to cover, runs the test,
# and asserts the test fails. If a test still passes, it is a bluff —
# the operator records it in the evidence file and either rewrites or
# removes it.
#
# Usage:
#   ./scripts/bluff-hunt.sh                    # interactive
#   ./scripts/bluff-hunt.sh --evidence <date>  # writes evidence file
#
# This script is INTERACTIVE by design. The mutation is operator-driven;
# the script frames the protocol but does not pretend to mutate the
# codebase autonomously (which would itself be a class of bluff).

set -euo pipefail

mode=interactive
date_tag="$(date +%Y-%m-%d)"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --evidence) shift; date_tag="$1"; mode=evidence; shift;;
    --help|-h) echo "Usage: $0 [--evidence YYYY-MM-DD]"; exit 0;;
    *) echo "Unknown arg: $1"; exit 1;;
  esac
done

cd "$(dirname "$0")/.."

evidence_dir=".lava-ci-evidence/bluff-hunt"
mkdir -p "$evidence_dir"
evidence_file="$evidence_dir/${date_tag}.json"

# Pick 5 random test files
mapfile -t targets < <(find core feature -name '*Test.kt' -not -path '*/build/*' 2>/dev/null | shuf -n 5)

if [[ ${#targets[@]} -lt 5 ]]; then
  echo "warning: only ${#targets[@]} test files found; bluff hunt requires 5+. exiting."
  exit 1
fi

echo "==============================================================="
echo "Seventh Law clause 5 — Recurring Bluff Hunt"
echo "Date: $date_tag"
echo "==============================================================="
echo "Targets selected (5 random *Test.kt files):"
for i in "${!targets[@]}"; do
  echo "  $((i+1)). ${targets[$i]}"
done
echo
echo "For each target, perform the following:"
echo
echo "  1. Read the test file. Identify the production class it claims"
echo "     to cover (usually visible in the imports + the SUT instance)."
echo "  2. Open that production class. Apply a deliberate mutation that"
echo "     should cause the test to fail (e.g. swap a return value,"
echo "     comment out a method body, throw inside a critical branch)."
echo "  3. Run the test:"
echo "        ./gradlew <module>:test --tests \"<TestClassFQN>\" --no-daemon"
echo "  4. RECORD whether the test failed (good — not a bluff) or passed"
echo "     (BAD — bluff detected). For bluffs, classify why per Seventh"
echo "     Law clause 4 (mocked-SUT, count-only assertion, etc.)."
echo "  5. Revert the mutation. Confirm test passes again."
echo
echo "When done, run:"
echo "    $0 --evidence $date_tag"
echo "to scaffold the evidence JSON. Fill in the per-target results."
echo "==============================================================="

if [[ "$mode" == "evidence" ]]; then
  cat > "$evidence_file" <<EOF
{
  "date": "$date_tag",
  "protocol": "Seventh Law clause 5 — Recurring Bluff Hunt",
  "targets": [
EOF
  for i in "${!targets[@]}"; do
    sep=","
    if [[ $i -eq $((${#targets[@]}-1)) ]]; then sep=""; fi
    cat >> "$evidence_file" <<EOF
    {
      "test_file": "${targets[$i]}",
      "production_class_under_test": "FILL_IN",
      "mutation_applied": "FILL_IN",
      "test_outcome_after_mutation": "FILL_IN — pass=BLUFF, fail=clean",
      "test_failure_message_if_failed": "FILL_IN_OR_NULL",
      "bluff_classification_if_bluff": "FILL_IN_OR_NULL",
      "remediation": "FILL_IN — rewrite|remove|N/A",
      "reverted": true
    }$sep
EOF
  done
  cat >> "$evidence_file" <<'EOF'
  ],
  "summary": {
    "bluffs_found": 0,
    "bluffs_remediated": 0,
    "phase_gate_pass": false
  }
}
EOF
  echo "Scaffold written to $evidence_file."
  echo "Edit each FILL_IN, then commit the evidence file. Phase cannot be"
  echo "marked complete until summary.phase_gate_pass is true."
fi

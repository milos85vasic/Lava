#!/usr/bin/env bash
# Hermetic test suite for scripts/pipeline/lib/anti-bluff-validate.sh (FR-004).
#
# This test constructs isolated, throwaway Evidence Record JSON fixtures
# (plus their raw_output_ref sibling files) under temp directories and
# calls the real production function `validate_evidence_record` against
# each one. It never touches this repository's real evidence directories.
#
# validate_evidence_record's signaling convention (documented in the
# library's own header comment): exit code only — 0 == accepted
# ("validated"), 1 == rejected ("REJECTED: <reason>"). The function ALSO
# rewrites the record's own anti_bluff_status field in place; this test
# checks BOTH the exit code and the rewritten field, since either one
# lying would be exactly the kind of bluff this component exists to
# prevent.
#
# Exit 0 if every case passes; non-zero otherwise.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LIB_UNDER_TEST="${REPO_ROOT}/scripts/pipeline/lib/anti-bluff-validate.sh"

if [[ ! -f "$LIB_UNDER_TEST" ]]; then
  echo "FAIL: library under test not found: $LIB_UNDER_TEST"
  exit 1
fi
if ! command -v jq >/dev/null 2>&1; then
  echo "FAIL: jq is required to run this test suite but was not found on PATH"
  exit 1
fi

# shellcheck source=/dev/null
source "$LIB_UNDER_TEST"

FIXTURE_DIRS=()
cleanup() {
  for d in "${FIXTURE_DIRS[@]:-}"; do
    if [[ -n "$d" && -d "$d" ]]; then
      rm -rf -- "$d"
    fi
  done
}
trap cleanup EXIT

# make_fixture_dir <name> — creates a fresh temp dir. Echoes its path.
make_fixture_dir() {
  local name="$1"
  local dir
  dir="$(mktemp -d "${TMPDIR:-/tmp}/anti-bluff-fixture-${name}-XXXXXX")"
  FIXTURE_DIRS+=("$dir")
  echo "$dir"
}

# write_record <dir> <test_id> <category> <assertion_summary> <raw_output_ref> <initial_anti_bluff_status> [result=PASS]
# Writes record.json in <dir> via jq -n (no ad-hoc string interpolation
# into JSON — matching this project's "use jq, never regex-substitute
# into JSON" convention). Echoes the record path. The optional 7th
# argument lets callers exercise a non-PASS `result` value (e.g. FAIL or
# the new SKIPPED value); it defaults to "PASS" so every pre-existing call
# site (which never passed a 7th argument) is unaffected.
write_record() {
  local dir="$1" test_id="$2" category="$3" summary="$4" raw_ref="$5" initial_status="$6"
  local result="${7:-PASS}"
  jq -n \
    --arg test_id "$test_id" \
    --arg category "$category" \
    --arg command "echo fixture-command" \
    --arg result "$result" \
    --arg summary "$summary" \
    --arg raw_ref "$raw_ref" \
    --arg status "$initial_status" \
    '{test_id: $test_id, category: $category, command: $command, result: $result, assertion_summary: $summary, raw_output_ref: $raw_ref, anti_bluff_status: $status}' \
    > "${dir}/record.json"
  echo "${dir}/record.json"
}

FAILURES=0

# run_case <case_num> <accept|reject> <record_path> [reject_substr]
run_case() {
  local case_num="$1"
  local expect="$2"
  local record_path="$3"
  local reject_substr="${4:-}"

  local exit_code
  validate_evidence_record "$record_path" >/dev/null 2>&1 && exit_code=0 || exit_code=$?

  local final_status
  final_status="$(jq -r '.anti_bluff_status' "$record_path")"

  if [[ "$expect" == "accept" ]]; then
    if [[ "$exit_code" -eq 0 && "$final_status" == "validated" ]]; then
      echo "PASS: case ${case_num} accepted (exit 0, anti_bluff_status == 'validated')"
    else
      echo "FAIL: case ${case_num} expected accept (exit 0, status 'validated'); got exit=${exit_code} status='${final_status}'"
      FAILURES=$((FAILURES + 1))
    fi
  else
    if [[ "$exit_code" -ne 0 && "$final_status" == REJECTED:* ]]; then
      if [[ -n "$reject_substr" && "$final_status" != *"$reject_substr"* ]]; then
        echo "FAIL: case ${case_num} rejected as expected but reason did not mention '${reject_substr}': ${final_status}"
        FAILURES=$((FAILURES + 1))
      else
        echo "PASS: case ${case_num} rejected (exit ${exit_code}, anti_bluff_status='${final_status}')"
      fi
    else
      echo "FAIL: case ${case_num} expected reject (non-zero exit, status starting 'REJECTED: '); got exit=${exit_code} status='${final_status}'"
      FAILURES=$((FAILURES + 1))
    fi
  fi
}

# --- Case 1: generic bluff assertion_summary ("did not crash") ------------
dir1="$(make_fixture_dir "generic-bluff")"
echo "some real captured stdout, irrelevant to this case" > "${dir1}/raw.txt"
# Initial status is deliberately the OPPOSITE of the expected verdict, to
# prove re-evaluation is not sticky to whatever value was already there.
rec1="$(write_record "$dir1" "T1" "kotlin-unit" "did not crash" "raw.txt" "validated")"
run_case 1 "reject" "$rec1" "did not crash"

# --- Case 2: raw_output_ref points at a file that does not exist ----------
dir2="$(make_fixture_dir "missing-raw-ref")"
rec2="$(write_record "$dir2" "T2" "kotlin-unit" "expected 3 rows returned, got 3" "does-not-exist.txt" "REJECTED: placeholder")"
run_case 2 "reject" "$rec2" "does not exist"

# --- Case 3: raw_output_ref exists but is empty (0 bytes) -----------------
dir3="$(make_fixture_dir "empty-raw-ref")"
: > "${dir3}/empty.txt"
rec3="$(write_record "$dir3" "T3" "kotlin-unit" "expected 3 rows returned, got 3" "empty.txt" "REJECTED: placeholder")"
run_case 3 "reject" "$rec3" "empty"

# --- Case 4: real-device-challenge missing a falsifiability marker --------
dir4="$(make_fixture_dir "challenge-no-falsifiability")"
echo "BUILD SUCCESSFUL in 12s, 46 tests, 46 passed, 0 failed" > "${dir4}/raw.txt"
rec4="$(write_record "$dir4" "T4" "real-device-challenge" "Challenge26 renders the provider list with 4 entries" "raw.txt" "validated")"
run_case 4 "reject" "$rec4" "FALSIFIABILITY"

# --- Case 5: well-formed non-Challenge record ------------------------------
dir5="$(make_fixture_dir "well-formed")"
cat > "${dir5}/raw.txt" <<'EOF'
> Task :core:preferences:test
EndpointConverterTest > fromJson decodes explicit port PASSED
BUILD SUCCESSFUL in 4s
12 tests completed, 0 failed
EOF
rec5="$(write_record "$dir5" "T5" "kotlin-unit" "expected 0 entries in Files array, got 0" "raw.txt" "REJECTED: placeholder")"
run_case 5 "accept" "$rec5"

# --- Case 6: well-formed real-device-challenge WITH falsifiability marker -
dir6="$(make_fixture_dir "challenge-with-falsifiability")"
echo "adb logcat capture: MenuScreen title node found, 1 match" > "${dir6}/raw.txt"
rec6="$(write_record "$dir6" "T6" "real-device-challenge" "Challenge26 asserts dominant color is Lava red; FALSIFIABILITY REHEARSAL: tint forced to monochrome white, assertion failed as expected with message 'expected red, got white', reverted, re-ran green" "raw.txt" "validated")"
run_case 6 "accept" "$rec6"

# --- Case 7: well-formed SKIPPED record with a real, specific skip reason -
# and a real, non-empty raw_output_ref -> must be validated. This is the
# design decision from research.md/data-model.md: a legitimately-skipped
# test (e.g. `t.Skip("POSTGRES_TEST_URL not set")`) reported honestly is
# not a bluff and must be accepted through the exact same anti-bluff gate
# as PASS/FAIL, not forced into either of those two values.
dir7="$(make_fixture_dir "skipped-well-formed")"
echo 'go test: --- SKIP: TestRealPostgresIntegration (0.00s)
    postgres_integration_test.go:42: POSTGRES_TEST_URL not set, skipping real-Postgres integration test' > "${dir7}/raw.txt"
rec7="$(write_record "$dir7" "T7" "go-unit-integration" "test genuinely did not execute: POSTGRES_TEST_URL environment variable is not set, so TestRealPostgresIntegration self-skipped via t.Skip() before any assertion ran" "raw.txt" "REJECTED: placeholder" "SKIPPED")"
run_case 7 "accept" "$rec7"

# --- Case 8: SKIPPED record with a generic/bluff-shaped assertion_summary -
# must still be REJECTED. SKIPPED does not bypass the existing generic-
# string anti-bluff check (Rule 1) -- "no crash" is exactly as much of a
# bluff phrase on a SKIPPED record as it would be on a PASS/FAIL one.
dir8="$(make_fixture_dir "skipped-generic-bluff")"
echo "some real captured stdout, irrelevant to this case" > "${dir8}/raw.txt"
rec8="$(write_record "$dir8" "T8" "go-unit-integration" "no crash" "raw.txt" "validated" "SKIPPED")"
run_case 8 "reject" "$rec8" "no crash"

# --- Case 9: result value outside the schema's PASS/FAIL/SKIPPED enum -----
# must be REJECTED. This is the genuine pre-implementation RED case for
# this task's change: validate_evidence_record did not previously inspect
# the record's `result` field's value AT ALL, so a record claiming an
# invalid result (anything other than PASS/FAIL/SKIPPED) was incorrectly
# ACCEPTED as "validated" as long as its assertion_summary/raw_output_ref
# happened to look fine -- a real, latent gap this task's enum check
# closes as a side effect of explicitly wiring in the new SKIPPED value
# (see the Bluff-Audit / design-judgment-call note in the final report).
dir9="$(make_fixture_dir "invalid-result-enum")"
echo "some real captured stdout, irrelevant to this case" > "${dir9}/raw.txt"
rec9="$(write_record "$dir9" "T9" "kotlin-unit" "expected 3 rows returned, got 3" "raw.txt" "validated" "BOGUS")"
run_case 9 "reject" "$rec9" "result"

# --- Case 10: real production false-positive regression test -------------
# Found 2026-08-21 during this feature's own verification pass, against
# this project's REAL Kotlin test suite: phase-02-test-kotlin.sh's PASS-
# path template quotes a test's own real, descriptive name verbatim inside
# an otherwise rich, specific summary. Several genuinely-passing real
# tests in this codebase (e.g.
# lava.credentials.CredentialsEntryRepositoryImplTest#"observe emits empty
# list when key holder is locked -- no crash on search path") have authors
# who chose names containing "no crash" as real, specific content -- not a
# lazy bluff. Before the fix, Rule 1's substring-anywhere match rejected 5
# such real PASS records from a single real run. This fixture reproduces
# the exact real shape (test name embedded via the wrapper's own template)
# and MUST be accepted -- a regression here would silently start
# rejecting genuine evidence again.
dir10="$(make_fixture_dir "real-testname-contains-bluff-phrase")"
echo "gradle test output: CredentialsEntryRepositoryImplTest > observe emits empty list when key holder is locked \xe2\x80\x94 no crash on search path PASSED" > "${dir10}/raw.txt"
rec10="$(write_record "$dir10" 'lava.credentials.CredentialsEntryRepositoryImplTest#observe emits empty list when key holder is locked \xe2\x80\x94 no crash on search path' "kotlin-unit" 'real JUnit XML testcase for "observe emits empty list when key holder is locked \xe2\x80\x94 no crash on search path" reports no <failure>/<error> element (Gradle test task genuinely executed it in 0.717s)' "raw.txt" "REJECTED: placeholder")"
run_case 10 "accept" "$rec10"

echo "---"
if [[ "$FAILURES" -eq 0 ]]; then
  echo "PASS: all anti-bluff-validate test cases passed"
  exit 0
else
  echo "FAIL: ${FAILURES} anti-bluff-validate test case(s) failed"
  exit 1
fi

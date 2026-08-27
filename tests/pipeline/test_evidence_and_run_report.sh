#!/usr/bin/env bash
# Hermetic test suite for scripts/pipeline/lib/evidence.sh and
# scripts/pipeline/lib/run-report.sh (T008/T009).
#
# Runs entirely inside a throwaway temp directory (its own fresh
# ".lava-ci-evidence/pipeline-runs/<run_id>/" tree) so it never touches
# this actual repository's evidence directory. Validates every generated
# JSON file both for strict JSON validity (python3 json.load) and for
# structural conformance to the two contract schemas:
#   specs/002-build-test-distribute-pipeline/contracts/evidence-record.schema.json
#   specs/002-build-test-distribute-pipeline/contracts/pipeline-run-report.schema.json
#
# Exit 0 if every case passes; non-zero otherwise.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
EVIDENCE_LIB="${REPO_ROOT}/scripts/pipeline/lib/evidence.sh"
RUN_REPORT_LIB="${REPO_ROOT}/scripts/pipeline/lib/run-report.sh"

FIXTURE_DIRS=()
cleanup() {
  for d in "${FIXTURE_DIRS[@]:-}"; do
    if [[ -n "$d" && -d "$d" ]]; then
      rm -rf -- "$d"
    fi
  done
}
trap cleanup EXIT

FAILURES=0
pass() { echo "PASS: $1"; }
fail() {
  echo "FAIL: $1"
  FAILURES=$((FAILURES + 1))
}

# _validate_against_schema <json-file> <required-keys-space-separated>
# Minimal structural check (no jsonschema library available on this host —
# confirmed broken native-extension import; see report). Confirms the file
# parses as strict JSON AND has exactly the schema's required top-level
# keys (via python3 stdlib json only).
_check_required_keys() {
  local json_file="$1" expected_keys="$2"
  python3 - "$json_file" "$expected_keys" <<'PYEOF'
import json
import sys

json_file, expected_keys_str = sys.argv[1:3]
expected_keys = set(expected_keys_str.split())

with open(json_file) as f:
    data = json.load(f)

actual_keys = set(data.keys())
if actual_keys != expected_keys:
    missing = expected_keys - actual_keys
    extra = actual_keys - expected_keys
    print(f"key mismatch: missing={sorted(missing)} extra={sorted(extra)}", file=sys.stderr)
    sys.exit(1)
sys.exit(0)
PYEOF
}

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/pipeline-lib-test-XXXXXX")"
FIXTURE_DIRS+=("$work_dir")
cd "$work_dir" || exit 1

# shellcheck source=/dev/null
source "$EVIDENCE_LIB"
# shellcheck source=/dev/null
source "$RUN_REPORT_LIB"

run_id="2026-08-21T14-30-00Z"
commit_sha="0123456789abcdef0123456789abcdef01234567"

# --- Check 1: init_run_report -----------------------------------------
echo "--- Check 1: init_run_report ---"
if report_path="$(init_run_report "$run_id" "$commit_sha" 2>&1)"; then
  if [[ -f "$report_path" ]]; then
    pass "init_run_report created $report_path"
  else
    fail "init_run_report reported success but '$report_path' does not exist"
  fi
else
  fail "init_run_report failed: $report_path"
fi

echo "--- report.json after init_run_report ---"
cat "$report_path" 2>/dev/null || true

if python3 -c "import json,sys; json.load(open(sys.argv[1]))" "$report_path" >/dev/null 2>&1; then
  pass "report.json is valid JSON after init"
else
  fail "report.json is NOT valid JSON after init"
fi

required_report_keys="run_id commit_sha started_at completed_at outcome phases build_artifacts evidence_summary distributions submodule_advances"
if _check_required_keys "$report_path" "$required_report_keys"; then
  pass "report.json has exactly the schema's required top-level keys after init"
else
  fail "report.json is missing/has extra top-level keys after init"
fi

# --- Check 2: write_evidence_record (PASS + FAIL) ----------------------
echo ""
echo "--- Check 2: write_evidence_record (PASS case) ---"
phase_dir=".lava-ci-evidence/pipeline-runs/${run_id}/phase-02"
raw_out_pass="${phase_dir}/raw/pass-test.log"
mkdir -p "$(dirname "$raw_out_pass")"
printf 'BUILD SUCCESSFUL in 4s\n1 test, 1 passed\n' > "$raw_out_pass"

pass_record_path="$(write_evidence_record \
  "$phase_dir" \
  "lava.login.LoginViewModelTest#validateUsername_clearsStaleServiceUnavailable" \
  "kotlin-unit" \
  "./gradlew :feature:login:testDebugUnitTest --tests lava.login.LoginViewModelTest" \
  "PASS" \
  "expected serviceUnavailable=null after UsernameChanged, got null" \
  "$raw_out_pass" 2>&1)"
pass_record_rc=$?

if [[ "$pass_record_rc" -eq 0 && -f "$pass_record_path" ]]; then
  pass "write_evidence_record (PASS) wrote $pass_record_path"
else
  fail "write_evidence_record (PASS) failed (rc=$pass_record_rc): $pass_record_path"
fi

echo "--- pass_record.json content ---"
cat "$pass_record_path" 2>/dev/null || true

if python3 -c "import json,sys; json.load(open(sys.argv[1]))" "$pass_record_path" >/dev/null 2>&1; then
  pass "PASS evidence record is valid JSON"
else
  fail "PASS evidence record is NOT valid JSON"
fi

required_evidence_keys="test_id category command result assertion_summary raw_output_ref anti_bluff_status"
if _check_required_keys "$pass_record_path" "$required_evidence_keys"; then
  pass "PASS evidence record has exactly the schema's required keys"
else
  fail "PASS evidence record is missing/has extra keys"
fi

echo ""
echo "--- Check 2b: write_evidence_record (FAIL case) ---"
raw_out_fail="${phase_dir}/raw/fail-test.log"
printf 'BUILD SUCCESSFUL in 6s\n1 test, 1 failed\njava.lang.AssertionError: expected:<0> but was:<3>\n' > "$raw_out_fail"

fail_record_path="$(write_evidence_record \
  "$phase_dir" \
  "lava.search.SearchInputViewModelTest#selectedProviders_defaultToConfiguredOnly" \
  "kotlin-unit" \
  "./gradlew :feature:search_input:testDebugUnitTest --tests lava.search.SearchInputViewModelTest" \
  "FAIL" \
  "expected 0 default-selected providers, got 3 (all hardcoded providers pre-selected)" \
  "$raw_out_fail" 2>&1)"
fail_record_rc=$?

if [[ "$fail_record_rc" -eq 0 && -f "$fail_record_path" ]]; then
  pass "write_evidence_record (FAIL) wrote $fail_record_path"
else
  fail "write_evidence_record (FAIL) failed (rc=$fail_record_rc): $fail_record_path"
fi

echo "--- fail_record.json content ---"
cat "$fail_record_path" 2>/dev/null || true

if python3 -c "import json,sys; json.load(open(sys.argv[1]))" "$fail_record_path" >/dev/null 2>&1; then
  pass "FAIL evidence record is valid JSON"
else
  fail "FAIL evidence record is NOT valid JSON"
fi

if _check_required_keys "$fail_record_path" "$required_evidence_keys"; then
  pass "FAIL evidence record has exactly the schema's required keys"
else
  fail "FAIL evidence record is missing/has extra keys"
fi

result_field="$(python3 -c "import json; print(json.load(open('$fail_record_path'))['result'])")"
if [[ "$result_field" == "FAIL" ]]; then
  pass "FAIL evidence record's result field is literally FAIL"
else
  fail "FAIL evidence record's result field is '$result_field', expected FAIL"
fi

# UPDATED 2026-08-26. This assertion previously required the placeholder to
# be the literal string "validated" — the SAME value the real validator
# writes on accept — which made "an independent validator examined this
# record and accepted it" and "no validator ever looked at this record"
# byte-identical on disk. Measured consequence: a real-device-challenge
# record asserting only "did not crash", never validated by anything,
# reached "phase-02-test: PASSED". The placeholder is now the REJECTED form
# (the only other value evidence-record.schema.json's
# ^(validated|REJECTED: .+)$ pattern permits), so an unvalidated record is
# fail-CLOSED. It must ALSO still be schema-legal and must NOT be the
# accept value.
anti_bluff_field="$(python3 -c "import json; print(json.load(open('$fail_record_path'))['anti_bluff_status'])")"
if [[ "$anti_bluff_field" != "validated" && "$anti_bluff_field" == REJECTED:* ]]; then
  pass "FAIL evidence record's anti_bluff_status placeholder is a schema-legal NOT-VALIDATED value, not the accept value 'validated'"
else
  fail "FAIL evidence record's anti_bluff_status is '$anti_bluff_field'; the writer's placeholder must never be 'validated' (that is the validator's accept value) and must match ^REJECTED: .+$ so an unvalidated record fails closed"
fi

# anti_bluff_status pattern check: ^(validated|REJECTED: .+)$
if [[ "$anti_bluff_field" =~ ^(validated|REJECTED:\ .+)$ ]]; then
  pass "anti_bluff_status matches the schema's required pattern"
else
  fail "anti_bluff_status '$anti_bluff_field' does NOT match schema pattern ^(validated|REJECTED: .+)\$"
fi

# --- Check 3: append_phase_result --------------------------------------
echo ""
echo "--- Check 3: append_phase_result ---"
if appended_path="$(append_phase_result "$run_id" "test" "PASS" "12.5" "$phase_dir" 2>&1)"; then
  pass "append_phase_result succeeded, wrote $appended_path"
else
  fail "append_phase_result failed: $appended_path"
fi

echo "--- report.json after append_phase_result ---"
cat "$report_path" 2>/dev/null || true

phase_count="$(python3 -c "import json; print(len(json.load(open('$report_path'))['phases']))")"
if [[ "$phase_count" == "1" ]]; then
  pass "report.json's phases[] has exactly 1 entry after one append_phase_result call"
else
  fail "report.json's phases[] has $phase_count entries, expected 1"
fi

phase_name_check="$(python3 -c "import json; print(json.load(open('$report_path'))['phases'][0]['name'])")"
phase_result_check="$(python3 -c "import json; print(json.load(open('$report_path'))['phases'][0]['result'])")"
phase_duration_check="$(python3 -c "import json; print(json.load(open('$report_path'))['phases'][0]['duration_seconds'])")"
phase_evidence_dir_check="$(python3 -c "import json; print(json.load(open('$report_path'))['phases'][0]['evidence_dir'])")"

if [[ "$phase_name_check" == "test" && "$phase_result_check" == "PASS" && "$phase_duration_check" == "12.5" && "$phase_evidence_dir_check" == "$phase_dir" ]]; then
  pass "appended phase entry has correct name/result/duration_seconds/evidence_dir"
else
  fail "appended phase entry fields wrong: name=$phase_name_check result=$phase_result_check duration=$phase_duration_check evidence_dir=$phase_evidence_dir_check"
fi

# --- Check 4a: finalize_run_report — all phases PASS + 0 rejected -> PASS
echo ""
echo "--- Check 4a: finalize_run_report (expect outcome=PASS) ---"
if finalize_output="$(finalize_run_report "$run_id" 2>&1)"; then
  pass "finalize_run_report succeeded: $finalize_output"
else
  fail "finalize_run_report failed: $finalize_output"
fi

echo "--- report.json after finalize_run_report (all-PASS scenario) ---"
cat "$report_path" 2>/dev/null || true

outcome_a="$(python3 -c "import json; print(json.load(open('$report_path'))['outcome'])")"
if [[ "$outcome_a" == "PASS" ]]; then
  pass "outcome computed as PASS when the sole phase is PASS and rejected_by_anti_bluff==0"
else
  fail "outcome is '$outcome_a', expected PASS"
fi

completed_at_a="$(python3 -c "import json; print(json.load(open('$report_path'))['completed_at'])")"
if [[ "$completed_at_a" != "1970-01-01T00:00:00Z" && -n "$completed_at_a" ]]; then
  pass "completed_at was overwritten from the epoch placeholder to a real timestamp ($completed_at_a)"
else
  fail "completed_at was not updated by finalize_run_report (still '$completed_at_a')"
fi

if _check_required_keys "$report_path" "$required_report_keys"; then
  pass "report.json still has exactly the schema's required keys after finalize (PASS scenario)"
else
  fail "report.json is missing/has extra keys after finalize (PASS scenario)"
fi

# --- Check 4b: finalize_run_report — one phase FAIL -> FAIL ------------
echo ""
echo "--- Check 4b: finalize_run_report (expect outcome=FAIL, second run) ---"
run_id_2="2026-08-21T15-00-00Z"
init_run_report "$run_id_2" "$commit_sha" >/dev/null

phase_dir_2=".lava-ci-evidence/pipeline-runs/${run_id_2}/phase-02"
append_phase_result "$run_id_2" "build" "PASS" "30" "$phase_dir_2" >/dev/null
append_phase_result "$run_id_2" "test" "FAIL" "45" "$phase_dir_2" >/dev/null

report_path_2="$(_run_report_path "$run_id_2")"
if finalize_output_2="$(finalize_run_report "$run_id_2" 2>&1)"; then
  pass "finalize_run_report (2nd run, mixed phases) succeeded: $finalize_output_2"
else
  fail "finalize_run_report (2nd run) failed: $finalize_output_2"
fi

echo "--- report.json after finalize_run_report (mixed PASS/FAIL scenario) ---"
cat "$report_path_2" 2>/dev/null || true

outcome_b="$(python3 -c "import json; print(json.load(open('$report_path_2'))['outcome'])")"
if [[ "$outcome_b" == "FAIL" ]]; then
  pass "outcome computed as FAIL when one phase is FAIL, even though another phase is PASS"
else
  fail "outcome is '$outcome_b', expected FAIL"
fi

# --- Check 4c: empty phases[] -> FAIL (never PASS on an empty run) -----
echo ""
echo "--- Check 4c: finalize_run_report on an empty run (expect outcome=FAIL) ---"
run_id_3="2026-08-21T16-00-00Z"
init_run_report "$run_id_3" "$commit_sha" >/dev/null
report_path_3="$(_run_report_path "$run_id_3")"
finalize_run_report "$run_id_3" >/dev/null
outcome_c="$(python3 -c "import json; print(json.load(open('$report_path_3'))['outcome'])")"
if [[ "$outcome_c" == "FAIL" ]]; then
  pass "outcome computed as FAIL for an empty phases[] (an empty run proved nothing)"
else
  fail "outcome is '$outcome_c' for an empty run, expected FAIL"
fi

# --- Check 4d: rejected_by_anti_bluff > 0 forces FAIL even if all phases PASS
echo ""
echo "--- Check 4d: rejected_by_anti_bluff > 0 forces FAIL despite all-PASS phases ---"
run_id_4="2026-08-21T17-00-00Z"
init_run_report "$run_id_4" "$commit_sha" >/dev/null
report_path_4="$(_run_report_path "$run_id_4")"
phase_dir_4=".lava-ci-evidence/pipeline-runs/${run_id_4}/phase-02"
append_phase_result "$run_id_4" "test" "PASS" "5" "$phase_dir_4" >/dev/null
# Simulate anti-bluff-validate.sh having rejected one record (out of scope
# of this feature's own component, but the Validation rule must still be
# honored here). Uses python3 (stdlib only) rather than jq so this harness
# step works identically whether or not jq is on PATH — this test suite is
# itself run once with jq available and once with it deliberately hidden,
# to prove BOTH code paths inside the library functions actually execute.
python3 -c "
import json
with open('$report_path_4') as f:
    report = json.load(f)
report['evidence_summary']['rejected_by_anti_bluff'] = 1
with open('$report_path_4', 'w') as f:
    json.dump(report, f, indent=2)
"
finalize_run_report "$run_id_4" >/dev/null
outcome_d="$(python3 -c "import json; print(json.load(open('$report_path_4'))['outcome'])")"
if [[ "$outcome_d" == "FAIL" ]]; then
  pass "outcome computed as FAIL when rejected_by_anti_bluff > 0, even though the only phase is PASS"
else
  fail "outcome is '$outcome_d', expected FAIL"
fi

# --- Check 4e: evidence_summary.skipped > 0 does NOT block outcome=PASS --
# Per data-model.md's Evidence Record design decision (the SKIPPED result
# value): a phase that contains one or more legitimately-skipped,
# anti-bluff-validated Evidence Records, with everything else PASS/
# validated (rejected_by_anti_bluff == 0), is still a genuine pipeline
# PASS — a documented, honestly-reported absence of coverage is not a
# pipeline failure. This proves finalize_run_report's PASS computation
# does not (accidentally or otherwise) start gating on `skipped`.
echo ""
echo "--- Check 4e: evidence_summary.skipped > 0 does not block outcome=PASS ---"
run_id_5="2026-08-21T18-00-00Z"
init_run_report "$run_id_5" "$commit_sha" >/dev/null
report_path_5="$(_run_report_path "$run_id_5")"
phase_dir_5=".lava-ci-evidence/pipeline-runs/${run_id_5}/phase-02"
append_phase_result "$run_id_5" "test" "PASS" "8" "$phase_dir_5" >/dev/null
# Simulate this phase having written one validated SKIPPED Evidence Record
# (e.g. a real `t.Skip("POSTGRES_TEST_URL not set")`) alongside otherwise
# all-PASS/validated tests: evidence_summary.skipped == 1,
# rejected_by_anti_bluff stays 0 (a validated SKIPPED record is never a
# rejection). Uses python3 (stdlib only), matching Check 4d's rationale
# for exercising both the jq and no-jq code paths of the library
# functions identically.
python3 -c "
import json
with open('$report_path_5') as f:
    report = json.load(f)
report['evidence_summary']['skipped'] = 1
report['evidence_summary']['total'] = 4
report['evidence_summary']['passed'] = 3
report['evidence_summary']['rejected_by_anti_bluff'] = 0
with open('$report_path_5', 'w') as f:
    json.dump(report, f, indent=2)
"
finalize_run_report "$run_id_5" >/dev/null
outcome_e="$(python3 -c "import json; print(json.load(open('$report_path_5'))['outcome'])")"
if [[ "$outcome_e" == "PASS" ]]; then
  pass "outcome computed as PASS when evidence_summary.skipped > 0 but the phase is PASS and rejected_by_anti_bluff == 0"
else
  fail "outcome is '$outcome_e', expected PASS (a legitimately-skipped, honestly-reported test must not block outcome)"
fi

skipped_field_e="$(python3 -c "import json; print(json.load(open('$report_path_5'))['evidence_summary']['skipped'])")"
if [[ "$skipped_field_e" == "1" ]]; then
  pass "evidence_summary.skipped survived finalize_run_report unchanged (1)"
else
  fail "evidence_summary.skipped is '$skipped_field_e' after finalize_run_report, expected 1"
fi

if _check_required_keys "$report_path_5" "$required_report_keys"; then
  pass "report.json still has exactly the schema's required top-level keys after finalize (skipped scenario)"
else
  fail "report.json is missing/has extra top-level keys after finalize (skipped scenario)"
fi

# --- Summary -------------------------------------------------------------
echo ""
echo "==============================================================="
if [[ "$FAILURES" -eq 0 ]]; then
  echo "ALL CHECKS PASSED"
  exit 0
else
  echo "$FAILURES CHECK(S) FAILED"
  exit 1
fi

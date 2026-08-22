#!/usr/bin/env bash
# Hermetic test suite for scripts/pipeline/lib/anti-bluff-validate.sh's
# handling of Evidence Records whose evidence is ABSENT rather than wrong.
#
# WHY THIS TEST EXISTS (forensic anchor, 2026-08-21, vacuous-pass hunt):
# validate_evidence_record is FR-004's independent gate — the component whose
# entire job is to decide whether a record's claimed PASS "genuinely reflects
# a real, falsifiable, evidenced outcome". Its own header says rule 0 rejects
# a record that "is not valid JSON OR IS MISSING REQUIRED FIELDS". At the
# time this test was written it did not do the second half, and the omission
# was self-reinforcing: every one of its four rules is satisfied VACUOUSLY by
# a record that carries no evidence at all.
#
#   rule 1 (generic assertion_summary)  an EMPTY summary contains none of the
#                                       forbidden bluff phrases -> passes
#   rule 2 (raw_output_ref exists)      an EMPTY ref resolves to
#                                       "${record_dir}/" — the record's own
#                                       directory — and [[ -e dir ]] is TRUE
#                                       -> passes
#   rule 3 (raw_output_ref non-empty)   [[ -s dir ]] is TRUE for a directory
#                                       (a directory's inode has a non-zero
#                                       size) -> passes
#   rule 4 (challenge falsifiability)   an EMPTY category is not
#                                       "real-device-challenge" -> skipped
#
# Verbatim proof of the vacuous pass, against the unfixed validator:
#
#   $ validate_evidence_record phase-02/kotlin-unit/EmptyFields.json
#   validated
#   exit=0
#
# ...for a record whose assertion_summary was "" and whose raw_output_ref was
# "". The record asserted nothing, pointed at nothing, and was stamped
# "validated" — the anti-bluff gate rubber-stamping the total absence of
# evidence. Same verdict for a record with those fields entirely ABSENT.
#
# CASE 3 is the one reachable WITHOUT hand-writing JSON: a wrapper that
# passes its raw DIRECTORY (e.g. "$phase_dir/$category/raw") instead of a
# captured-output FILE goes through the production write_evidence_record
# unchallenged (that writer deliberately does not inspect the path — see its
# header), and then the validator accepts it, because -e and -f were never
# distinguished. That is a real integration hole between two real components,
# not a hand-crafted straw record.
#
# CASE 3b covers a sibling of the same shape: rule 4 (the mandatory
# FALSIFIABILITY REHEARSAL marker for real-device challenges) is selected by
# an EXACT match on the category string, so an unrecognized category never
# reaches it. Demonstrated against a copy of the validator with only that
# new rule removed:
#
#   --- RED: category 'real-device-challenges' (trailing s typo), NO marker ---
#   validated
#   exit=0
#
# This suite is additive: tests/pipeline/test_anti_bluff_validate.sh already
# covers the validator's four rules against records that HAVE evidence. This
# one covers records that do not.
#
# Exit 0 if every case passes; non-zero otherwise.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ABV_LIB="${REPO_ROOT}/scripts/pipeline/lib/anti-bluff-validate.sh"
EVIDENCE_LIB="${REPO_ROOT}/scripts/pipeline/lib/evidence.sh"

for lib in "$ABV_LIB" "$EVIDENCE_LIB"; do
  if [[ ! -f "$lib" ]]; then
    echo "FAIL: library under test not found: $lib"
    exit 1
  fi
done
for tool in jq python3; do
  command -v "$tool" >/dev/null 2>&1 || { echo "FAIL: '$tool' required"; exit 1; }
done

FAILURES=0
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAILURES=$((FAILURES + 1)); }

WORKDIR="$(mktemp -d)"
trap 'rm -rf -- "$WORKDIR"' EXIT

# shellcheck source=scripts/pipeline/lib/anti-bluff-validate.sh
source "$ABV_LIB"
# shellcheck source=scripts/pipeline/lib/evidence.sh
source "$EVIDENCE_LIB"

cd "$WORKDIR"
PHASE_DIR="phase-02"
mkdir -p "${PHASE_DIR}/kotlin-unit/raw"

# _verdict <record-path> — runs the validator, sets VERDICT_RC and
# VERDICT_STATUS (the anti_bluff_status the validator left in the file).
_verdict() {
  set +e
  validate_evidence_record "$1" >/dev/null 2>&1
  VERDICT_RC=$?
  set -e
  VERDICT_STATUS="$(jq -r '.anti_bluff_status' "$1")"
}

echo "==============================================================="
echo "CASE 1 (LOAD-BEARING): required fields present but EMPTY"
echo "==============================================================="
echo "A record that asserts nothing and points at no captured output"
echo "must not be stamped 'validated'."
echo ""

REC_EMPTY="${PHASE_DIR}/kotlin-unit/EmptyFields.json"
cat > "$REC_EMPTY" <<'J'
{
  "test_id": "lava.core.EmptyFieldsTest",
  "category": "kotlin-unit",
  "command": "./gradlew :core:testDebugUnitTest",
  "result": "PASS",
  "assertion_summary": "",
  "raw_output_ref": "",
  "anti_bluff_status": "validated"
}
J
_verdict "$REC_EMPTY"
if [[ "$VERDICT_RC" -ne 0 ]]; then
  pass "empty assertion_summary + empty raw_output_ref -> rejected (exit ${VERDICT_RC})"
else
  fail "empty assertion_summary + empty raw_output_ref -> exit 0. The gate stamped a record that asserts nothing and points at nothing as evidence of success."
fi
if [[ "$VERDICT_STATUS" == REJECTED* ]]; then
  pass "empty-fields record: anti_bluff_status rewritten to '${VERDICT_STATUS}'"
else
  fail "empty-fields record: anti_bluff_status is '${VERDICT_STATUS}', expected a 'REJECTED: ...' string — recompute_evidence_summary counts REJECTED by that prefix, so without it the run report cannot see this rejection at all"
fi

echo ""
echo "==============================================================="
echo "CASE 2 (LOAD-BEARING): required fields entirely ABSENT"
echo "==============================================================="
echo "evidence-record.schema.json lists assertion_summary and"
echo "raw_output_ref among its 'required' properties. A record missing"
echo "them is not a valid Evidence Record."
echo ""

REC_MISSING="${PHASE_DIR}/kotlin-unit/MissingFields.json"
cat > "$REC_MISSING" <<'J'
{
  "test_id": "lava.core.MissingFieldsTest",
  "result": "PASS",
  "anti_bluff_status": "validated"
}
J
_verdict "$REC_MISSING"
if [[ "$VERDICT_RC" -ne 0 ]]; then
  pass "absent category/command/assertion_summary/raw_output_ref -> rejected (exit ${VERDICT_RC})"
else
  fail "absent required fields -> exit 0. The validator's own rule 0 claims to reject records 'missing required fields'; it only detected unparseable JSON, and jq's '// empty' turned every absent field into a benign empty string."
fi

echo ""
echo "==============================================================="
echo "CASE 3 (LOAD-BEARING): raw_output_ref points at a DIRECTORY"
echo "==============================================================="
echo "Reachable through the real production writer: a wrapper that"
echo "passes its raw DIRECTORY instead of a captured-output FILE."
echo "[[ -e dir ]] and [[ -s dir ]] are both TRUE, so rules 2 and 3 —"
echo "the two rules whose entire job is 'there is real captured output"
echo "behind this claim' — both pass on an empty directory."
echo ""

REC_DIR="$(write_evidence_record "$PHASE_DIR" "lava.core.DirRefTest" "kotlin-unit" \
  "./gradlew :core:testDebugUnitTest --tests lava.core.DirRefTest" "PASS" \
  "real JUnit XML testcase for lava.core.DirRefTest reports no <failure> element (executed in 0.02s)" \
  "${PHASE_DIR}/kotlin-unit/raw")"
ref_value="$(jq -r '.raw_output_ref' "$REC_DIR")"
resolved="${PHASE_DIR}/kotlin-unit/${ref_value}"
if [[ -d "$resolved" ]]; then
  pass "fixture sanity: raw_output_ref '${ref_value}' really does resolve to a directory"
else
  fail "fixture sanity: '${resolved}' is not a directory, so this case proves nothing"
fi
_verdict "$REC_DIR"
if [[ "$VERDICT_RC" -ne 0 ]]; then
  pass "raw_output_ref pointing at a directory -> rejected (exit ${VERDICT_RC})"
else
  fail "raw_output_ref pointing at an empty directory -> exit 0. Rules 2 and 3 were satisfied by the directory's own inode, not by any captured output."
fi

echo ""
echo "==============================================================="
echo "CASE 3b (LOAD-BEARING): an unrecognized category silently"
echo "skips rule 4"
echo "==============================================================="
echo "Rule 4 — the mandatory FALSIFIABILITY REHEARSAL marker for"
echo "real-device challenges — is selected by an exact match on the"
echo "string 'real-device-challenge'. A category that does not match"
echo "any known value does not fail; it just never reaches rule 4."
echo ""

mkdir -p "${PHASE_DIR}/real-device-challenges/raw"
printf 'C11 nav teardown ran on Pixel_8 API35, 1 test 0 failures\n' \
  > "${PHASE_DIR}/real-device-challenges/raw/c11.log"
REC_TYPO="${PHASE_DIR}/real-device-challenges/C11.json"
cat > "$REC_TYPO" <<'J'
{
  "test_id": "lava.app.challenges.Challenge11NavTeardownTest",
  "category": "real-device-challenges",
  "command": "./gradlew :app:connectedDebugAndroidTest --tests lava.app.challenges.Challenge11NavTeardownTest",
  "result": "PASS",
  "assertion_summary": "instrumentation reported 1 test, 0 failures on Pixel_8 API 35 in 12.4s",
  "raw_output_ref": "raw/c11.log",
  "anti_bluff_status": "validated"
}
J
_verdict "$REC_TYPO"
if [[ "$VERDICT_RC" -ne 0 ]]; then
  pass "unrecognized category -> rejected (exit ${VERDICT_RC})"
else
  fail "category 'real-device-challenges' (trailing-s typo) with NO falsifiability marker -> exit 0. Rule 4 never fired because the category string matched nothing, so a challenge record skipped the one check that exists specifically for challenge records."
fi

echo ""
echo "==============================================================="
echo "CASE 4: a genuine record with real captured output -> validated"
echo "(guards against a 'fix' that just rejects everything)"
echo "==============================================================="

raw_real="${PHASE_DIR}/kotlin-unit/raw/GenuineTest.log"
printf 'lava.core.GenuineTest > observe emits 3 rows PASSED\n1 test, 1 passed\n' > "$raw_real"
REC_GOOD="$(write_evidence_record "$PHASE_DIR" "lava.core.GenuineTest" "kotlin-unit" \
  "./gradlew :core:testDebugUnitTest --tests lava.core.GenuineTest" "PASS" \
  "real JUnit XML testcase for 'observe emits 3 rows' reports no <failure>/<error> element; 1 of 1 test executed in 0.02s" \
  "$raw_real")"
_verdict "$REC_GOOD"
if [[ "$VERDICT_RC" -eq 0 && "$VERDICT_STATUS" == "validated" ]]; then
  pass "genuine record with a real non-empty raw log -> validated"
else
  fail "genuine record -> exit ${VERDICT_RC}, status '${VERDICT_STATUS}', expected validated. The fix over-corrected and now rejects real evidence."
fi

echo ""
echo "==============================================================="
echo "CASE 5: a genuine SKIPPED record still validates"
echo "(SKIPPED is a first-class outcome, not a rejection trigger)"
echo "==============================================================="

raw_skip="${PHASE_DIR}/go-unit-integration/raw/SkippedTest.log"
mkdir -p "$(dirname "$raw_skip")"
printf -- '--- SKIP: TestPostgresRoundTrip (0.00s)\n    main_test.go:41: POSTGRES_TEST_URL not set\n' > "$raw_skip"
REC_SKIP="$(write_evidence_record "$PHASE_DIR" "TestPostgresRoundTrip" "go-unit-integration" \
  "go test ./internal/store/..." "SKIPPED" \
  "go test reported '--- SKIP: TestPostgresRoundTrip' with the test's own reason 'POSTGRES_TEST_URL not set' — an honestly-reported non-execution, quoted verbatim from the real run" \
  "$raw_skip")"
_verdict "$REC_SKIP"
if [[ "$VERDICT_RC" -eq 0 && "$VERDICT_STATUS" == "validated" ]]; then
  pass "genuine SKIPPED record with a real raw log -> validated"
else
  fail "genuine SKIPPED record -> exit ${VERDICT_RC}, status '${VERDICT_STATUS}', expected validated"
fi

echo ""
echo "==============================================================="
echo "CASE 6: an ABSOLUTE raw_output_ref to a real file still validates"
echo "(the absolute-path branch must not regress)"
echo "==============================================================="

raw_abs="${WORKDIR}/absolute-capture.log"
printf 'real captured output, reached by absolute path\n' > "$raw_abs"
REC_ABS="${PHASE_DIR}/kotlin-unit/AbsRefTest.json"
jq -n --arg raw "$raw_abs" '{
  test_id: "lava.core.AbsRefTest",
  category: "kotlin-unit",
  command: "./gradlew :core:testDebugUnitTest --tests lava.core.AbsRefTest",
  result: "PASS",
  assertion_summary: "real JUnit XML testcase for lava.core.AbsRefTest reports 4 of 4 assertions passing in 0.03s",
  raw_output_ref: $raw,
  anti_bluff_status: "validated"
}' > "$REC_ABS"
_verdict "$REC_ABS"
if [[ "$VERDICT_RC" -eq 0 && "$VERDICT_STATUS" == "validated" ]]; then
  pass "absolute raw_output_ref to a real non-empty file -> validated"
else
  fail "absolute raw_output_ref -> exit ${VERDICT_RC}, status '${VERDICT_STATUS}', expected validated"
fi

echo ""
echo "==============================================================="
if [[ "$FAILURES" -eq 0 ]]; then
  echo "ALL CHECKS PASSED"
  exit 0
else
  echo "$FAILURES CHECK(S) FAILED"
  exit 1
fi

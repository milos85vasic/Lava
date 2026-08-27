#!/usr/bin/env bash
# Asserts: an Evidence Record that no anti-bluff validator ever evaluated can
# NEVER reach "phase-02-test: PASSED".
#
# FORENSIC ANCHOR (2026-08-26). Two defects composed into a fail-open gate:
#
#   1. scripts/pipeline/lib/evidence.sh's write_evidence_record stamped every
#      record it wrote with the literal string "validated" — the exact value
#      the real validator writes on ACCEPT. "The independent validator
#      examined this record and accepted it" and "no validator ever looked at
#      this record" were therefore BYTE-IDENTICAL on disk.
#   2. scripts/pipeline/phase-02-test.sh read that field straight off disk and
#      counted a record rejected only when the string began with "REJECTED",
#      so an ABSENT or empty status compared equal to "not rejected".
#
# Measured end-to-end through the real phase-02-test.sh before the fix: a
# `real-device-challenge` record whose ENTIRE assertion_summary was
# "did not crash" produced
#
#     Evidence Records found: 1 / PASS: 1 / FAIL: 0 / REJECTED (anti-bluff): 0
#     phase-02-test: PASSED — ... 1 Evidence Records scanned, 0 FAIL, 0 REJECTED
#     === EXIT 0 ===
#
# while the real validator's verdict on that same record was
#
#     REJECTED: assertion_summary matches generic bluff pattern 'did not
#     crash' with no other specific content
#
# Per §6.Z clause 4 a cold-start survival check is the MINIMUM and explicitly
# not sufficient on its own, and §6.AK exists precisely because a C00-only
# gate green-lit a release whose claimed fixes were never exercised. A
# defaulted stamp is not a validation — it is the ABSENCE of one being read as
# its presence, which is the §6.J bluff class by construction. §6.AA clause 8
# condition (B) ("zero Evidence Records carry an anti_bluff_status other than
# validated") rests directly on this field.
set -u
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
PHASE02="${REPO_ROOT}/scripts/pipeline/phase-02-test.sh"
EVIDENCE_LIB="${REPO_ROOT}/scripts/pipeline/lib/evidence.sh"

FAILURES=0
EXAMINED=0
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAILURES=$((FAILURES + 1)); }

for f in "$PHASE02" "$EVIDENCE_LIB"; do
  [[ -f "$f" ]] || { echo "FAIL: not found: $f"; exit 1; }
done
for tool in jq git python3; do
  command -v "$tool" >/dev/null 2>&1 || { echo "FAIL: '$tool' required"; exit 1; }
done

WORKDIR="$(mktemp -d)"
trap 'rm -rf -- "$WORKDIR"' EXIT

# --- stub wrappers -----------------------------------------------------
# Each writes a record with the PRODUCTION writer and, like the historical
# fail-open path, never calls the validator itself.

cat > "${WORKDIR}/stub-bluff.sh" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
PHASE_DIR="$2"
source "${LAVA_EVIDENCE_LIB}"
raw="${PHASE_DIR}/real-device-challenge/raw"; mkdir -p -- "$raw"
printf 'Starting: lava.app.challenges.Challenge00CrashSurvivalTest\nOK\n' > "${raw}/c00.log"
write_evidence_record "$PHASE_DIR" \
  "lava.app.challenges.Challenge00CrashSurvivalTest#coldStart" \
  "real-device-challenge" "./gradlew :app:connectedDebugAndroidTest" "PASS" \
  "did not crash" "${raw}/c00.log" >/dev/null
exit 0
STUB

# A bluffing record that ALSO arrives with no anti_bluff_status field at all —
# a hand-rolled writer, a partial write, a validator that never ran. Absence
# of a verdict must not read as a favourable verdict.
cat > "${WORKDIR}/stub-status-stripped.sh" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
PHASE_DIR="$2"
source "${LAVA_EVIDENCE_LIB}"
raw="${PHASE_DIR}/kotlin-unit/raw"; mkdir -p -- "$raw"
printf 'app started\n' > "${raw}/s.log"
rec="$(write_evidence_record "$PHASE_DIR" "lava.core.StrippedTest" "kotlin-unit" \
  "./gradlew :core:test" "PASS" "did not crash" "${raw}/s.log")"
tmp="${rec}.tmp"
jq 'del(.anti_bluff_status)' "$rec" > "$tmp" && mv -f "$tmp" "$rec"
exit 0
STUB

# An honest record with no anti_bluff_status, in a directory the validator
# CANNOT write to — so no verdict can be produced for it at all. This is the
# state the aggregator must still refuse to call a pass: not "rejected", but
# "never evaluated". Verified 2026-08-26 that validate_evidence_record leaves
# the field absent in exactly this situation (its mktemp fails with
# "Permission denied" and it returns 1 without rewriting the record).
cat > "${WORKDIR}/stub-unevaluatable.sh" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
PHASE_DIR="$2"
source "${LAVA_EVIDENCE_LIB}"
raw="${PHASE_DIR}/kotlin-unit/raw"; mkdir -p -- "$raw"
printf 'real captured output\n7 of 7 assertions passed\n' > "${raw}/u.log"
rec="$(write_evidence_record "$PHASE_DIR" "lava.core.UnevaluatableTest" "kotlin-unit" \
  "./gradlew :core:test" "PASS" \
  "expected 7 rows with matching ids, observed 7 rows with matching ids" \
  "${raw}/u.log")"
tmp="${rec}.tmp"
jq 'del(.anti_bluff_status)' "$rec" > "$tmp" && mv -f "$tmp" "$rec"
chmod a-w "$(dirname "$rec")"
exit 0
STUB

cat > "${WORKDIR}/stub-honest.sh" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
PHASE_DIR="$2"
source "${LAVA_EVIDENCE_LIB}"
raw="${PHASE_DIR}/kotlin-unit/raw"; mkdir -p -- "$raw"
printf 'real captured output\n7 of 7 assertions passed\n' > "${raw}/h.log"
write_evidence_record "$PHASE_DIR" "lava.core.HonestTest" "kotlin-unit" \
  "./gradlew :core:test" "PASS" \
  "expected 7 rows with matching ids, observed 7 rows with matching ids" \
  "${raw}/h.log" >/dev/null
exit 0
STUB
chmod +x "${WORKDIR}"/stub-*.sh

_new_run() {
  local name="$1" run_id="$2"
  local dir="${WORKDIR}/${name}"
  git init -q -b master "$dir"
  git -C "$dir" config user.email "fixture@example.invalid"
  git -C "$dir" config user.name "Fixture"
  printf '.lava-ci-evidence/pipeline-runs/\n' > "${dir}/.gitignore"
  printf 'x\n' > "${dir}/f"
  git -C "$dir" add -A
  git -C "$dir" commit -qm init
  ( cd "$dir" && source "${REPO_ROOT}/scripts/pipeline/lib/run-report.sh" && \
    init_run_report "$run_id" "$(git -C "$dir" rev-parse HEAD)" >/dev/null )
  printf '%s' "$dir"
}

# _run_phase02 <dir> <run_id> <wrapper-var-name> <wrapper-path>
_run_phase02() {
  local dir="$1" run_id="$2" var="$3" wrapper="$4" out="${WORKDIR}/out.log"
  set +e
  (
    cd "$dir" && \
    LAVA_EVIDENCE_LIB="$EVIDENCE_LIB" \
    PHASE02_GO_WRAPPER=/nonexistent/go.sh \
    PHASE02_KOTLIN_WRAPPER=/nonexistent/kotlin.sh \
    PHASE02_HERMETIC_WRAPPER=/nonexistent/hermetic.sh \
    PHASE02_STRESS_CHAOS_WRAPPER=/nonexistent/stress.sh \
    PHASE02_RELEASE_CANARY_WRAPPER=/nonexistent/canary.sh \
    PHASE02_GATE_SWEEP_WRAPPER=/nonexistent/sweep.sh \
    PHASE02_CHALLENGE_WRAPPER=/nonexistent/challenge.sh \
    env "$var=$wrapper" \
      bash "$PHASE02" "$run_id" "$dir"
  ) >"$out" 2>&1
  P2_RC=$?
  set -e
  P2_OUT="$(cat "$out")"
}

echo "==============================================================="
echo "CASE 1: the writer's placeholder must not be the validator's accept value"
echo "==============================================================="
EXAMINED=$((EXAMINED + 1))
PROBE="${WORKDIR}/probe"; mkdir -p "${PROBE}/kotlin-unit/raw"
printf 'output\n' > "${PROBE}/kotlin-unit/raw/p.log"
(
  source "$EVIDENCE_LIB"
  write_evidence_record "$PROBE" "lava.core.ProbeTest" "kotlin-unit" \
    "./gradlew :core:test" "PASS" \
    "expected 2 rows with matching ids, observed 2 rows with matching ids" \
    "${PROBE}/kotlin-unit/raw/p.log" >/dev/null
)
PROBE_REC="$(find "${PROBE}/kotlin-unit" -maxdepth 1 -name '*.json' | head -1)"
PROBE_STATUS="$(jq -r '.anti_bluff_status' "$PROBE_REC")"
if [[ "$PROBE_STATUS" == "validated" ]]; then
  fail "write_evidence_record stamps records with 'validated' — the SAME value the independent validator writes on accept. A record nobody examined is then indistinguishable on disk from one that was examined and accepted, and every consumer reads this field as a verdict."
else
  pass "the writer's placeholder is not 'validated' (found: '${PROBE_STATUS}')"
fi
EXAMINED=$((EXAMINED + 1))
if [[ "$PROBE_STATUS" =~ ^(validated|REJECTED:\ .+)$ ]]; then
  pass "the writer's placeholder still satisfies evidence-record.schema.json's ^(validated|REJECTED: .+)\$ pattern"
else
  fail "the writer's placeholder '${PROBE_STATUS}' violates evidence-record.schema.json's anti_bluff_status pattern"
fi

echo ""
echo "==============================================================="
echo "CASE 2 (LOAD-BEARING): a 'did not crash' device record must not PASS"
echo "==============================================================="
echo "§6.Z clause 4: cold-start survival is the MINIMUM, explicitly not"
echo "sufficient on its own. §6.AK exists because a C00-only gate shipped."
echo ""
EXAMINED=$((EXAMINED + 1))
RUN_A="2026-08-26T10-00-00Z"
DIR_A="$(_new_run bluff "$RUN_A")"
_run_phase02 "$DIR_A" "$RUN_A" PHASE02_CHALLENGE_WRAPPER "${WORKDIR}/stub-bluff.sh"
if grep -q "dispatching 'real-device-challenge'" <<< "$P2_OUT"; then
  pass "fixture sanity: the bluffing wrapper really was dispatched"
else
  fail "fixture sanity: the wrapper was never dispatched, so this case proves nothing. Output: ${P2_OUT}"
fi
EXAMINED=$((EXAMINED + 1))
if [[ "$P2_RC" -ne 0 ]]; then
  pass "a real-device-challenge record asserting only 'did not crash' -> phase FAILS (exit ${P2_RC})"
else
  fail "a real-device-challenge record whose entire assertion was 'did not crash', which no validator ever examined, reached phase-02-test: PASSED. Output: ${P2_OUT}"
fi
EXAMINED=$((EXAMINED + 1))
if grep -qE 'REJECTED \(anti-bluff\): +[1-9]' <<< "$P2_OUT"; then
  pass "the phase reported the record as REJECTED rather than clean"
else
  fail "the phase did not report any REJECTED record; the summary claims a clean run. Output: ${P2_OUT}"
fi

echo ""
echo "==============================================================="
echo "CASE 3a: a bluff carrying NO anti_bluff_status at all must not PASS"
echo "==============================================================="
EXAMINED=$((EXAMINED + 1))
RUN_B="2026-08-26T11-00-00Z"
DIR_B="$(_new_run stripped "$RUN_B")"
_run_phase02 "$DIR_B" "$RUN_B" PHASE02_KOTLIN_WRAPPER "${WORKDIR}/stub-status-stripped.sh"
if [[ "$P2_RC" -ne 0 ]]; then
  pass "a bluffing record with an absent anti_bluff_status -> phase FAILS (exit ${P2_RC})"
else
  fail "a bluffing record carrying no anti_bluff_status at all counted as clean and the phase PASSED. An absent verdict is not a favourable verdict; it is the absence of one. Output: ${P2_OUT}"
fi

echo ""
echo "==============================================================="
echo "CASE 3b: a record for which NO verdict can be produced must not PASS"
echo "==============================================================="
echo "This is the distinction the aggregator has to be able to draw:"
echo "'a validator examined this and accepted it' vs 'nothing ever"
echo "examined this'. Here the record's own content is honest, so the"
echo "ONLY thing that can fail this case is the missing verdict."
echo ""
EXAMINED=$((EXAMINED + 1))
RUN_D="2026-08-26T13-00-00Z"
DIR_D="$(_new_run unevaluatable "$RUN_D")"
_run_phase02 "$DIR_D" "$RUN_D" PHASE02_KOTLIN_WRAPPER "${WORKDIR}/stub-unevaluatable.sh"
chmod -R u+w "${DIR_D}/.lava-ci-evidence" 2>/dev/null || true
if [[ "$P2_RC" -ne 0 ]]; then
  pass "a record no validator could evaluate -> phase FAILS (exit ${P2_RC})"
else
  fail "a record that no anti-bluff validator was able to evaluate at all reached phase-02-test: PASSED. Treating the absence of a validation as its presence is the exact bluff class this gate exists to refuse. Output: ${P2_OUT}"
fi
EXAMINED=$((EXAMINED + 1))
if grep -qE 'UNEVALUATED \(anti-bluff\): +[1-9]' <<< "$P2_OUT"; then
  pass "the phase reports it as UNEVALUATED, distinct from both 'validated' and 'REJECTED'"
else
  fail "the phase did not report an UNEVALUATED record, so it cannot distinguish 'examined and accepted' from 'never examined'. Output: ${P2_OUT}"
fi

echo ""
echo "==============================================================="
echo "CASE 4 (control): an honest record must still PASS"
echo "==============================================================="
EXAMINED=$((EXAMINED + 1))
RUN_C="2026-08-26T12-00-00Z"
DIR_C="$(_new_run honest "$RUN_C")"
_run_phase02 "$DIR_C" "$RUN_C" PHASE02_KOTLIN_WRAPPER "${WORKDIR}/stub-honest.sh"
if [[ "$P2_RC" -eq 0 ]]; then
  pass "a specific, evidenced record is accepted (the fix did not just fail everything)"
else
  fail "an honest, specific, evidenced record was refused — the fix over-reached. Output: ${P2_OUT}"
fi
EXAMINED=$((EXAMINED + 1))
if grep -qE 'independently re-validated: +[1-9]' <<< "$P2_OUT"; then
  pass "the phase reports how many records it independently re-validated (a verdict that provably ran)"
else
  fail "the phase does not report an independent re-validation count, so 'validated' on disk is still being taken on trust. Output: ${P2_OUT}"
fi

echo ""
echo "==============================================================="
if [[ "$EXAMINED" -eq 0 ]]; then
  echo "FAIL: this suite examined 0 records and therefore proves nothing"
  exit 1
fi
echo "examined ${EXAMINED} case(s)"
if [[ "$FAILURES" -eq 0 ]]; then
  echo "ALL CHECKS PASSED"
  exit 0
fi
echo "$FAILURES CHECK(S) FAILED"
exit 1

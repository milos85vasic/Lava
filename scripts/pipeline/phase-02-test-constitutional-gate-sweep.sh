#!/usr/bin/env bash
# phase-02-test-constitutional-gate-sweep.sh — Phase 02 test-category
# wrapper: constitutional-gate-sweep (T028 of
# specs/002-build-test-distribute-pipeline/tasks.md).
#
# ---------------------------------------------------------------------------
# Why scripts/verify-all-constitution-rules.sh --json-only, NOT scripts/ci.sh
# ---------------------------------------------------------------------------
# scripts/ci.sh (both --changed-only and --full) invokes Gradle heavily
# (spotlessCheck, unit tests, detekt, and in --full mode connectedDebug-
# AndroidTest). scripts/verify-all-constitution-rules.sh is itself just ONE
# step INSIDE scripts/ci.sh (confirmed by reading scripts/ci.sh: line ~168
# runs "./scripts/verify-all-constitution-rules.sh --json-only > /dev/null"
# as its own §11.4.32 gate) — it is pure bash + git + grep, invokes ZERO
# Gradle, and IS the actual "constitutional gate sweep" this category's name
# refers to (see the extensive forensic history in root CLAUDE.md's §6.L
# narrative referencing "verify-all sweep at N/N PASS in fully STRICT mode" —
# that IS this exact script). Invoking scripts/ci.sh here would be both less
# precise (it would re-run this exact sweep as a buried substep of a much
# heavier Gradle-invoking process) AND would risk a real resource conflict
# with another concurrently-running agent doing real Gradle+emulator work on
# this same host. So this wrapper invokes
# scripts/verify-all-constitution-rules.sh --json-only directly.
#
# ---------------------------------------------------------------------------
# What "one Evidence Record per gate" means here, and where raw_output_ref
# comes from (read scripts/verify-all-constitution-rules.sh in full before
# writing this wrapper — this is what it actually does, not assumed)
# ---------------------------------------------------------------------------
# scripts/verify-all-constitution-rules.sh's run_gate() function ALWAYS does
# `eval "$cmd" >/dev/null 2>&1` — this discards every individual gate
# command's own stdout/stderr UNCONDITIONALLY, regardless of --json-only.
# --json-only only toggles the human-readable progress lines ("==> gate-name
# (ref)" / "    ✓ PASS (Ns)"), not the underlying gate command's captured
# output — there is no deeper per-gate console log available from ANY
# invocation of this script, --json-only or not. What the sweep DOES always
# produce, regardless of mode, is a real, structured, mechanically-parseable
# per-gate JSON attestation at
# .lava-ci-evidence/verify-all/<UTC-timestamp>.json:
#   {sweep_timestamp, sweep_mode, sweep_constitution_pin, total_gates,
#    pass_count, fail_count, all_passed,
#    gates: [{name, rule_ref, result, duration_seconds}, ...]}
# (verified directly by reading the script's own JSON-construction block,
# not assumed from CLAUDE.md's description).
#
# So the real, honest evidence available per gate from this ONE real
# invocation is: (a) that gate's own JSON object, extracted VERBATIM from
# the sweep's own real attestation file this exact invocation produced, plus
# (b) the exact underlying command that gate runs, recovered from the
# sweep script's own real source (see "Command recovery" below) — NOT a
# fabricated generic phrase, and NOT a copy of some OTHER script's captured
# output. This wrapper writes each gate's own JSON object (plus sweep-level
# context fields) to its own per-gate raw log file under
# <phase_dir>/constitutional-gate-sweep/raw/<gate>.json and points that
# gate's Evidence Record's raw_output_ref at it.
#
# This is intentionally NOT a duplicate of phase-02-test-hermetic.sh's
# hermetic-script category, even though some of the same underlying scripts
# (the check-constitution/pre-push/tests/*/run_all.sh hermetic suites) are
# exercised by both: phase-02-test-hermetic.sh captures each suite's OWN full
# stdout/stderr by invoking it standalone, and its Evidence Records attest
# "this specific suite, run in isolation, produced this specific output".
# THIS wrapper attests something different and non-redundant: "the SWEEP
# ITSELF — the actual gate ci.sh's pre-push/pre-tag flow relies on — ran all
# ~40 gates together in one invocation and reported this specific per-gate
# verdict for THIS gate, at THIS timestamp, against THIS constitution pin".
# scripts/verify-all-constitution-rules.sh's own header comment explicitly
# calls this kind of overlap "redundancy is intentional — multiple
# enforcement points per §11.4.32 design"; this wrapper follows that same
# design intent rather than avoiding it.
#
# ---------------------------------------------------------------------------
# Command recovery ("command" field, for FR-003 independent re-run)
# ---------------------------------------------------------------------------
# scripts/verify-all-constitution-rules.sh has no "--gate=<name>" flag — it
# cannot re-run a single named gate. Inventing a synthetic single-gate
# command that doesn't actually exist in the tool would itself be a
# fabrication. Instead, this wrapper recovers each gate's REAL underlying
# command in one of two ways, both derived from the sweep script's own real,
# checked-in source (never guessed):
#   1. Static gates (the ~19 explicit `run_gate "name" "ref" \` + `"cmd"`
#      call sites): extracted via a regex over the sweep script's own source
#      text.
#   2. Dynamically-generated gates (the three `for`-loops at the bottom of
#      the sweep script: hermetic-suite-<X>, hermetic-pre-push-<bn>,
#      hermetic-check-constitution-<bn>): reconstructed by mirroring the
#      EXACT SAME glob/loop logic the sweep script itself runs (same
#      directory, same glob patterns, same basename-.sh-stripping, same
#      test_verify_all_rules.sh self-recursion skip) against the live
#      filesystem at this wrapper's own invocation time.
# Running that recovered command directly (`cd <repo> && <cmd>`) reproduces
# exactly what that gate's own `eval` inside the sweep evaluated — this is a
# real, precise, independently-rerunnable command, not a placeholder. If a
# future gate is added to the sweep in a shape this wrapper's recovery logic
# doesn't recognize, the wrapper degrades honestly: the recorded "command"
# falls back to re-running the whole sweep with a comment naming which gate
# entry to inspect, rather than fabricating a nonexistent single-gate
# invocation.
#
# ---------------------------------------------------------------------------
# Result derivation — PASS / FAIL / SKIPPED (never fabricated)
# ---------------------------------------------------------------------------
# scripts/verify-all-constitution-rules.sh's run_gate() only ever writes
# "PASS" or "FAIL" per gate (based on the gate command's real exit code) —
# there is no "advisory-only" / "waived" / "not applicable" distinction in
# its real per-gate output (MODE only affects the SWEEP's own overall exit
# code, never an individual gate's recorded result). So per-gate mapping is
# direct: PASS -> PASS, FAIL -> FAIL — no forcing needed because there is
# nothing else to force. The one place SKIPPED legitimately applies here is
# defensive: if a gate's own JSON object ever reports a `result` value that
# is neither "PASS" nor "FAIL" (e.g. a future sweep version adds a new
# state, or the field is missing/null due to some parsing anomaly), this
# wrapper does NOT fabricate that into a PASS (a false claim of verification)
# or force it into a FAIL (misrepresenting an ungraded/anomalous entry as a
# real defect) — it is recorded as SKIPPED, with the real, verbatim anomalous
# JSON quoted in assertion_summary, per contracts/evidence-record.schema.json
# and data-model.md's Evidence Record section ("SKIPPED covers a test that
# legitimately, honestly did not execute").
#
# A separate CRITICAL bug class (recorded by a prior agent in this same
# session, and fixed on 4 of this wrapper's 6 sibling wrappers): forcing
# every legitimate non-execution into a fabricated FAIL, which would make
# the whole pipeline refuse to distribute even on a fully passing run. This
# wrapper never does that — see the SKIPPED handling above.
#
# ---------------------------------------------------------------------------
# Anti-bluff: proving THIS invocation produced THIS attestation
# ---------------------------------------------------------------------------
# scripts/verify-all-constitution-rules.sh names its own attestation file
# `date -u +%Y-%m-%dT%H-%M-%SZ).json` (second-granularity) computed once at
# its own top, so this wrapper snapshots .lava-ci-evidence/verify-all/*.json
# BEFORE invoking the sweep and diffs against an AFTER snapshot (the same
# `comm -13 BEFORE AFTER` pattern phase-02-test-stress-chaos.sh already uses
# for its own new-evidence-directory detection) — never "most recent by
# mtime" alone, which could silently replay a stale prior run's attestation.
#
# ---------------------------------------------------------------------------
# Usage
# ---------------------------------------------------------------------------
#   scripts/pipeline/phase-02-test-constitutional-gate-sweep.sh \
#     [repo-path] [phase-dir]
#
# repo-path — defaults to `git rev-parse --show-toplevel` (matches every
#             other phase-NN-*.sh script's existing convention).
# phase-dir — defaults to a freshly-created
#             "<repo-path>/.lava-ci-evidence/pipeline-runs/<UTC-run-id>/phase-02"
#             so this script is independently runnable/verifiable on its own.
#
# Exit codes:
#   0 - every gate is PASS or SKIPPED (no genuine FAIL) AND every Evidence
#       Record was anti-bluff-validated.
#   1 - at least one gate genuinely FAILed, or at least one Evidence Record
#       was REJECTED by anti-bluff validation.
#   2 - usage/precondition error (repo path, sweep script, jq, or python3
#       missing; OR the sweep invocation produced no new, parseable
#       attestation file at all — a real toolchain-level failure this
#       wrapper cannot honestly turn into per-gate Evidence Records).
#
# This script does NOT invoke Gradle, Docker, or Podman anywhere in its own
# logic, and does not shell out to scripts/ci.sh.

set -uo pipefail
# Deliberately NOT `set -e`: this wrapper's whole job is to keep going after
# the sweep's own non-zero exit (that non-zero exit IS the real, wanted
# signal driving several gates' FAIL records) — every risky command below is
# explicitly guarded (`if`, direct `$?` capture), never relying on inherited
# errexit to stop the script.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/pipeline/lib/evidence.sh
source "${SCRIPT_DIR}/lib/evidence.sh"
# shellcheck source=scripts/pipeline/lib/anti-bluff-validate.sh
source "${SCRIPT_DIR}/lib/anti-bluff-validate.sh"

REPO_PATH="${1:-}"
PHASE_DIR="${2:-}"

if [[ -z "$REPO_PATH" ]]; then
  REPO_PATH="$(git rev-parse --show-toplevel)"
fi

SWEEP_SCRIPT="${REPO_PATH}/scripts/verify-all-constitution-rules.sh"

if [[ ! -f "$SWEEP_SCRIPT" ]]; then
  echo "phase-02-test-constitutional-gate-sweep: precondition failed — ${SWEEP_SCRIPT} not found" >&2
  exit 2
fi

for tool in jq python3; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "phase-02-test-constitutional-gate-sweep: precondition failed — required tool '$tool' not found on PATH" >&2
    exit 2
  fi
done

if [[ -z "$PHASE_DIR" ]]; then
  RUN_ID="$(date -u +%Y-%m-%dT%H-%M-%SZ)"
  PHASE_DIR="${REPO_PATH}/.lava-ci-evidence/pipeline-runs/${RUN_ID}/phase-02"
fi

mkdir -p "$PHASE_DIR"
RAW_DIR="${PHASE_DIR}/constitutional-gate-sweep/raw"
mkdir -p "$RAW_DIR"

EVIDENCE_ROOT="${REPO_PATH}/.lava-ci-evidence/verify-all"
mkdir -p "$EVIDENCE_ROOT"

echo "phase-02-test-constitutional-gate-sweep: repo=${REPO_PATH}"
echo "phase-02-test-constitutional-gate-sweep: phase_dir=${PHASE_DIR}"

# --- Snapshot BEFORE invoking, so we can prove the NEW attestation is real -
BEFORE_SNAPSHOT="$(mktemp)"
find "$EVIDENCE_ROOT" -mindepth 1 -maxdepth 1 -name '*.json' 2>/dev/null | sort > "$BEFORE_SNAPSHOT"

TOP_LEVEL_LOG="${RAW_DIR}/sweep-invocation.log"
echo "phase-02-test-constitutional-gate-sweep: RUN bash scripts/verify-all-constitution-rules.sh --json-only"

( cd "$REPO_PATH" && bash "$SWEEP_SCRIPT" --json-only ) > "$TOP_LEVEL_LOG" 2>&1
SWEEP_RC=$?  # vacuous-pass-ok: the verdict comes from the sweep's real per-gate JSON attestation, not this rc; the rc is quoted in the no-attestation precondition error.

echo "phase-02-test-constitutional-gate-sweep: sweep invocation exit code = ${SWEEP_RC}"

AFTER_SNAPSHOT="$(mktemp)"
find "$EVIDENCE_ROOT" -mindepth 1 -maxdepth 1 -name '*.json' 2>/dev/null | sort > "$AFTER_SNAPSHOT"
NEW_FILES="$(comm -13 "$BEFORE_SNAPSHOT" "$AFTER_SNAPSHOT")"
rm -f "$BEFORE_SNAPSHOT" "$AFTER_SNAPSHOT"

ATTESTATION_FILE=""
if [[ -n "$NEW_FILES" ]]; then
  # Newest by mtime among the genuinely-new files (guards against another
  # concurrent agent's own sweep invocation landing a file in the same
  # second window; this invocation's own file is the one we just produced).
  ATTESTATION_FILE="$(printf '%s\n' "$NEW_FILES" | xargs -I{} stat -c '%Y %n' {} 2>/dev/null | sort -n | tail -1 | cut -d' ' -f2-)"
fi

if [[ -z "$ATTESTATION_FILE" || ! -f "$ATTESTATION_FILE" ]]; then
  echo "phase-02-test-constitutional-gate-sweep: precondition failed — the sweep invocation produced no new, discoverable attestation file under ${EVIDENCE_ROOT}/ (exit was ${SWEEP_RC}); real captured stdout/stderr:" >&2
  cat "$TOP_LEVEL_LOG" >&2
  exit 2
fi

echo "phase-02-test-constitutional-gate-sweep: real new attestation -> ${ATTESTATION_FILE#$REPO_PATH/}"

if ! jq empty "$ATTESTATION_FILE" >/dev/null 2>&1; then
  echo "phase-02-test-constitutional-gate-sweep: precondition failed — ${ATTESTATION_FILE} is not valid JSON" >&2
  exit 2
fi

TOTAL_GATES="$(jq -r '.gates | length' "$ATTESTATION_FILE")"
if [[ -z "$TOTAL_GATES" || "$TOTAL_GATES" -eq 0 ]]; then
  echo "phase-02-test-constitutional-gate-sweep: precondition failed — ${ATTESTATION_FILE} contains zero gates (a genuinely broken invocation, not a real zero-coverage signal)" >&2
  exit 2
fi

SWEEP_TS="$(jq -r '.sweep_timestamp // "unknown"' "$ATTESTATION_FILE")"
SWEEP_MODE="$(jq -r '.sweep_mode // "unknown"' "$ATTESTATION_FILE")"
SWEEP_PIN="$(jq -r '.sweep_constitution_pin // "unknown"' "$ATTESTATION_FILE")"
SWEEP_TOTAL="$(jq -r '.total_gates // "unknown"' "$ATTESTATION_FILE")"
SWEEP_PASS="$(jq -r '.pass_count // "unknown"' "$ATTESTATION_FILE")"
SWEEP_FAIL="$(jq -r '.fail_count // "unknown"' "$ATTESTATION_FILE")"
# NOTE: deliberately NOT `jq -r '.all_passed // "unknown"'`. jq's `//`
# alternative operator falls back not only on null/missing but ALSO on the
# boolean `false` -- so `false // "unknown"` yields "unknown". That would
# silently rewrite the sweep's real "this sweep did NOT all-pass" signal into
# an ambiguous "unknown" on exactly the runs where it matters most (verified
# against a real attestation: the file said `"all_passed": false` while the
# recorded evidence said "unknown"). `has()` distinguishes a genuinely absent
# field from a present-and-false one; `tostring` preserves true/false verbatim.
SWEEP_ALL_PASSED="$(jq -r 'if has("all_passed") and .all_passed != null then (.all_passed | tostring) else "unknown" end' "$ATTESTATION_FILE")"

echo "phase-02-test-constitutional-gate-sweep: ${TOTAL_GATES} gate(s) in attestation (sweep_mode=${SWEEP_MODE}, pass=${SWEEP_PASS}, fail=${SWEEP_FAIL})"

# --- Command recovery ---------------------------------------------------
# 1. Static `run_gate "name" "ref" \` + `"cmd"` call sites, via regex over
#    the sweep script's own real source text.
declare -A GATE_CMD=()

STATIC_CMDS_JSON="$(python3 - "$SWEEP_SCRIPT" <<'PYEOF'
import json
import re
import sys

path = sys.argv[1]
text = open(path, encoding='utf-8').read()
pattern = re.compile(r'run_gate\s+"([^"]+)"\s+"[^"]*"\s*\\\s*\n\s*"([^"]*)"')
result = {}
for m in pattern.finditer(text):
    name, cmd = m.group(1), m.group(2)
    result[name] = cmd
print(json.dumps(result))
PYEOF
)"

if [[ -n "$STATIC_CMDS_JSON" ]]; then
  while IFS=$'\t' read -r k v; do
    [[ -z "$k" ]] && continue
    GATE_CMD["$k"]="$v"
  done < <(jq -r 'to_entries[] | [.key, .value] | @tsv' <<<"$STATIC_CMDS_JSON" 2>/dev/null)
fi

# 2. Dynamically-generated gates — mirror the EXACT SAME loops
#    scripts/verify-all-constitution-rules.sh itself runs, against the live
#    filesystem, at this wrapper's own invocation time.
for suite in tests/firebase tests/ci-sh tests/compose-layout tests/tag-helper \
             tests/vm-images tests/vm-signing tests/vm-distro; do
  if [[ -x "${REPO_PATH}/${suite}/run_all.sh" ]]; then
    GATE_CMD["hermetic-suite-$(basename "$suite")"]="bash ${suite}/run_all.sh"
  fi
done

for t in "${REPO_PATH}"/tests/pre-push/check*_test.sh; do
  [[ -f "$t" ]] || continue
  bn="$(basename "$t" .sh)"
  GATE_CMD["hermetic-pre-push-${bn}"]="bash tests/pre-push/${bn}.sh"
done

for t in "${REPO_PATH}"/tests/check-constitution/test_*.sh "${REPO_PATH}"/tests/check-constitution/check_constitution_test.sh; do
  [[ -f "$t" ]] || continue
  bn="$(basename "$t" .sh)"
  [[ "$bn" == "test_verify_all_rules" ]] && continue
  GATE_CMD["hermetic-check-constitution-${bn}"]="bash tests/check-constitution/${bn}.sh"
done

# --- Per-gate Evidence Record emission -----------------------------------
PASS_COUNT=0
FAIL_COUNT=0
SKIPPED_COUNT=0
VALIDATED_COUNT=0
REJECTED_COUNT=0
declare -a FAILED_GATES=()
declare -a REJECTED_RECORDS=()

while IFS= read -r gate_json; do
  name="$(jq -r '.name' <<<"$gate_json")"
  rule_ref="$(jq -r '.rule_ref' <<<"$gate_json")"
  raw_result="$(jq -r '.result' <<<"$gate_json")"
  duration="$(jq -r '.duration_seconds' <<<"$gate_json")"

  resolved_cmd="${GATE_CMD[$name]:-}"
  if [[ -n "$resolved_cmd" ]]; then
    command_str="cd '${REPO_PATH}' && ${resolved_cmd}"
  else
    command_str="cd '${REPO_PATH}' && bash scripts/verify-all-constitution-rules.sh --json-only  # gate '${name}' has no statically/dynamically recovered single-gate command; inspect the '${name}' entry in .lava-ci-evidence/verify-all/${SWEEP_TS}.json's 'gates' array"
  fi

  test_id="constitutional-gate-sweep:${name}"
  sanitized_name="$(printf '%s' "$name" | tr -c 'A-Za-z0-9._-' '_')"
  raw_log="${RAW_DIR}/${sanitized_name}.json"

  jq -n \
    --argjson gate "$gate_json" \
    --arg sweep_timestamp "$SWEEP_TS" \
    --arg sweep_mode "$SWEEP_MODE" \
    --arg sweep_constitution_pin "$SWEEP_PIN" \
    --arg total_gates "$SWEEP_TOTAL" \
    --arg pass_count "$SWEEP_PASS" \
    --arg fail_count "$SWEEP_FAIL" \
    --arg all_passed "$SWEEP_ALL_PASSED" \
    --arg resolved_command "$resolved_cmd" \
    --arg attestation_file "${ATTESTATION_FILE#$REPO_PATH/}" \
    '{
      gate: $gate,
      sweep: {
        sweep_timestamp: $sweep_timestamp,
        sweep_mode: $sweep_mode,
        sweep_constitution_pin: $sweep_constitution_pin,
        total_gates: $total_gates,
        pass_count: $pass_count,
        fail_count: $fail_count,
        all_passed: $all_passed
      },
      resolved_command: $resolved_command,
      attestation_file: $attestation_file,
      note: "scripts/verify-all-constitution-rules.sh own run_gate() function redirects this gate own underlying command own stdout/stderr to /dev/null internally (unconditionally, regardless of --json-only) -- this JSON object is extracted verbatim from the real attestation file this exact invocation produced. This is the full real per-gate evidence available from this invocation; run resolved_command directly (when non-empty) for this gate own deeper stdout/stderr."
    }' > "$raw_log"

  case "$raw_result" in
    PASS)
      result="PASS"
      PASS_COUNT=$((PASS_COUNT + 1))
      assertion_summary="verify-all-constitution-rules.sh --json-only (real invocation) reported result=PASS for gate '${name}' (${rule_ref}) in ${duration}s [sweep_timestamp=${SWEEP_TS}, sweep_mode=${SWEEP_MODE}, constitution_pin=${SWEEP_PIN}, ${SWEEP_PASS}/${SWEEP_TOTAL} gates passed overall]"
      ;;
    FAIL)
      result="FAIL"
      FAIL_COUNT=$((FAIL_COUNT + 1))
      FAILED_GATES+=("${name} (${rule_ref})")
      assertion_summary="verify-all-constitution-rules.sh --json-only (real invocation) reported result=FAIL for gate '${name}' (${rule_ref}) in ${duration}s [sweep_timestamp=${SWEEP_TS}, sweep_mode=${SWEEP_MODE}, constitution_pin=${SWEEP_PIN}] -- this gate own underlying command exited non-zero; its own stdout/stderr is discarded internally by run_gate()'s '>/dev/null 2>&1', so re-run to inspect: ${resolved_cmd:-<no recovered command; see raw_output_ref>}"
      ;;
    *)
      result="SKIPPED"
      SKIPPED_COUNT=$((SKIPPED_COUNT + 1))
      assertion_summary="Genuinely did not execute as a normal PASS/FAIL check: gate '${name}' (${rule_ref}) reported an unrecognized/non-standard result field ('${raw_result}') in the sweep own real attestation [sweep_timestamp=${SWEEP_TS}] -- scripts/verify-all-constitution-rules.sh's run_gate() only ever emits PASS or FAIL per gate today, so this anomalous value is honestly reported as SKIPPED rather than fabricated into PASS or misrepresented as a real defect FAIL"
      ;;
  esac

  record_path=""
  if ! record_path="$(write_evidence_record "$PHASE_DIR" "$test_id" "constitutional-gate-sweep" "$command_str" "$result" "$assertion_summary" "$raw_log")"; then
    echo "  ERROR: write_evidence_record failed for ${name}" >&2
    REJECTED_COUNT=$((REJECTED_COUNT + 1))
    REJECTED_RECORDS+=("${name} (evidence-write failure)")
    continue
  fi

  if validate_evidence_record "$record_path" >/dev/null 2>&1; then
    VALIDATED_COUNT=$((VALIDATED_COUNT + 1))
    echo "  -> gate=${name} result=${result} anti_bluff=validated record=${record_path#$REPO_PATH/}"
  else
    REJECTED_COUNT=$((REJECTED_COUNT + 1))
    REJECTED_RECORDS+=("${name} ($(jq -r '.anti_bluff_status' "$record_path" 2>/dev/null || echo REJECTED))")
    echo "  -> gate=${name} result=${result} anti_bluff=REJECTED record=${record_path#$REPO_PATH/}"
  fi
done < <(jq -c '.gates[]' "$ATTESTATION_FILE")

# --- Summary --------------------------------------------------------------
echo ""
echo "phase-02-test-constitutional-gate-sweep: SUMMARY"
echo "  gates total:     ${TOTAL_GATES}"
echo "  PASS:            ${PASS_COUNT}"
echo "  FAIL:            ${FAIL_COUNT}"
echo "  SKIPPED:         ${SKIPPED_COUNT}"
echo "  anti_bluff validated: ${VALIDATED_COUNT}"
echo "  anti_bluff REJECTED:  ${REJECTED_COUNT}"

if [[ ${#FAILED_GATES[@]} -gt 0 ]]; then
  echo "  Failed gates:"
  for g in "${FAILED_GATES[@]}"; do
    echo "    - ${g}"
  done
fi

if [[ ${#REJECTED_RECORDS[@]} -gt 0 ]]; then
  echo "  Rejected Evidence Records:"
  for r in "${REJECTED_RECORDS[@]}"; do
    echo "    - ${r}"
  done
fi

echo ""
echo "phase-02-test-constitutional-gate-sweep: Evidence Records under ${PHASE_DIR#$REPO_PATH/}/constitutional-gate-sweep/"

if [[ $FAIL_COUNT -gt 0 || $REJECTED_COUNT -gt 0 ]]; then
  exit 1
fi

exit 0

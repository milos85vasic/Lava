#!/usr/bin/env bash
# phase-02-test-release-canary.sh — Phase 02 test-category wrapper: release-canary
# (T027 of specs/002-build-test-distribute-pipeline/tasks.md).
#
# This project already has a real, working release-artifact cold-start canary
# at scripts/run-release-canary.sh (LVA-077, §6.Z). It exists specifically
# because a prior real incident (1.2.19-1039) proved release-only R8 bugs can
# crash on cold start even when the debug build is perfectly fine
# (`painterResource()` rejecting a `<layer-list>` that only the minified
# release variant hit). This wrapper does NOT reimplement any of that
# cold-start logic — it is thin glue (Decoupled Reusable Architecture: reuse,
# don't reinvent) that invokes the EXISTING script for real against the EXACT
# release APK about to ship, captures its real output, and records ONE
# Evidence Record (category: release-canary) per
# specs/002-build-test-distribute-pipeline/contracts/evidence-record.schema.json,
# then anti-bluff-validates it via scripts/pipeline/lib/anti-bluff-validate.sh.
#
# scripts/run-release-canary.sh's own invocation contract (read from its own
# header + argument parsing before this wrapper was written):
#
#   scripts/run-release-canary.sh \
#     --apk <path-to-release.apk> --package <application-id> \
#     [--device "<Genymotion VM name>"]   # default: first RUNNING VM \
#     [--watch-seconds <N>]               # default: 25 \
#     [--evidence-dir <dir>]
#
#   Exit 0 = cold-start survived (PASS); 1 = crash/fatal observed (FAIL);
#   2 = config error (e.g. no running Genymotion VM target — a legitimate,
#   honestly-reportable BLOCKED outcome per this project's §6.AH policy,
#   NOT a feature regression).
#
# Target discovery: run-release-canary.sh resolves its own target — it shells
# out to submodules/containers/cmd/genymotion (built on the fly) to `detect`
# gmtool and list `running` Genymotion VM serials, per this project's §6.AH
# container/VM-only policy (a live physical ADB device is explicitly NOT an
# acceptable substitute — those are reserved for other work per §6.AG). This
# wrapper does not attempt to provision a brand-new VM/emulator itself (that
# is explicitly out of scope per this task's own instructions); it only
# invokes the script the way an operator would and reports whatever real
# target-resolution outcome the script itself produces.
#
# Usage:
#   scripts/pipeline/phase-02-test-release-canary.sh <apk-path> <package-id> \
#     [repo-path] [phase-dir]
#
# With no repo-path: resolves via `git rev-parse --show-toplevel` (matches
# every other phase-NN script's existing convention). With no phase-dir:
# defaults to a freshly-created
# `.lava-ci-evidence/pipeline-runs/<UTC-run-id>/phase-02` under repo-path, so
# this script is independently runnable/testable without an orchestrator
# having already created a run directory.
#
# Result derivation (schema's "result" enum is PASS/FAIL/SKIPPED per
# contracts/evidence-record.schema.json — SKIPPED was added specifically for
# an honestly-reported non-execution; see data-model.md's Evidence Record
# section):
#   - run-release-canary.sh exit 0  -> result=PASS  (cold-start proven to
#     survive against the real artifact).
#   - run-release-canary.sh exit 1  -> result=FAIL  (real crash/fatal
#     observed on cold-start — a genuine defect).
#   - run-release-canary.sh exit 2  -> result=SKIPPED  (config error — most
#     commonly no running Genymotion VM target available on this host per
#     §6.AH). EXACTLY 2, not "anything that isn't 0 or 1" (see the next
#     bullet). This is schema-honest: the canary's job is to CONFIRM
#     cold-start survival, and a config error confirms nothing, so PASS
#     would be a fabrication and FAIL would misrepresent a missing host
#     precondition as a real artifact defect — exactly the false-FAIL class
#     data-model.md's Evidence Record section introduced SKIPPED to prevent.
#     The assertion_summary quotes run-release-canary.sh's own real
#     diagnostic message verbatim so a human reader sees the specific,
#     honest reason this run did not execute.
#
#   - any OTHER exit code (126/127/137/139/...) -> result=FAIL. Added
#     2026-08-21 after a wrapper audit. run-release-canary.sh's own contract
#     is "Exit: 0 cold-start survived (PASS); 1 crash/fatal observed (FAIL);
#     2 config error", and every one of its own `exit 2` sites is a genuine
#     config error (missing --apk/--package, APK not found, no running
#     Genymotion VM serial). But it runs under `set -euo pipefail`, so any
#     command that dies inside it propagates ITS OWN status instead: 127 for
#     a missing `adb`/`gmtool`, 126 for a non-executable one, 137 for an
#     OOM/SIGKILL. This wrapper used to map every one of those to SKIPPED,
#     with an assertion_summary asserting "(real host/config precondition
#     gap, not a feature defect)" — a characterization it has no basis for.
#     Observed verbatim:
#         run-release-canary.sh exit code = 127
#         assertion_summary: Genuinely did not execute: ... exit 127 (real
#           host/config precondition gap, not a feature defect) — real
#           diagnostic: "...: line 71: adb: command not found"
#         result: SKIPPED     WRAPPER EXIT = 0
#     SKIPPED does not block the phase (by design), so a canary harness that
#     crashed, was killed, or could not find its tools silenced the §6.Z
#     cold-start gate entirely — the gate that exists because 1.2.19-1039
#     shipped an APK crashing on every cold launch. An unclassified non-zero
#     exit now FAILs, still quoting the canary's own real diagnostic.
#     Regression coverage:
#     tests/pipeline/test_phase_02_release_canary_wrapper.sh CASE 2.
#
# assertion_summary derivation: quotes run-release-canary.sh's OWN real,
# specific outcome — its "verdict.txt" content on a real run (PID + resumed
# activity + fatal-line summary), or its real captured stderr diagnostic on a
# config error — never a generic phrase like "canary ran" or "did not
# crash" (which scripts/pipeline/lib/anti-bluff-validate.sh's rule 1 exists
# specifically to reject as a bluff pattern).
#
# Exit codes (of THIS wrapper):
#   0 - run-release-canary.sh exited 0 (PASS) or exactly 2 (SKIPPED — its own
#       documented, honest config-error block, e.g. no running Genymotion VM
#       target), AND the Evidence Record was anti-bluff-validated.
#   1 - run-release-canary.sh exited 1 (FAIL — a real crash/fatal observed),
#       exited with any other undocumented non-zero code (FAIL — the canary
#       harness itself died, so the cold-start gate produced no verdict), OR
#       the Evidence Record was REJECTED by anti-bluff validation.
#   2 - usage/precondition error (missing args, APK not found, canary script
#       not found).

set -uo pipefail
# Deliberately NOT `set -e`: this wrapper's job includes surviving
# run-release-canary.sh's own non-zero exit (that non-zero exit IS the real,
# wanted signal for the Evidence Record) — every risky command below is
# explicitly guarded (`if`, direct `$?` capture), never relying on inherited
# errexit to stop the script.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/pipeline/lib/evidence.sh
source "${SCRIPT_DIR}/lib/evidence.sh"
# shellcheck source=scripts/pipeline/lib/anti-bluff-validate.sh
source "${SCRIPT_DIR}/lib/anti-bluff-validate.sh"

APK_PATH="${1:-}"
PACKAGE_ID="${2:-}"
REPO_PATH="${3:-}"
PHASE_DIR="${4:-}"

if [[ -z "$APK_PATH" || -z "$PACKAGE_ID" ]]; then
  echo "phase-02-test-release-canary: usage: $0 <apk-path> <package-id> [repo-path] [phase-dir]" >&2
  exit 2
fi

if [[ -z "$REPO_PATH" ]]; then
  REPO_PATH="$(git rev-parse --show-toplevel)"
fi

CANARY_SCRIPT="${REPO_PATH}/scripts/run-release-canary.sh"
if [[ ! -f "$CANARY_SCRIPT" ]]; then
  echo "phase-02-test-release-canary: precondition failed — ${CANARY_SCRIPT} not found" >&2
  exit 2
fi

# APK_PATH may be given relative to CWD or to repo-path; resolve to absolute
# so it survives being handed to run-release-canary.sh regardless of that
# script's own internal cwd assumptions.
if [[ "$APK_PATH" != /* ]]; then
  if [[ -f "$APK_PATH" ]]; then
    APK_PATH="$(cd "$(dirname "$APK_PATH")" && pwd)/$(basename "$APK_PATH")"
  elif [[ -f "${REPO_PATH}/${APK_PATH}" ]]; then
    APK_PATH="${REPO_PATH}/${APK_PATH}"
  fi
fi

if [[ ! -f "$APK_PATH" ]]; then
  echo "phase-02-test-release-canary: precondition failed — release APK not found: ${APK_PATH}" >&2
  exit 2
fi

if [[ -z "$PHASE_DIR" ]]; then
  RUN_ID="$(date -u +%Y-%m-%dT%H-%M-%SZ)"
  PHASE_DIR="${REPO_PATH}/.lava-ci-evidence/pipeline-runs/${RUN_ID}/phase-02"
fi

mkdir -p "$PHASE_DIR"
RAW_DIR="${PHASE_DIR}/release-canary/raw"
mkdir -p "$RAW_DIR"

APK_BASENAME="$(basename "$APK_PATH" .apk)"
TEST_ID="release-canary:${APK_BASENAME}"
CANARY_EVIDENCE_DIR="${RAW_DIR}/${APK_BASENAME}-run-release-canary-evidence"
RAW_LOG="${RAW_DIR}/${APK_BASENAME}.log"
CMD_STR="scripts/run-release-canary.sh --apk '${APK_PATH#$REPO_PATH/}' --package '${PACKAGE_ID}' --evidence-dir '${CANARY_EVIDENCE_DIR#$REPO_PATH/}'"

echo "phase-02-test-release-canary: repo=${REPO_PATH}"
echo "phase-02-test-release-canary: phase_dir=${PHASE_DIR}"
echo "phase-02-test-release-canary: apk=${APK_PATH}"
echo "phase-02-test-release-canary: package=${PACKAGE_ID}"
echo "phase-02-test-release-canary: RUN ${CANARY_SCRIPT}"

"$CANARY_SCRIPT" \
  --apk "$APK_PATH" \
  --package "$PACKAGE_ID" \
  --evidence-dir "$CANARY_EVIDENCE_DIR" \
  > "$RAW_LOG" 2>&1
exit_code=$?

echo "phase-02-test-release-canary: run-release-canary.sh exit code = ${exit_code}"

# --- Derive result + assertion_summary from the canary's OWN real output ---
VERDICT_FILE="${CANARY_EVIDENCE_DIR}/verdict.txt"

if [[ $exit_code -eq 0 ]]; then
  result="PASS"
  if [[ -f "$VERDICT_FILE" && -s "$VERDICT_FILE" ]]; then
    verdict_text="$(tr '\n' ' ' < "$VERDICT_FILE" | sed 's/  */ /g')"
    assertion_summary="run-release-canary.sh exit 0 (PASS) — ${verdict_text}"
  else
    # Fall back to the raw log's own PASS line (script always prints one on
    # exit 0 — see run-release-canary.sh's own final echo).
    verdict_line="$(grep -aE 'RELEASE CANARY PASS' "$RAW_LOG" 2>/dev/null | tail -n 1 || true)"
    assertion_summary="run-release-canary.sh exit 0 (PASS) — ${verdict_line:-cold-start survived; no verdict.txt captured}"
  fi
elif [[ $exit_code -eq 1 ]]; then
  result="FAIL"
  if [[ -f "$VERDICT_FILE" && -s "$VERDICT_FILE" ]]; then
    verdict_text="$(tr '\n' ' ' < "$VERDICT_FILE" | sed 's/  */ /g')"
    assertion_summary="run-release-canary.sh exit 1 (FAIL) — ${verdict_text}"
  else
    verdict_line="$(grep -aE 'RELEASE CANARY FAIL' "$RAW_LOG" 2>/dev/null | tail -n 1 || true)"
    assertion_summary="run-release-canary.sh exit 1 (FAIL) — ${verdict_line:-crash/fatal observed on cold-start; no verdict.txt captured}"
  fi
elif [[ $exit_code -eq 2 ]]; then
  # EXACTLY 2 — run-release-canary.sh's own documented config-error code, most
  # commonly no running Genymotion VM target on this host per §6.AH
  # (container/VM-only — a live physical ADB device is never an acceptable
  # substitute). This is a legitimate, honestly-reportable SKIPPED outcome,
  # not a feature defect — forcing it to FAIL would misrepresent a missing
  # host precondition as a real artifact regression, exactly the false-FAIL
  # class data-model.md's Evidence Record section introduced SKIPPED to
  # prevent.
  result="SKIPPED"
  real_diag="$(grep -av '^[[:space:]]*$' "$RAW_LOG" 2>/dev/null | tail -n 1 || true)"
  assertion_summary="Genuinely did not execute: run-release-canary.sh exit 2 (its own documented config-error code — a real host/config precondition gap, not a feature defect) — real diagnostic: \"${real_diag:-<no output captured>}\""
else
  # Any OTHER non-zero code: NOT one of the three outcomes run-release-canary.sh
  # documents. Because that script runs under `set -euo pipefail`, a dead tool
  # (127), a non-executable one (126) or a killed process (137/139) propagates
  # its own status here. The canary produced no cold-start verdict at all, and
  # this wrapper has no basis for calling that a host/config gap — see the
  # header note. Recorded as FAIL so the §6.Z gate cannot be silenced by its
  # own harness dying.
  result="FAIL"
  real_diag="$(grep -av '^[[:space:]]*$' "$RAW_LOG" 2>/dev/null | tail -n 1 || true)"
  assertion_summary="run-release-canary.sh exited ${exit_code}, which is NOT one of its own documented outcomes (0=PASS, 1=crash/fatal, 2=config error) — the cold-start canary produced no verdict for this release artifact, so nothing about its cold-start behaviour was verified. Real diagnostic: \"${real_diag:-<no output captured>}\""
fi

echo "phase-02-test-release-canary: assertion_summary: ${assertion_summary}"

record_path=""
if ! record_path="$(write_evidence_record "$PHASE_DIR" "$TEST_ID" "release-canary" "$CMD_STR" "$result" "$assertion_summary" "$RAW_LOG")"; then
  echo "phase-02-test-release-canary: ERROR: write_evidence_record failed" >&2
  exit 1
fi

echo "phase-02-test-release-canary: record written: ${record_path#$REPO_PATH/}"

if validate_evidence_record "$record_path"; then
  echo "phase-02-test-release-canary: anti_bluff_status=validated"
  anti_bluff_ok=1
else
  echo "phase-02-test-release-canary: anti_bluff_status=REJECTED"
  anti_bluff_ok=0
fi

echo ""
echo "phase-02-test-release-canary: SUMMARY"
echo "  test_id:      ${TEST_ID}"
echo "  result:       ${result}"
echo "  canary exit:  ${exit_code}"
echo "  record:       ${record_path#$REPO_PATH/}"
echo "  anti_bluff:   $(jq -r '.anti_bluff_status' "$record_path" 2>/dev/null || echo unknown)"

if [[ "$result" == "FAIL" || $anti_bluff_ok -ne 1 ]]; then
  exit 1
fi

exit 0

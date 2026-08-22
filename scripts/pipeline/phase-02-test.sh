#!/usr/bin/env bash
# scripts/pipeline/phase-02-test.sh — tasks.md T029: wires the independent
# test-category wrapper scripts (T021-T028, each built and already
# independently verified against this project's real test suites by its own
# prior agent) into one phase, dispatched as genuinely parallel OS processes
# — mirroring scripts/pipeline/phase-01-build.sh's own structure and
# conventions exactly (same usage shape, same precondition check, same
# parallel dispatch-then-wait pattern, same exit-code convention).
#
# Usage:
#   scripts/pipeline/phase-02-test.sh <run_id> [repo-path]
#
# <run_id> MUST already have a report.json (via lib/run-report.sh's
# init_run_report, called by the top-level orchestrator before this phase
# runs). This script appends to that same report.json — it never creates a
# new run.
#
# ---------------------------------------------------------------------------
# Which wrappers are dispatched, and how each is invoked
# ---------------------------------------------------------------------------
# Seven test-category wrapper scripts now exist in this directory, each
# independently built and verified by a separate prior agent against this
# project's real test suites (Go, Kotlin, hermetic bash suites, stress/chaos,
# release canary). A sixth (real-device-challenge) is expected to land later
# — this script tolerates its absence today and will pick it up automatically
# once the file exists, with zero changes required here.
#
#   go            -> phase-02-test-go.sh <repo-path> <phase-dir>
#                     (covers BOTH go-unit-integration and real-binary-contract)
#   kotlin        -> phase-02-test-kotlin.sh <repo-path> <phase-dir>
#   hermetic      -> phase-02-test-hermetic.sh <repo-path> <phase-dir>
#   stress-chaos  -> phase-02-test-stress-chaos.sh <repo-path> <phase-dir>
#   release-canary -> phase-02-test-release-canary.sh <apk-path> <package-id> \
#                      <repo-path> <phase-dir>
#   real-device-challenge -> phase-02-test-challenge.sh <repo-path> <phase-dir>
#                     (dispatched only if the script file exists)
#
# release-canary needs the exact release APK THIS run just built (T016) and
# the app's applicationId. Both are resolved from real sources, never
# hardcoded (§6.R): the APK path comes from the run's OWN report.json
# build_artifacts entry with artifact_id == "app-release" (written by
# phase-01-build.sh), and the applicationId is read from the real
# app/build.gradle.kts. If phase-01 hasn't recorded an app-release Build
# Artifact yet (its build phase failed before producing one, hasn't run at
# all, or the recorded path no longer exists on disk), release-canary has
# nothing real to test against — this category is honestly NOT dispatched
# for this run rather than invoked against a stale or missing artifact. The
# same "don't dispatch what can't be meaningfully invoked" rule applies to
# real-device-challenge when its wrapper script doesn't exist yet: a wrapper
# that was never dispatched is not a failure, it is a category not yet
# applicable to this run (an actual test-suite failure, or an anti-bluff
# rejection, always IS a failure — see the overall-result rule below).
#
# "kotlin" and "real-device-challenge" both independently invoke Gradle
# (`./gradlew ... test` and `./gradlew ... connectedDebugAndroidTest`
# respectively) against this SAME project checkout — Gradle daemon/lock
# contention makes concurrent invocations unsafe, so these two (and only
# these two) are serialized relative to each other: "real-device-challenge"
# is not dispatched until "kotlin" has finished. Every other wrapper
# (go/hermetic/stress-chaos/release-canary) never touches Gradle and runs
# truly in parallel with everything else throughout.
#
# Every wrapper path above is overridable via an env var
# (PHASE02_GO_WRAPPER, PHASE02_KOTLIN_WRAPPER, PHASE02_HERMETIC_WRAPPER,
# PHASE02_STRESS_CHAOS_WRAPPER, PHASE02_RELEASE_CANARY_WRAPPER,
# PHASE02_CHALLENGE_WRAPPER) purely so this aggregator's own
# dispatch-then-wait-then-aggregate mechanics can be verified in isolation
# with tiny fake stand-in scripts, without ever touching or slowing down the
# real (already independently proven) wrapper scripts. Production callers
# never need to set these — the defaults always point at the real scripts in
# this directory.
#
# ---------------------------------------------------------------------------
# How the overall "test" phase result is computed
# ---------------------------------------------------------------------------
# Each wrapper already writes its own Evidence Records (JSON) directly under
# this run's phase-02/<category>/ directories via the shared
# scripts/pipeline/lib/evidence.sh, and already anti-bluff-validates each one
# itself via scripts/pipeline/lib/anti-bluff-validate.sh before it exits (see
# any of the five wrapper scripts' own source for that call). This script
# does NOT re-validate or re-write any Evidence Record — it only dispatches,
# waits, and AGGREGATES. Rather than parsing each wrapper's own printed
# console SUMMARY (worded slightly differently per wrapper, since they were
# built independently), the aggregation scans the real Evidence Record JSON
# files themselves under "<phase_dir>/<category>/<test_id>.json" (exactly 2
# path segments below phase_dir per data-model.md's path convention — this
# is what distinguishes a real Evidence Record from a wrapper's own raw
# output companion files, which always live one level deeper under
# "<category>/raw/"). This is authoritative and format-independent: it does
# not matter how any one wrapper phrases its own summary line, only what it
# actually wrote to disk.
#
# Overall "test" phase result is PASS if and only if ALL of:
#   1. Every DISPATCHED wrapper (i.e. every wrapper this script actually
#      invoked — a category that was never dispatched because its script
#      doesn't exist yet, or because its required inputs like the release
#      APK aren't available this run, does not count here) exited 0.
#   2. Zero scanned Evidence Records have result == "FAIL".
#   3. Zero scanned Evidence Records have anti_bluff_status starting with
#      "REJECTED".
#   4. At LEAST ONE Evidence Record was actually scanned.
#
# Condition 4 was added 2026-08-21 and is not a formality. Conditions 1-3 are
# all satisfied vacuously by a run in which every dispatched wrapper exits 0
# without writing anything: no wrapper failed, no record said FAIL, no record
# was REJECTED — because there were no records. Such a run reported
# "PASSED - all N dispatched wrapper(s) exited 0, 0 Evidence Records scanned",
# and the run report carried it forward as a passing phase. That is the same
# principle already stated below for the zero-wrappers case ("an empty test
# phase proves nothing"), applied to the case that actually reaches this
# aggregator. Regression coverage: tests/pipeline/test_phase_02_aggregation.sh
# CASE 2.
# A SKIPPED Evidence Record does NOT block PASS (an honestly-reported,
# anti-bluff-validated non-execution is not a pipeline failure — matches
# data-model.md's Evidence Record Validation rules and
# lib/run-report.sh's own finalize_run_report() treatment of the analogous
# evidence_summary.skipped counter). A run where ZERO wrappers were
# dispatched at all also does not pass: an empty test phase proves nothing.
#
# Exit codes:
#   0 - every dispatched wrapper exited 0, zero FAIL Evidence Records, zero
#       REJECTED Evidence Records.
#   1 - at least one dispatched wrapper exited non-zero, OR at least one
#       Evidence Record reports result FAIL, OR at least one Evidence Record
#       was REJECTED by its own wrapper's anti-bluff validation.
#   2 - usage/precondition error (missing run_id, report.json absent, jq
#       missing).

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# shellcheck source=scripts/pipeline/lib/run-report.sh
source "$SCRIPT_DIR/lib/run-report.sh"

RUN_ID="${1:-}"
REPO_PATH_OVERRIDE="${2:-}"

if [[ -z "$RUN_ID" ]]; then
  echo "phase-02-test: usage: $0 <run_id> [repo-path]" >&2
  exit 2
fi

REPORT_PATH=".lava-ci-evidence/pipeline-runs/${RUN_ID}/report.json"
if [[ ! -f "$REPORT_PATH" ]]; then
  echo "phase-02-test: precondition failed — $REPORT_PATH does not exist (call init_run_report first)" >&2
  exit 2
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "phase-02-test: FAILED — required tool 'jq' not found on PATH" >&2
  exit 2
fi

REPO_PATH="${REPO_PATH_OVERRIDE:-$REPO_ROOT}"

PHASE_DIR=".lava-ci-evidence/pipeline-runs/${RUN_ID}/phase-02"
mkdir -p "$PHASE_DIR"

# --- Wrapper script paths (overridable for isolated testing — see header) --
GO_WRAPPER="${PHASE02_GO_WRAPPER:-$SCRIPT_DIR/phase-02-test-go.sh}"
KOTLIN_WRAPPER="${PHASE02_KOTLIN_WRAPPER:-$SCRIPT_DIR/phase-02-test-kotlin.sh}"
HERMETIC_WRAPPER="${PHASE02_HERMETIC_WRAPPER:-$SCRIPT_DIR/phase-02-test-hermetic.sh}"
STRESS_CHAOS_WRAPPER="${PHASE02_STRESS_CHAOS_WRAPPER:-$SCRIPT_DIR/phase-02-test-stress-chaos.sh}"
RELEASE_CANARY_WRAPPER="${PHASE02_RELEASE_CANARY_WRAPPER:-$SCRIPT_DIR/phase-02-test-release-canary.sh}"
CHALLENGE_WRAPPER="${PHASE02_CHALLENGE_WRAPPER:-$SCRIPT_DIR/phase-02-test-challenge.sh}"
GATE_SWEEP_WRAPPER="${PHASE02_GATE_SWEEP_WRAPPER:-$SCRIPT_DIR/phase-02-test-constitutional-gate-sweep.sh}"

START_TS=$(date +%s)

echo "phase-02-test: dispatching available test-category wrappers as parallel processes"

declare -a DISPATCHED_NAMES=()
declare -a DISPATCHED_PIDS=()
declare -a DISPATCHED_LOGS=()

# _dispatch <name> <wrapper-path> [args...] — backgrounds <wrapper-path> with
# the given args, redirecting its stdout+stderr to its own log file under
# PHASE_DIR, and records its name/pid/log for the wait loop below. Skips
# gracefully (not a failure) when <wrapper-path> does not exist, so a
# not-yet-landed category (or an isolated-test env-var override pointing at
# a path that isn't there) never blocks the rest of the phase.
_dispatch() {
  local name="$1" wrapper="$2"
  shift 2
  local log="${PHASE_DIR}/${name}.log"

  if [[ ! -f "$wrapper" ]]; then
    echo "phase-02-test: SKIPPING '${name}' wrapper — script not found at '${wrapper}' (category not yet wired in / not applicable this run)"
    return 0
  fi

  echo "phase-02-test: dispatching '${name}' -> bash ${wrapper} $* (log: ${log})"
  bash "$wrapper" "$@" >"$log" 2>&1 &
  DISPATCHED_NAMES+=("$name")
  DISPATCHED_PIDS+=("$!")
  DISPATCHED_LOGS+=("$log")
}

_dispatch "go" "$GO_WRAPPER" "$REPO_PATH" "$PHASE_DIR"
_dispatch "kotlin" "$KOTLIN_WRAPPER" "$REPO_PATH" "$PHASE_DIR"
_dispatch "hermetic" "$HERMETIC_WRAPPER" "$REPO_PATH" "$PHASE_DIR"
_dispatch "stress-chaos" "$STRESS_CHAOS_WRAPPER" "$REPO_PATH" "$PHASE_DIR"
# constitutional-gate-sweep joins this first parallel group rather than being
# serialized after "kotlin": it invokes verify-all-constitution-rules.sh, which
# is pure bash + git + grep and starts ZERO Gradle daemons, so it cannot contend
# with the Gradle-invoking wrappers the way "real-device-challenge" does.
#
# It does READ the working tree while sibling wrappers WRITE to it, which looks
# like a race and is not one: every gate in the sweep scans TRACKED files, while
# everything the siblings produce (Gradle build outputs, Evidence Records under
# .lava-ci-evidence/pipeline-runs/) is gitignored and therefore invisible to them.
#
# ADDED 2026-08-21 (T028 closure). Until then this wrapper existed, was fully
# functional, and was NEVER DISPATCHED — dead code in the pipeline. The category
# was silently absent from every run: not skipped-with-a-reason, just missing.
# That is worse than a failing gate, because the run summary looked complete.
# Note this was NOT covered by the tolerate-absence path documented in this
# file's header: that path only protects a category whose _dispatch line exists
# but whose script file does not. A category with no _dispatch line at all is
# invisible to it.
_dispatch "constitutional-gate-sweep" "$GATE_SWEEP_WRAPPER" "$REPO_PATH" "$PHASE_DIR"

# release-canary: resolve the release APK + applicationId from real sources
# (§6.R — never hardcoded) before deciding whether this category applies to
# this run at all.
RELEASE_APK="$(jq -r '[.build_artifacts[]? | select(.artifact_id == "app-release")][-1].build_output_path // empty' "$REPORT_PATH" 2>/dev/null)"
if [[ -n "$RELEASE_APK" && -f "$RELEASE_APK" ]]; then
  PACKAGE_ID="$(grep -E '^[[:space:]]*applicationId[[:space:]]*=' "${REPO_PATH}/app/build.gradle.kts" 2>/dev/null | head -1 | sed -E 's/.*"([^"]*)".*/\1/')"
  if [[ -n "$PACKAGE_ID" ]]; then
    _dispatch "release-canary" "$RELEASE_CANARY_WRAPPER" "$RELEASE_APK" "$PACKAGE_ID" "$REPO_PATH" "$PHASE_DIR"
  else
    echo "phase-02-test: SKIPPING 'release-canary' wrapper — could not determine applicationId from ${REPO_PATH}/app/build.gradle.kts"
  fi
else
  echo "phase-02-test: SKIPPING 'release-canary' wrapper — no 'app-release' Build Artifact recorded in ${REPORT_PATH} (or its build_output_path no longer exists on disk)"
fi

# real-device-challenge invokes Gradle itself (`./gradlew
# :<module>:connectedDebugAndroidTest ...` — see phase-02-test-challenge.sh's
# own header comment) on the SAME project checkout the kotlin wrapper is
# already building/testing via `./gradlew ... test`. Concurrent Gradle
# invocations against one checkout are unsafe (Gradle daemon/lock contention
# — see phase-01-build-android.sh's own header note on this exact hazard), so
# dispatching both as concurrent OS processes would violate this feature's
# own documented no-concurrent-Gradle rule. If "kotlin" was actually
# dispatched this run, wait for IT SPECIFICALLY to finish before dispatching
# "real-device-challenge" — waiting on an already-completed child PID a
# second time (in the aggregation wait loop below) is well-defined bash
# behavior and returns the same cached exit code, so this does not disturb
# normal per-wrapper exit-code recording. go/hermetic/stress-chaos/
# release-canary never touch Gradle and continue running truly in parallel
# throughout, including while "kotlin" is still running and after
# "real-device-challenge" starts.
KOTLIN_PID=""
for i in "${!DISPATCHED_NAMES[@]}"; do
  if [[ "${DISPATCHED_NAMES[$i]}" == "kotlin" ]]; then
    KOTLIN_PID="${DISPATCHED_PIDS[$i]}"
    break
  fi
done
if [[ -n "$KOTLIN_PID" ]]; then
  echo "phase-02-test: waiting on 'kotlin' (pid ${KOTLIN_PID}) to finish before dispatching 'real-device-challenge' — both invoke Gradle against the same checkout and must never run concurrently"
  wait "$KOTLIN_PID"
  KOTLIN_EARLY_RC=$?  # vacuous-pass-ok: this `wait` exists only to serialize Gradle against the challenge wrapper; the same child's exit code is recorded for real by the aggregation wait loop below.
  echo "phase-02-test: 'kotlin' (pid ${KOTLIN_PID}) finished (exit ${KOTLIN_EARLY_RC}, recorded normally in the aggregation wait loop below) — dispatching 'real-device-challenge' now"
fi

_dispatch "real-device-challenge" "$CHALLENGE_WRAPPER" "$REPO_PATH" "$PHASE_DIR"

if [[ "${#DISPATCHED_NAMES[@]}" -eq 0 ]]; then
  echo "phase-02-test: FAILED — zero wrappers were dispatched (no wrapper scripts found / no applicable inputs); an empty test phase proves nothing" >&2
  END_TS=$(date +%s)
  DURATION=$((END_TS - START_TS))
  append_phase_result "$RUN_ID" "test" "FAIL" "$DURATION" "$PHASE_DIR"
  exit 1
fi

echo "phase-02-test: waiting on ${#DISPATCHED_NAMES[@]} dispatched wrapper(s): ${DISPATCHED_NAMES[*]}"

declare -a EXIT_CODES=()
_any_wrapper_nonzero=0
for i in "${!DISPATCHED_PIDS[@]}"; do
  pid="${DISPATCHED_PIDS[$i]}"
  name="${DISPATCHED_NAMES[$i]}"
  wait "$pid"
  rc=$?
  EXIT_CODES+=("$rc")
  echo "phase-02-test: wrapper '${name}' (pid ${pid}) exited ${rc}"
  if [[ "$rc" -ne 0 ]]; then
    _any_wrapper_nonzero=1
  fi
done

END_TS=$(date +%s)
DURATION=$((END_TS - START_TS))

echo ""
echo "phase-02-test: --- wrapper output logs ---"
for i in "${!DISPATCHED_NAMES[@]}"; do
  echo ""
  echo "phase-02-test: === ${DISPATCHED_NAMES[$i]} (exit ${EXIT_CODES[$i]}) ==="
  cat "${DISPATCHED_LOGS[$i]}"
done

# ---------------------------------------------------------------------------
# Aggregate directly from the real Evidence Record JSON files each wrapper
# wrote — authoritative, format-independent (see header). A real Evidence
# Record always lives at exactly "<phase_dir>/<category>/<test_id>.json",
# i.e. exactly 2 path segments below PHASE_DIR; a wrapper's own raw-output
# companion files always live one level deeper, under "<category>/raw/", so
# -mindepth 2 -maxdepth 2 reliably selects only real Evidence Records.
# ---------------------------------------------------------------------------
_total=0
_pass=0
_fail=0
_skipped=0
_rejected=0
declare -a _fail_records=()
declare -a _rejected_records=()

while IFS= read -r -d '' record_path; do
  result="$(jq -r '.result // empty' "$record_path" 2>/dev/null)"
  status="$(jq -r '.anti_bluff_status // empty' "$record_path" 2>/dev/null)"
  test_id="$(jq -r '.test_id // empty' "$record_path" 2>/dev/null)"

  _total=$((_total + 1))
  case "$result" in
    PASS)
      _pass=$((_pass + 1))
      ;;
    FAIL)
      _fail=$((_fail + 1))
      _fail_records+=("${record_path} :: ${test_id}")
      ;;
    SKIPPED)
      _skipped=$((_skipped + 1))
      ;;
    *)
      # Defensive: a record whose result cannot be interpreted is never
      # silently ignored — treat it the same as a real FAIL.
      _fail=$((_fail + 1))
      _fail_records+=("${record_path} :: (unrecognized result '${result}')")
      ;;
  esac

  if [[ "$status" == REJECTED* ]]; then
    _rejected=$((_rejected + 1))
    _rejected_records+=("${record_path} :: ${status}")
  fi
done < <(find "$PHASE_DIR" -mindepth 2 -maxdepth 2 -type f -name '*.json' -print0 2>/dev/null)

echo ""
echo "phase-02-test: SUMMARY"
echo "  wrappers dispatched:        ${#DISPATCHED_NAMES[@]} (${DISPATCHED_NAMES[*]})"
echo "  wrapper exit codes:         ${EXIT_CODES[*]}"
echo "  Evidence Records found:     ${_total}"
echo "  PASS:                       ${_pass}"
echo "  FAIL:                       ${_fail}"
echo "  SKIPPED:                    ${_skipped}"
echo "  REJECTED (anti-bluff):      ${_rejected}"

if [[ "${#_fail_records[@]}" -gt 0 ]]; then
  echo ""
  echo "  FAILing Evidence Records:"
  for r in "${_fail_records[@]}"; do
    echo "    - ${r}"
  done
fi

if [[ "${#_rejected_records[@]}" -gt 0 ]]; then
  echo ""
  echo "  REJECTED Evidence Records (anti-bluff validation failed):"
  for r in "${_rejected_records[@]}"; do
    echo "    - ${r}"
  done
fi

PHASE_RESULT="PASS"
if [[ "$_any_wrapper_nonzero" -ne 0 || "$_fail" -gt 0 || "$_rejected" -gt 0 || "$_total" -eq 0 ]]; then
  PHASE_RESULT="FAIL"
fi

append_phase_result "$RUN_ID" "test" "$PHASE_RESULT" "$DURATION" "$PHASE_DIR"

if [[ "$PHASE_RESULT" == "FAIL" ]]; then
  echo ""
  if [[ "$_total" -eq 0 ]]; then
    echo "phase-02-test: FAILED — ${#DISPATCHED_NAMES[@]} wrapper(s) were dispatched and every one exited 0, but they produced ZERO Evidence Records between them. An empty test phase proves nothing: there is no evidence here to have passed. Check each wrapper's log under ${PHASE_DIR} — a wrapper exiting 0 without writing a single record is a bug in that wrapper, not a passing run." >&2
  fi
  echo "phase-02-test: FAILED — any_wrapper_nonzero=${_any_wrapper_nonzero} fail=${_fail} rejected=${_rejected} evidence_records=${_total}" >&2
  exit 1
fi

echo ""
echo "phase-02-test: PASSED — all ${#DISPATCHED_NAMES[@]} dispatched wrapper(s) exited 0, ${_total} Evidence Records scanned, 0 FAIL, 0 REJECTED (${_skipped} SKIPPED, which does not block PASS)"
exit 0

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
# NARROWED 2026-08-26 (corpus-floor sweep, P2). That tolerance now applies
# ONLY to a wrapper whose path was redirected by its PHASE02_*_WRAPPER
# override — i.e. the isolated-test hook described above. All seven wrappers
# exist in this directory today, so a wrapper resolved to its in-tree DEFAULT
# path and missing from disk is drift, not a not-yet-landed category, and the
# category it covers would silently disappear from every pipeline run. That is
# refused (condition 5 below) rather than skipped.
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
#   5. EVERY dispatched wrapper produced at least one Evidence Record of its
#      own, and every wrapper resolved to its in-tree default path exists on
#      disk. Added 2026-08-26 (corpus-floor sweep finding P2) — see the
#      PER-CATEGORY CORPUS FLOOR block below for the measured reproduction.
#      Condition 4 is a floor with one stair: it counts records in TOTAL, so a
#      category that was dispatched, exited 0 and wrote nothing is invisible
#      behind any other category's records.
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
#       is REJECTED by this phase's own independent anti-bluff re-validation,
#       OR at least one Evidence Record ends up with an anti_bluff_status
#       that is neither exactly "validated" nor a "REJECTED: ..." verdict
#       (i.e. it was never actually evaluated — see the aggregation loop).
#   2 - usage/precondition error (missing run_id, report.json absent, jq
#       missing).

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# shellcheck source=scripts/pipeline/lib/run-report.sh
source "$SCRIPT_DIR/lib/run-report.sh"

# shellcheck source=scripts/pipeline/lib/anti-bluff-validate.sh
# The aggregator below re-validates EVERY Evidence Record it scans with this
# independent validator rather than trusting the anti_bluff_status field it
# finds on disk — see the long note above the aggregation loop.
source "$SCRIPT_DIR/lib/anti-bluff-validate.sh"

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

# --- PER-CATEGORY EVIDENCE MANIFEST (corpus-floor sweep 2026-08-26, P2) ----
# Dispatch name -> the Evidence Record directory name(s) that category's
# wrapper writes under PHASE_DIR. This is the manifest the per-category floor
# below derives its expectation from; it is NOT a cosmetic label map.
#
# "go" carries two because phase-02-test-go.sh classifies each Go package at
# runtime (real-binary-contract for tests/contract/**, go-unit-integration
# otherwise), so either directory alone is a legitimate outcome for that
# wrapper — the floor requires records in at least one of them, never in both.
#
# Every name passed to _dispatch MUST appear here. A dispatched category with
# no entry is refused below rather than skipped, because silently exempting an
# unmapped category from the floor would be this same defect relocated INTO
# the floor (the failure mode verify-all-constitution-rules.sh guards against
# in its own derived registry count).
declare -A CATEGORY_EVIDENCE_DIRS=(
  [go]="go-unit-integration real-binary-contract"
  [kotlin]="kotlin-unit"
  [hermetic]="hermetic-script"
  [stress-chaos]="stress-chaos"
  [release-canary]="release-canary"
  [constitutional-gate-sweep]="constitutional-gate-sweep"
  [real-device-challenge]="real-device-challenge"
)

START_TS=$(date +%s)

echo "phase-02-test: dispatching available test-category wrappers as parallel processes"

declare -a DISPATCHED_NAMES=()
declare -a DISPATCHED_PIDS=()
declare -a DISPATCHED_LOGS=()

# Absent wrappers, split by CAUSE — a diagnosis that misstates its cause sends
# the reader to the wrong remedy. A wrapper resolved to its in-tree default
# path under this script's own directory but not present on disk is real drift
# (deleted, renamed, or never landed) and the category vanishes from every
# pipeline run; a wrapper whose path was redirected by a PHASE02_*_WRAPPER
# override is the documented isolation hook this file's header describes, and
# its absence is that harness's deliberate choice, not drift.
declare -a MISSING_DEFAULT_WRAPPERS=()
declare -a ABSENT_OVERRIDDEN_WRAPPERS=()

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
    if [[ "$wrapper" == "$SCRIPT_DIR/"* ]]; then
      echo "phase-02-test: MISSING '${name}' wrapper — its in-tree default '${wrapper}' does not exist on disk"
      MISSING_DEFAULT_WRAPPERS+=("${name} -> ${wrapper}")
    else
      echo "phase-02-test: SKIPPING '${name}' wrapper — overridden path '${wrapper}' does not exist (isolated-test override; not the in-tree default)"
      ABSENT_OVERRIDDEN_WRAPPERS+=("${name} -> ${wrapper}")
    fi
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
#
# ANTI-BLUFF STATUS IS RE-DERIVED HERE, NEVER TRUSTED AS FOUND (fixed
# 2026-08-26; forensic anchor). This loop used to read anti_bluff_status
# straight off disk and count a record rejected only when that string began
# with "REJECTED". Two defects composed into a fail-open gate:
#
#   1. lib/evidence.sh's write_evidence_record stamped every record it wrote
#      with the literal "validated" as a placeholder, so "the independent
#      validator accepted this record" and "no validator ever looked at this
#      record" were byte-identical on disk; and
#   2. an ABSENT or empty anti_bluff_status compared equal to "not rejected"
#      (`// empty` yields "", and `[[ "" == REJECTED* ]]` is false), so a
#      record carrying no status at all counted as clean. Note the asymmetry
#      this removes: an unrecognised `result` already fell to the `*)` arm and
#      counted as FAIL (fail-closed), while an unrecognised anti_bluff_status
#      counted as fine (fail-open).
#
# Measured consequence, end-to-end through this script: a real-device-challenge
# Evidence Record whose entire assertion_summary was "did not crash" reached
# "phase-02-test: PASSED" with "REJECTED (anti-bluff): 0", while the real
# validator's verdict on that same record was "REJECTED: assertion_summary
# matches generic bluff pattern 'did not crash' with no other specific
# content". Per §6.Z clause 4 a cold-start survival check is the MINIMUM and
# explicitly not sufficient alone, and §6.AK exists precisely because a
# C00-only gate green-lit a release whose claimed fixes were never exercised.
#
# The loop therefore invokes validate_evidence_record on every record itself.
# That function is documented IDEMPOTENT and NOT STICKY (it re-derives its
# verdict from the record's other fields and never reads the prior status as
# an input), so re-running it over records a wrapper already validated is
# both safe and the point: this phase's verdict now rests on a validation
# that provably ran, in this process, over these exact bytes. Three outcomes
# are distinguished, and only the first is a pass:
#     validated      an independent validator examined it and accepted it
#     REJECTED: ...  an independent validator examined it and refused it
#     anything else  it was never evaluated -> counted as unevaluated, FAIL
_total=0
_pass=0
_fail=0
_skipped=0
_rejected=0
_unevaluated=0
_revalidated=0
_placeholder_on_disk=0
declare -a _fail_records=()
declare -a _rejected_records=()
declare -a _unevaluated_records=()
declare -a _unvalidated_by_wrapper=()
declare -A _records_by_dir=()

while IFS= read -r -d '' record_path; do
  # Tally each record against the category directory it sits in — the
  # per-category floor below needs to know WHICH categories produced
  # evidence, not merely that some total was non-zero.
  _rel="${record_path#"$PHASE_DIR"/}"
  _cat_dir="${_rel%%/*}"
  _records_by_dir["$_cat_dir"]=$(( ${_records_by_dir["$_cat_dir"]:-0} + 1 ))

  result="$(jq -r '.result // empty' "$record_path" 2>/dev/null)"
  test_id="$(jq -r '.test_id // empty' "$record_path" 2>/dev/null)"

  # What the record claimed about itself BEFORE this phase looked at it.
  # Recorded only so a wrapper that never ran the validator is visible in
  # the summary; it is never used as the verdict.
  status_on_disk="$(jq -r '.anti_bluff_status // empty' "$record_path" 2>/dev/null)"
  if [[ "$status_on_disk" == "REJECTED: anti-bluff validation has not run on this record"* ]]; then
    _placeholder_on_disk=$((_placeholder_on_disk + 1))
    _unvalidated_by_wrapper+=("${record_path} :: ${test_id}")
  fi

  # Independent re-validation — the authoritative step.
  validate_evidence_record "$record_path" >/dev/null 2>&1
  _revalidated=$((_revalidated + 1))
  status="$(jq -r '.anti_bluff_status // empty' "$record_path" 2>/dev/null)"

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

  if [[ "$status" == "validated" ]]; then
    :
  elif [[ "$status" == REJECTED* ]]; then
    _rejected=$((_rejected + 1))
    _rejected_records+=("${record_path} :: ${status}")
  else
    # Neither verdict. The validator could not be run, could not write, or
    # something else left this record unexamined. An unexamined record is
    # NOT a passing record — that equivalence is the exact bluff this
    # branch exists to refuse.
    _unevaluated=$((_unevaluated + 1))
    _unevaluated_records+=("${record_path} :: anti_bluff_status='${status:-<absent>}' — never evaluated")
  fi
done < <(find "$PHASE_DIR" -mindepth 2 -maxdepth 2 -type f -name '*.json' -print0 2>/dev/null)

# ---------------------------------------------------------------------------
# PER-CATEGORY CORPUS FLOOR (corpus-floor sweep 2026-08-26, finding P2).
#
# The `_total -eq 0` rule below is a floor with ONE stair: it fires only when
# the run produced no evidence AT ALL, so one record from one category
# certifies the whole test phase. Measured on this script, verbatim:
#
#   wrappers dispatched:    2 (hermetic real-device-challenge)
#   wrapper exit codes:     0 0
#   Evidence Records found: 1
#   phase-02-test: PASSED — all 2 dispatched wrapper(s) exited 0 …    EXIT=0
#   records on disk:        hermetic-script/stub.HonestSuite.json
#                           (real-device-challenge produced ZERO)
#
# real-device-challenge is the category §6.AA clause 8 condition (C) makes
# mandatory for all four Android variants, and §6.AK exists because a gate
# that ran one thing green-lit a release claiming many. Its wrapper already
# says so in its own source: "the aggregate guard there does NOT rescue it,
# because it only fires when the run has zero records IN TOTAL, so any other
# wrapper's records let this one contribute nothing while the phase still
# reports PASS" (phase-02-test-challenge.sh, the TOTAL_SELECTED/TOTAL_RECORDS
# floor). That was documented one level down and unenforced up here.
#
# The expectation is DERIVED per dispatched category from CATEGORY_EVIDENCE_
# DIRS above and from the wrappers this run actually invoked — never a
# hardcoded record count, which would go stale the moment a suite grows or
# shrinks. A wrapper that exits 0 having written nothing is a bug in that
# wrapper (every one of them has its own zero-record refusal), so on a healthy
# run this floor cannot fire; it fires only when the phase is about to make an
# unbacked claim.
declare -a _silent_categories=()
declare -a _unmapped_categories=()
for i in "${!DISPATCHED_NAMES[@]}"; do
  _name="${DISPATCHED_NAMES[$i]}"
  _expected_dirs="${CATEGORY_EVIDENCE_DIRS[$_name]:-}"
  if [[ -z "$_expected_dirs" ]]; then
    _unmapped_categories+=("$_name")
    continue
  fi
  _n=0
  for _d in $_expected_dirs; do
    _n=$(( _n + ${_records_by_dir["$_d"]:-0} ))
  done
  if [[ "$_n" -eq 0 ]]; then
    _silent_categories+=("${_name} (exit ${EXIT_CODES[$i]}; expected Evidence Records under ${PHASE_DIR}/{${_expected_dirs// /,}}/)")
  fi
done

echo ""
echo "phase-02-test: SUMMARY"
echo "  wrappers dispatched:        ${#DISPATCHED_NAMES[@]} (${DISPATCHED_NAMES[*]})"
echo "  wrapper exit codes:         ${EXIT_CODES[*]}"
echo "  Evidence Records found:     ${_total}"
echo "  PASS:                       ${_pass}"
echo "  FAIL:                       ${_fail}"
echo "  SKIPPED:                    ${_skipped}"
echo "  REJECTED (anti-bluff):      ${_rejected}"
echo "  UNEVALUATED (anti-bluff):   ${_unevaluated}"
echo "  independently re-validated: ${_revalidated} of ${_total}"
echo "  arrived unvalidated:        ${_placeholder_on_disk} (record(s) whose wrapper never ran the validator)"
echo "  categories with 0 records:  ${#_silent_categories[@]} (of ${#DISPATCHED_NAMES[@]} dispatched)"
echo "  wrappers missing in-tree:   ${#MISSING_DEFAULT_WRAPPERS[@]}"

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

if [[ "${#_unevaluated_records[@]}" -gt 0 ]]; then
  echo ""
  echo "  UNEVALUATED Evidence Records (no anti-bluff verdict exists for these):"
  for r in "${_unevaluated_records[@]}"; do
    echo "    - ${r}"
  done
fi

if [[ "${#_unvalidated_by_wrapper[@]}" -gt 0 ]]; then
  echo ""
  echo "  NOTE — Evidence Records that arrived still carrying write_evidence_record's"
  echo "  not-yet-validated placeholder, i.e. the wrapper that wrote them never ran"
  echo "  anti-bluff validation itself. This phase validated them independently, so"
  echo "  the verdict above is real; the wrapper is nonetheless not holding up its"
  echo "  end of FR-004 and should be fixed:"
  for r in "${_unvalidated_by_wrapper[@]}"; do
    echo "    - ${r}"
  done
fi

PHASE_RESULT="PASS"
if [[ "$_any_wrapper_nonzero" -ne 0 || "$_fail" -gt 0 || "$_rejected" -gt 0 \
      || "$_unevaluated" -gt 0 || "$_total" -eq 0 \
      || "${#_silent_categories[@]}" -gt 0 || "${#_unmapped_categories[@]}" -gt 0 \
      || "${#MISSING_DEFAULT_WRAPPERS[@]}" -gt 0 ]]; then
  PHASE_RESULT="FAIL"
fi

append_phase_result "$RUN_ID" "test" "$PHASE_RESULT" "$DURATION" "$PHASE_DIR"

if [[ "$PHASE_RESULT" == "FAIL" ]]; then
  echo ""
  if [[ "$_total" -eq 0 ]]; then
    echo "phase-02-test: FAILED — ${#DISPATCHED_NAMES[@]} wrapper(s) were dispatched and every one exited 0, but they produced ZERO Evidence Records between them. An empty test phase proves nothing: there is no evidence here to have passed. Check each wrapper's log under ${PHASE_DIR} — a wrapper exiting 0 without writing a single record is a bug in that wrapper, not a passing run." >&2
  fi
  if [[ "${#MISSING_DEFAULT_WRAPPERS[@]}" -gt 0 ]]; then
    echo "phase-02-test: FAILED — ${#MISSING_DEFAULT_WRAPPERS[@]} test-category wrapper(s) resolved to their in-tree default path but do not exist on disk, so those categories were never dispatched and this run tested nothing of what they cover:" >&2
    for w in "${MISSING_DEFAULT_WRAPPERS[@]}"; do echo "    - ${w}" >&2; done
    echo "  → Examined: ${#DISPATCHED_NAMES[@]} dispatched categor(y/ies) (${DISPATCHED_NAMES[*]:-none}); expected every wrapper script named above to be present in $SCRIPT_DIR." >&2
    echo "  → A category that silently vanishes is worse than a failing one: the run summary still looks complete. This file's own history records exactly that (constitutional-gate-sweep existed, was functional, and was never dispatched)." >&2
    echo "  → Do: restore the missing script (git checkout -- <path>), or, if the category was deliberately retired, remove its _dispatch call and its CATEGORY_EVIDENCE_DIRS entry in the same change." >&2
  fi
  if [[ "${#_unmapped_categories[@]}" -gt 0 ]]; then
    echo "phase-02-test: FAILED — ${#_unmapped_categories[@]} dispatched categor(y/ies) have no CATEGORY_EVIDENCE_DIRS entry, so the per-category evidence floor cannot state what they were expected to produce: ${_unmapped_categories[*]}" >&2
    echo "  → Do: add each name to CATEGORY_EVIDENCE_DIRS near the top of this script, mapping it to the Evidence Record directory its wrapper writes. Exempting it instead would move this very defect into the floor." >&2
  fi
  if [[ "${#_silent_categories[@]}" -gt 0 ]]; then
    echo "phase-02-test: FAILED — ${#_silent_categories[@]} of ${#DISPATCHED_NAMES[@]} dispatched test categor(y/ies) exited 0 having produced ZERO Evidence Records, so nothing they cover was actually proven by this run:" >&2
    for c in "${_silent_categories[@]}"; do echo "    - ${c}" >&2; done
    echo "  → Examined: ${_total} Evidence Record(s) across ${#DISPATCHED_NAMES[@]} dispatched categor(y/ies) (${DISPATCHED_NAMES[*]}); expected at least one record from EACH." >&2
    echo "  → A non-empty total is not per-category evidence: one category's records let a silent one pass unnoticed, which is the §6.AK \"C00-only gate\" shape at the phase-02 layer. Every wrapper here carries its own zero-record refusal, so a wrapper exiting 0 with nothing written is a bug in that wrapper, not a passing run." >&2
    echo "  → Do: read that category's log under ${PHASE_DIR}/<category>.log and fix the wrapper (or its inputs) so it writes records or fails honestly." >&2
  fi
  if [[ "$_unevaluated" -gt 0 ]]; then
    echo "phase-02-test: FAILED — ${_unevaluated} Evidence Record(s) carry no anti-bluff verdict at all (neither \"validated\" nor \"REJECTED: ...\"). A record nobody evaluated is not a record that passed; treating the absence of a validation as its presence is the bluff class §6.J, §6.Z clause 4 and §6.AK all exist to refuse." >&2
  fi
  echo "phase-02-test: FAILED — any_wrapper_nonzero=${_any_wrapper_nonzero} fail=${_fail} rejected=${_rejected} unevaluated=${_unevaluated} evidence_records=${_total} independently_revalidated=${_revalidated} silent_categories=${#_silent_categories[@]} unmapped_categories=${#_unmapped_categories[@]} missing_default_wrappers=${#MISSING_DEFAULT_WRAPPERS[@]}" >&2
  exit 1
fi

echo ""
echo "phase-02-test: PASSED — all ${#DISPATCHED_NAMES[@]} dispatched wrapper(s) exited 0 and EACH produced at least one Evidence Record (${DISPATCHED_NAMES[*]}), ${_total} Evidence Records scanned and all ${_revalidated} independently anti-bluff re-validated, 0 FAIL, 0 REJECTED, 0 UNEVALUATED (${_skipped} SKIPPED, which does not block PASS)"
exit 0

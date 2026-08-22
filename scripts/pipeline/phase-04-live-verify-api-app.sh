#!/usr/bin/env bash
# scripts/pipeline/phase-04-live-verify-api-app.sh — FR-008 live-verification
# phase, :api-app-on-emulator half ONLY (tasks.md T037).
#
# This is the sibling of scripts/pipeline/phase-04-live-verify-api.sh. That
# script proves the standalone lava-api-go SERVICE genuinely answers real
# HTTP requests; it deliberately never touches Gradle, ADB, or any emulator
# (see its own header for why the two halves are separate scripts). THIS
# script is the other half: it takes the REAL :api-app debug APK artifact
# produced by phase-01, installs it onto a REAL cold-booted Android emulator
# brought up by the Containers submodule, and drives the REAL Compose UI
# Challenge that starts the on-device API embed and then — as a genuine HTTPS
# peer — asserts the embed actually SERVES (real status codes + real response
# bodies + a real auth-gate verdict) on the device.
#
# Until this script existed, a green `live_verify` phase meant only "the Go
# API is live" and said NOTHING about :api-app. That gap is what T037 closes.
#
# ---------------------------------------------------------------------------
# Why this is a DIFFERENT surface from phase-02's build-time Challenge pass
# ---------------------------------------------------------------------------
# phase-02-test-challenge.sh runs the WHOLE discovered Challenge suite for
# both modules as a build-time test pass, and its records live under
# `<run>/phase-02/real-device-challenge/`. THIS phase is narrower and later:
# it is scoped to the boot-and-serve Challenge specifically, it runs AFTER
# phase-03's install/boot step, it asserts on the artifact-under-distribution
# being installed on a device and serving, and its Evidence Records live in
# `<run>/phase-04/` — a different directory, appended to report.json under
# the "live_verify" phase name, never "test". A phase-02 pass proves the
# suite is green; a phase-04 pass proves the shipped artifact, installed on a
# real device, boots and serves. Those are genuinely different claims, and
# §6.Z's forensic anchor (a distribute green-lit by a test pass that never
# exercised the shipped behaviour) is exactly why they must not be conflated.
#
# ---------------------------------------------------------------------------
# Constitutional posture — §6.AH / §6.AG / §6.X (read the real code, not
# assumed)
# ---------------------------------------------------------------------------
# §6.AH: every virtual device MUST run inside a Container or VM; host-direct
# emulator execution is FORBIDDEN, and there is NO live-device fallback.
# §6.AG: a physical ADB-attached device is NEVER a target — it is presumed in
# use by other projects.
#
# This script does NOT reimplement emulator orchestration (Decoupled Reusable
# Architecture rule). It delegates to the already-existing, already-used
# scripts/run-api-app-challenge-matrix.sh, which is itself thin glue over the
# Containers submodule's submodules/containers/cmd/emulator-matrix CLI. What
# that path really does, confirmed by reading the real Go source rather than
# assuming:
#   - submodules/containers/pkg/emulator/containerized.go `Boot` runs the
#     emulator process INSIDE a podman/docker container (image ref passed via
#     --container-image), with the ADB console port forwarded to the host.
#   - `Install` (containerized.go:706) shells out to the real Android Debug
#     Bridge binary with `-s <target> install -r <apkPath>`, and it REFUSES to
#     continue unless that command's own output reports Success
#     (containerized.go:719). It is invoked from pkg/emulator/matrix.go:267
#     BEFORE the test runs, and a failure there produces a row whose
#     test_error begins "install failed:" with test_passed=false. So an
#     attestation row with test_passed=true is itself independent proof the
#     APK genuinely installed onto the device.
#   - `RunInstrumentation` (containerized.go:730) runs, ON THE HOST,
#     `ANDROID_SERIAL=localhost:<forwarded-port> ./gradlew
#     :<module>:connectedDebugAndroidTest -P...class=<classes>`. That is why
#     Gradle's own authoritative per-testcase JUnit XML lands on the HOST at
#     <module>/build/outputs/androidTest-results/connected/**/TEST-*.xml even
#     though the emulator itself is inside the container — which is what lets
#     this script cross-check the run against a source of truth that is NOT
#     the matrix runner's own self-report.
#
# On top of delegating, this script gathers its own §6.AH/§6.AG PROOF rather
# than trusting a log line: while the matrix run is in flight, a background
# poller samples `<runtime> ps --filter name=lava-emu` and the device list
# every few seconds into a raw evidence file. A run whose emulator really was
# containerized leaves timestamped rows naming a real running container; a
# run that silently fell back to a host-direct emulator would leave none. The
# provenance Evidence Record this script writes FAILS when that proof is
# absent, so "§6.AH-compliant" is a checked fact here, not a claim.
#
# ---------------------------------------------------------------------------
# Which artifact is installed
# ---------------------------------------------------------------------------
# `api-app/build/outputs/apk/debug/api-app-debug.apk` — the exact path
# scripts/run-api-app-challenge-matrix.sh passes to the Containers CLI's
# `--apk` flag, and the exact path phase-01-build-android.sh publishes as its
# "api-app-debug" build artifact (phase-01-build-android.sh:191).
#
# Honest note on the task wording: tasks.md T037 says "the T018 debug APK",
# but T018 is phase-01's :api-app *release* build step and T017 is the
# :api-app *debug* step. The debug APK is what is actually meant and what is
# actually installable here, because androidTest instrumentation APKs are
# built and signed against the debug variant — `connectedDebugAndroidTest`
# cannot drive a release APK. This script therefore verifies the debug
# artifact and says so plainly rather than papering over the numbering slip.
#
# This script passes --no-build to the matrix script, so no APK is rebuilt by
# THAT script; the artifact on disk (phase-01's output) is what gets
# installed. Gradle's own connectedDebugAndroidTest task will still bring the
# debug + androidTest APKs up to date with the current source tree before
# instrumenting — that is Gradle's normal behaviour, it is honest (it means
# the device runs the CURRENT source, never a stale binary), and the sha256
# this script records is captured AFTER the run, so the recorded hash is the
# hash of the APK that was really on the device.
#
# ---------------------------------------------------------------------------
# AVD matrix scope (real host constraint, honestly reported)
# ---------------------------------------------------------------------------
# The §6.AE.2 gate matrix is API 28/30/34/latest x phone/tablet. On THIS host
# only ghcr.io/vasic-digital/lava-android-emulator:api34-x86_64 is cached, and
# anonymous ghcr.io pulls for the others are refused (HTTP 403) — a real
# registry-access precondition gap, not a code defect. The Containers CLI's
# image preflight aborts the WHOLE run if ANY requested AVD's image is
# missing, so this script selects the FIRST candidate API level whose image is
# genuinely present locally (checked with a real `<runtime> image inspect`,
# never assumed) and scopes --avds to that one phone AVD. That is the
# mechanism run-challenge-matrix.sh's own header documents for this exact
# situation, not a workaround invented here. When none are cached, the lowest
# candidate is still attempted for real so the genuine pull failure becomes
# this run's diagnostic instead of a guess. This phase is a live-verification
# of the shipped artifact, NOT the §6.AE.2 release gate — that full matrix
# obligation is unchanged and still owed at tag time.
#
# ---------------------------------------------------------------------------
# Evidence Records written (both under <run>/phase-04/)
# ---------------------------------------------------------------------------
#   1. hermetic-script / "api-app-live-verify-install-and-runner-provenance"
#      — the §6.AH/§6.AG proof: the real sha256 + byte size of the APK that
#      was installed, the real AVD/api-level/runtime-sdk/device/adb-state the
#      Containers attestation recorded, the real container name+image observed
#      RUNNING during the run by this script's own poller, and confirmation
#      that no physical device was ever a target. FAILs if the container proof
#      is absent (i.e. if the emulator was not demonstrably containerized), or
#      if the attestation reports an install failure.
#   2. real-device-challenge / "<FQCN>" per verified Challenge class — PASS
#      only when BOTH the freshly-written host-side Gradle JUnit XML shows the
#      class's testcases with no <failure>/<error> AND the Containers
#      attestation row for it reports test_passed=true. Disagreement between
#      those two independent sources is itself a FAIL (a matrix runner that
#      claimed green while Gradle's own report showed a failure would be
#      precisely the bluff FR-004 exists to catch). The raw_output_ref file
#      embeds the class's own FALSIFIABILITY REHEARSAL KDoc block verbatim
#      from its .kt source — never fabricated — which is also what satisfies
#      anti-bluff-validate.sh's Rule 4 for this category.
#
# Directory-depth constraint (real integration fact, verified in
# lib/run-report.sh:555): finalize_run_report's evidence aggregator selects
# records with `find <phase_dir> -mindepth 2 -maxdepth 2 -name '*.json'`. Any
# stray .json at exactly depth 2 under phase-04 would be miscounted as an
# Evidence Record. This script therefore keeps ALL of its raw output — and the
# matrix runner's own real-device-verification.json / host-preflight.json —
# at depth 3 or deeper, under "<category>/raw/".
#
# ---------------------------------------------------------------------------
# §6.R (no hardcoding): this script contains no IPv4 literal, no host:port
# literal, and no UUID literal. The emulator container image reference is
# built from the API level actually chosen at runtime, the AVD name follows
# the same "CZ_API<N>_Phone:<N>:phone" convention phase-02-test-challenge.sh
# already uses, and the Challenge class under verification is DISCOVERED from
# the real files on disk (their own `package` line + filename), never
# hardcoded as an FQCN string that could silently drift from the source tree.
#
# §6.U: no sudo/su anywhere in this script or anything it invokes.
#
# ---------------------------------------------------------------------------
# Usage
# ---------------------------------------------------------------------------
#   scripts/pipeline/phase-04-live-verify-api-app.sh <run_id> [repo-path]
#
# <run_id> MUST already have a report.json (via lib/run-report.sh's
# init_run_report). This script appends to that same report.json under the
# phase name "live_verify" — the SAME name its lava-api-go sibling uses. Both
# entries legitimately coexist in phases[] (append_phase_result adds one entry
# per call and the schema allows repeated phase names); together they are the
# two halves of FR-008, and a reader can tell them apart by the evidence_dir
# and by the records inside it.
#
# Environment overrides (all optional; defaults are the real gate behaviour):
#   LAVA_PIPELINE_LIVE_VERIFY_API_APP_TEST_CLASSES
#       Comma-separated FQCN list, intersected with what is really discovered
#       on disk (a class named here that has no matching file is NOT invented).
#       Default: the discovered boot-and-serve Challenge class.
#   LAVA_PIPELINE_LIVE_VERIFY_API_APP_CONTAINER_RUNTIME   podman|docker
#       Default: podman (matching the matrix script's own default).
#   LAVA_PIPELINE_LIVE_VERIFY_API_APP_TIMEOUT_SECONDS
#       Hard outer bound on the whole matrix invocation. Default: 2700 (45m).
#       On expiry the invocation is killed, teardown of any surviving
#       lava-emu-* container is attempted, and the phase reports the REAL
#       timeout — never a fabricated pass.
#   LAVA_PIPELINE_LIVE_VERIFY_API_APP_BOOT_TIMEOUT
#       Forwarded to the matrix script's --boot-timeout. Default: 10m.
#
# WARNING — REAL side effects: this script boots a real emulator container,
# puts a real APK onto it, and runs a real Gradle instrumentation task on this
# host. It does NOT touch any physical device, and it does not start, stop, or
# modify the lava-api-go systemd service its sibling verifies.
#
# Exit codes:
#   0 - every Evidence Record this script wrote is PASS (or a legitimately
#       honest SKIPPED) AND every record was anti-bluff-validated.
#   1 - a real failure: the emulator/Challenge run genuinely failed, the two
#       independent sources disagreed, the §6.AH container proof was absent,
#       the APK artifact was missing, or any record was REJECTED by
#       anti-bluff-validate.sh. Recorded as FAIL in report.json either way —
#       never fabricated as success.
#   2 - usage/precondition error (missing run_id, report.json absent, a
#       required tool or the matrix script missing, or zero Challenge classes
#       discoverable).
#
# Classification: project-specific (Lava's :api-app module, its Challenge
# suite, and this pipeline's evidence layout; the delegate-to-Containers
# emulator orchestration it reuses is universal per §6.X).

set -uo pipefail
# Deliberately NOT `set -e`, for the same reason the lava-api-go sibling
# gives: the matrix script's own non-zero exits (host-gap, image preflight,
# a genuine test failure) are the REAL signal this phase exists to observe and
# record honestly — not a script bug to abort on. Every risky step below is
# explicitly guarded.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# shellcheck source=scripts/pipeline/lib/run-report.sh
source "$SCRIPT_DIR/lib/run-report.sh"
# shellcheck source=scripts/pipeline/lib/evidence.sh
source "$SCRIPT_DIR/lib/evidence.sh"
# shellcheck source=scripts/pipeline/lib/anti-bluff-validate.sh
source "$SCRIPT_DIR/lib/anti-bluff-validate.sh"

RUN_ID="${1:-}"
REPO_PATH_OVERRIDE="${2:-}"

if [[ -z "$RUN_ID" ]]; then
  echo "phase-04-live-verify-api-app: usage: $0 <run_id> [repo-path]" >&2
  exit 2
fi

REPO_PATH="${REPO_PATH_OVERRIDE:-$REPO_ROOT}"
if ! cd "$REPO_PATH"; then
  echo "phase-04-live-verify-api-app: precondition failed — cannot cd into repo path '$REPO_PATH'" >&2
  exit 2
fi
# From here on every path is repo-root-relative, matching lib/run-report.sh's
# own _run_report_path convention (which is CWD-relative) and the sibling
# phase-04 script's path shapes.

REPORT_PATH=".lava-ci-evidence/pipeline-runs/${RUN_ID}/report.json"
if [[ ! -f "$REPORT_PATH" ]]; then
  echo "phase-04-live-verify-api-app: precondition failed — $REPORT_PATH does not exist (call init_run_report first)" >&2
  exit 2
fi

for tool in jq python3; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "phase-04-live-verify-api-app: precondition failed — required tool '$tool' not found on PATH" >&2
    exit 2
  fi
done

MATRIX_SCRIPT="scripts/run-api-app-challenge-matrix.sh"
if [[ ! -f "$MATRIX_SCRIPT" ]]; then
  echo "phase-04-live-verify-api-app: precondition failed — ${MATRIX_SCRIPT} not found" >&2
  exit 2
fi

CONTAINER_RUNTIME="${LAVA_PIPELINE_LIVE_VERIFY_API_APP_CONTAINER_RUNTIME:-podman}"
if ! command -v "$CONTAINER_RUNTIME" >/dev/null 2>&1; then
  echo "phase-04-live-verify-api-app: precondition failed — container runtime '$CONTAINER_RUNTIME' not found on PATH (§6.AH requires a container/VM emulator; there is no host-direct fallback)" >&2
  exit 2
fi

OUTER_TIMEOUT_SECONDS="${LAVA_PIPELINE_LIVE_VERIFY_API_APP_TIMEOUT_SECONDS:-2700}"
BOOT_TIMEOUT="${LAVA_PIPELINE_LIVE_VERIFY_API_APP_BOOT_TIMEOUT:-10m}"

PHASE_DIR=".lava-ci-evidence/pipeline-runs/${RUN_ID}/phase-04"
CHALLENGE_RAW_DIR="${PHASE_DIR}/real-device-challenge/raw"
SCRIPT_RAW_DIR="${PHASE_DIR}/hermetic-script/raw"
mkdir -p "$CHALLENGE_RAW_DIR" "$SCRIPT_RAW_DIR"

SUMMARY_LOG="${SCRIPT_RAW_DIR}/live-verify-api-app-summary.log"
: > "$SUMMARY_LOG"

_log() {
  echo "$*" | tee -a "$SUMMARY_LOG"
}

START_TS=$(date +%s)

_log "phase-04-live-verify-api-app: repo=${REPO_PATH}"
_log "phase-04-live-verify-api-app: run_id=${RUN_ID}"
_log "phase-04-live-verify-api-app: container_runtime=${CONTAINER_RUNTIME}"
_log "phase-04-live-verify-api-app: outer timeout=${OUTER_TIMEOUT_SECONDS}s, boot timeout=${BOOT_TIMEOUT}"
_log ""

OVERALL_OK="true"

declare -a RECORD_PATHS=()
declare -a RECORD_STATUSES=()
declare -a RECORD_RESULTS=()

# emit_record <test_id> <category> <command> <result> <assertion_summary> <raw_file>
# Writes the record, anti-bluff-validates it, and accumulates the outcome.
emit_record() {
  local test_id="$1" category="$2" command_str="$3" result="$4" summary="$5" raw_file="$6"
  local record_path=""
  if ! record_path="$(write_evidence_record "$PHASE_DIR" "$test_id" "$category" "$command_str" "$result" "$summary" "$raw_file")"; then
    echo "phase-04-live-verify-api-app: ERROR — write_evidence_record failed for ${test_id}" >&2
    OVERALL_OK="false"
    return 1
  fi
  RECORD_PATHS+=("$record_path")
  RECORD_RESULTS+=("$result")
  if [[ "$result" == "FAIL" ]]; then
    OVERALL_OK="false"
  fi
  if validate_evidence_record "$record_path" >/dev/null 2>&1; then
    RECORD_STATUSES+=("validated")
    _log "phase-04-live-verify-api-app: [${result}] ${test_id} — Evidence Record anti_bluff_status=validated (${record_path})"
  else
    local status
    status="$(jq -r '.anti_bluff_status' "$record_path" 2>/dev/null || echo 'REJECTED: unknown')"
    RECORD_STATUSES+=("$status")
    OVERALL_OK="false"
    _log "phase-04-live-verify-api-app: [${result}] ${test_id} — Evidence Record REJECTED: ${status} (${record_path})"
  fi
  return 0
}

# ---------------------------------------------------------------------------
# Step 1: §6.AG real check — no physical device is ever a target.
# ---------------------------------------------------------------------------
_log "=== Step 1: §6.AG live-device check (real command output, not assumed) ==="
LIVE_DEVICES=""
if command -v adb >/dev/null 2>&1; then
  LIVE_DEVICES="$(adb devices 2>/dev/null | tail -n +2 | grep -v '^[[:space:]]*$' || true)"
  if [[ -n "$LIVE_DEVICES" ]]; then
    _log "phase-04-live-verify-api-app: NOTE — the device list reports attached device(s). Per §6.AG they are reserved for other work and are NEVER a target here; the Containers CLI only ever drives the emulator it booted itself, addressed by its own forwarded port:"
    echo "$LIVE_DEVICES" | sed 's/^/    /' | tee -a "$SUMMARY_LOG"
  else
    _log "phase-04-live-verify-api-app: the device list reports ZERO attached devices — the only target this run can possibly use is the emulator the Containers submodule boots"
  fi
else
  _log "phase-04-live-verify-api-app: 'adb' not on PATH; the Containers CLI uses its own SDK bridge binary for its own emulator regardless"
fi
_log ""

# ---------------------------------------------------------------------------
# Step 2: locate the REAL artifact under verification.
# ---------------------------------------------------------------------------
_log "=== Step 2: the :api-app debug artifact under live-verification ==="
APK_PATH="api-app/build/outputs/apk/debug/api-app-debug.apk"
APK_PRESENT="false"
if [[ -f "$APK_PATH" ]]; then
  APK_PRESENT="true"
  _log "phase-04-live-verify-api-app: artifact present: ${APK_PATH} ($(stat -c %s "$APK_PATH") bytes, mtime $(date -u -r "$APK_PATH" +%Y-%m-%dT%H:%M:%SZ))"
else
  _log "phase-04-live-verify-api-app: artifact MISSING at ${APK_PATH} — phase-01's :api-app debug build step has not produced it"
fi
_log ""

# ---------------------------------------------------------------------------
# Step 3: discover the Challenge class(es) to verify (never a hardcoded FQCN).
# ---------------------------------------------------------------------------
_log "=== Step 3: discover the boot-and-serve Challenge class from the real source tree ==="
CHALLENGE_DIR="api-app/src/androidTest/kotlin/lava/api/app/challenges"
MANIFEST_ALL="${CHALLENGE_RAW_DIR}/_discovered.tsv"
: > "$MANIFEST_ALL"
if [[ -d "$CHALLENGE_DIR" ]]; then
  while IFS= read -r f; do
    [[ -z "$f" ]] && continue
    pkg="$(grep -m1 -E '^package ' "$f" 2>/dev/null | awk '{print $2}')"
    base="$(basename "$f" .kt)"
    [[ -n "$pkg" ]] && printf '%s.%s\t%s\n' "$pkg" "$base" "$f"
  done < <(find "$CHALLENGE_DIR" -maxdepth 1 -name 'Challenge*Test.kt' 2>/dev/null | sort) >> "$MANIFEST_ALL"
fi

MANIFEST="${CHALLENGE_RAW_DIR}/_selected.tsv"
: > "$MANIFEST"
OVERRIDE_CLASSES="${LAVA_PIPELINE_LIVE_VERIFY_API_APP_TEST_CLASSES:-}"
if [[ -n "$OVERRIDE_CLASSES" ]]; then
  WANTED="$(printf '%s' "$OVERRIDE_CLASSES" | tr ',' '\n' | sed '/^[[:space:]]*$/d')"
  while IFS=$'\t' read -r fqcn path; do
    [[ -z "$fqcn" ]] && continue
    # Herestring, not a pipe — see the SIGPIPE/pipefail note in
    # scripts/pipeline/lib/anti-bluff-validate.sh.
    if grep -qxF "$fqcn" <<< "$WANTED"; then
      printf '%s\t%s\n' "$fqcn" "$path" >> "$MANIFEST"
    fi
  done < "$MANIFEST_ALL"
  _log "phase-04-live-verify-api-app: LAVA_PIPELINE_LIVE_VERIFY_API_APP_TEST_CLASSES override active"
else
  # Default: the boot-and-serve Challenge, matched on its own filename rather
  # than a hardcoded FQCN so a rename in the source tree is discovered, not
  # silently missed. `BootAndServe` is the naming this project already uses
  # (Challenge02ApiAppBootAndServeTest.kt).
  grep -i 'BootAndServe' "$MANIFEST_ALL" >> "$MANIFEST" || true
fi

SELECTED_COUNT=$(wc -l < "$MANIFEST" | tr -d '[:space:]')
DISCOVERED_COUNT=$(wc -l < "$MANIFEST_ALL" | tr -d '[:space:]')
_log "phase-04-live-verify-api-app: ${DISCOVERED_COUNT} Challenge class(es) discovered under ${CHALLENGE_DIR}; ${SELECTED_COUNT} selected for live-verification:"
cut -f1 "$MANIFEST" | sed 's/^/    /' | tee -a "$SUMMARY_LOG"

if [[ "$SELECTED_COUNT" -eq 0 ]]; then
  echo "phase-04-live-verify-api-app: precondition failed — zero boot-and-serve Challenge class(es) selected (discovered ${DISCOVERED_COUNT} total under ${CHALLENGE_DIR})" >&2
  exit 2
fi
TEST_CLASS_ARG="$(cut -f1 "$MANIFEST" | paste -sd, -)"
_log ""

# ---------------------------------------------------------------------------
# Step 4: choose a real, locally-available emulator container image.
# ---------------------------------------------------------------------------
_log "=== Step 4: choose a real, locally-present emulator container image ==="
CANDIDATE_APIS=(34 36 30 28)
CHOSEN_API=""
IMAGE_PRESENCE_LOG="${SCRIPT_RAW_DIR}/image-presence.log"
: > "$IMAGE_PRESENCE_LOG"
for api in "${CANDIDATE_APIS[@]}"; do
  ref="ghcr.io/vasic-digital/lava-android-emulator:api${api}-x86_64"
  if "$CONTAINER_RUNTIME" image inspect "$ref" >/dev/null 2>&1; then
    echo "PRESENT   ${ref}" >> "$IMAGE_PRESENCE_LOG"
    [[ -z "$CHOSEN_API" ]] && CHOSEN_API="$api"
  else
    echo "ABSENT    ${ref}" >> "$IMAGE_PRESENCE_LOG"
  fi
done
sed 's/^/    /' "$IMAGE_PRESENCE_LOG" | tee -a "$SUMMARY_LOG"
if [[ -z "$CHOSEN_API" ]]; then
  CHOSEN_API="${CANDIDATE_APIS[-1]}"
  _log "phase-04-live-verify-api-app: none cached locally — attempting api${CHOSEN_API} for real so its genuine pull failure becomes this run's diagnostic"
fi
AVD_SPEC="CZ_API${CHOSEN_API}_Phone:${CHOSEN_API}:phone"
CONTAINER_IMAGE_REF="ghcr.io/vasic-digital/lava-android-emulator:api${CHOSEN_API}-x86_64"
_log "phase-04-live-verify-api-app: AVD for this run: ${AVD_SPEC} (image: ${CONTAINER_IMAGE_REF})"
_log ""

MATRIX_EVIDENCE_DIR="${CHALLENGE_RAW_DIR}/matrix-run"
RUN_LOG="${CHALLENGE_RAW_DIR}/matrix-invocation.log"
TARGET_PROOF_LOG="${SCRIPT_RAW_DIR}/live-target-proof.log"
MARKER_FILE="${CHALLENGE_RAW_DIR}/.freshness-marker"

RERUN_COMMAND="LAVA_PIPELINE_LIVE_VERIFY_API_APP_TEST_CLASSES=\"${TEST_CLASS_ARG}\" scripts/pipeline/phase-04-live-verify-api-app.sh ${RUN_ID}"
MATRIX_COMMAND="bash ${MATRIX_SCRIPT} --no-build --avds \"${AVD_SPEC}\" --test-class \"${TEST_CLASS_ARG}\" --container-image \"${CONTAINER_IMAGE_REF}\" --container-runtime \"${CONTAINER_RUNTIME}\" --boot-timeout \"${BOOT_TIMEOUT}\" --evidence-dir \"${MATRIX_EVIDENCE_DIR}\""

MATRIX_RC=""
TIMED_OUT="false"

if [[ "$APK_PRESENT" != "true" ]]; then
  _log "=== Step 5: SKIPPED the emulator run — the artifact under verification does not exist ==="
else
  # -------------------------------------------------------------------------
  # Step 5: run it for real, with a bounded outer timeout and a live
  # §6.AH/§6.AG proof poller.
  # -------------------------------------------------------------------------
  _log "=== Step 5: REAL containerized emulator run (put the APK on the device + drive the Challenge) ==="
  _log "\$ ${MATRIX_COMMAND}"
  mkdir -p "$MATRIX_EVIDENCE_DIR"
  touch "$MARKER_FILE"

  {
    echo "# Live target proof — sampled by phase-04-live-verify-api-app.sh WHILE the"
    echo "# matrix run was in flight. Each block is one real sample of what was"
    echo "# actually running (§6.AH container proof) and what the bridge could"
    echo "# actually see (§6.AG no-physical-device proof)."
    echo "# runtime: ${CONTAINER_RUNTIME}"
  } > "$TARGET_PROOF_LOG"

  (
    while :; do
      ts="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
      ps_out="$("$CONTAINER_RUNTIME" ps --filter name=lava-emu --format '{{.Names}} | image={{.Image}} | status={{.Status}} | ports={{.Ports}}' 2>/dev/null || true)"
      adb_out="$(adb devices -l 2>/dev/null | tail -n +2 | grep -v '^[[:space:]]*$' || true)"
      {
        echo "--- ${ts} ---"
        if [[ -n "$ps_out" ]]; then
          echo "${CONTAINER_RUNTIME} ps (name~lava-emu):"
          echo "$ps_out" | sed 's/^/  /'
        else
          echo "${CONTAINER_RUNTIME} ps (name~lava-emu): <none running at this instant>"
        fi
        if [[ -n "$adb_out" ]]; then
          echo "device list:"
          echo "$adb_out" | sed 's/^/  /'
        else
          echo "device list: <no devices at this instant>"
        fi
      } >> "$TARGET_PROOF_LOG"
      sleep 10
    done
  ) &
  POLLER_PID=$!

  timeout --signal=TERM --kill-after=60 "$OUTER_TIMEOUT_SECONDS" \
    bash "$MATRIX_SCRIPT" \
      --no-build \
      --avds "$AVD_SPEC" \
      --test-class "$TEST_CLASS_ARG" \
      --container-image "$CONTAINER_IMAGE_REF" \
      --container-runtime "$CONTAINER_RUNTIME" \
      --boot-timeout "$BOOT_TIMEOUT" \
      --evidence-dir "$MATRIX_EVIDENCE_DIR" \
      > "$RUN_LOG" 2>&1
  MATRIX_RC=$?

  kill "$POLLER_PID" 2>/dev/null || true
  wait "$POLLER_PID" 2>/dev/null || true

  if [[ "$MATRIX_RC" -eq 124 || "$MATRIX_RC" -eq 137 ]]; then
    TIMED_OUT="true"
    _log "phase-04-live-verify-api-app: the matrix invocation exceeded the ${OUTER_TIMEOUT_SECONDS}s outer bound and was killed (exit ${MATRIX_RC})"
  fi
  _log "phase-04-live-verify-api-app: ${MATRIX_SCRIPT} exited ${MATRIX_RC}"
  _log "phase-04-live-verify-api-app: run log: ${RUN_LOG}"
fi
_log ""

# ---------------------------------------------------------------------------
# Step 6: orphan reaping — never leave an emulator container behind.
# ---------------------------------------------------------------------------
_log "=== Step 6: cleanup — reap any surviving lava-emu-* container ==="
REAP_LOG="${SCRIPT_RAW_DIR}/cleanup.log"
{
  echo "post-run ${CONTAINER_RUNTIME} ps -a (name~lava-emu):"
  "$CONTAINER_RUNTIME" ps -a --filter name=lava-emu --format '{{.Names}} | {{.Image}} | {{.Status}}' 2>/dev/null || true
} > "$REAP_LOG"
SURVIVORS="$("$CONTAINER_RUNTIME" ps -a --filter name=lava-emu --format '{{.Names}}' 2>/dev/null || true)"
if [[ -n "$SURVIVORS" ]]; then
  while IFS= read -r name; do
    [[ -z "$name" ]] && continue
    _log "phase-04-live-verify-api-app: reaping orphaned emulator container '${name}' (this run's own; the Containers CLI names them lava-emu-*)"
    "$CONTAINER_RUNTIME" rm -f "$name" >> "$REAP_LOG" 2>&1 || true
  done <<< "$SURVIVORS"
else
  _log "phase-04-live-verify-api-app: no lava-emu-* container survived the run (the Containers CLI tore its own container down)"
fi
{
  echo ""
  echo "after reaping, ${CONTAINER_RUNTIME} ps -a (name~lava-emu):"
  "$CONTAINER_RUNTIME" ps -a --filter name=lava-emu --format '{{.Names}} | {{.Image}} | {{.Status}}' 2>/dev/null || echo "<none>"
} >> "$REAP_LOG"
_log ""

# ---------------------------------------------------------------------------
# Step 7: build the Evidence Records from the REAL artifacts of the run.
# ---------------------------------------------------------------------------
_log "=== Step 7: Evidence Records from the real artifacts of this run ==="

ATTESTATION="${MATRIX_EVIDENCE_DIR}/real-device-verification.json"
HOST_PREFLIGHT="${MATRIX_EVIDENCE_DIR}/host-preflight.json"
XML_SEARCH_DIR="api-app/build/outputs/androidTest-results/connected"

PARSED_JSONL="${CHALLENGE_RAW_DIR}/_parsed.jsonl"
: > "$PARSED_JSONL"

python3 - \
  "$REPO_PATH" "$CHALLENGE_RAW_DIR" "$SCRIPT_RAW_DIR" "$MANIFEST" \
  "$ATTESTATION" "$HOST_PREFLIGHT" "$RUN_LOG" "$TARGET_PROOF_LOG" \
  "$XML_SEARCH_DIR" "$MARKER_FILE" "$APK_PATH" "$AVD_SPEC" \
  "$CONTAINER_IMAGE_REF" "$CONTAINER_RUNTIME" "${MATRIX_RC:-}" "$TIMED_OUT" \
  "$APK_PRESENT" "$OUTER_TIMEOUT_SECONDS" "$MATRIX_COMMAND" "$RERUN_COMMAND" \
  "${LIVE_DEVICES:-}" \
  > "$PARSED_JSONL" <<'PYEOF'
import glob
import hashlib
import json
import os
import re
import sys

try:
    import defusedxml.ElementTree as ET
except ImportError:  # pragma: no cover - stdlib fallback
    import xml.etree.ElementTree as ET

(repo_path, challenge_raw_dir, script_raw_dir, manifest_file, attestation_path,
 host_preflight_path, run_log, target_proof_log, xml_search_dir, marker_file,
 apk_path, avd_spec, container_image_ref, container_runtime, matrix_rc,
 timed_out, apk_present, outer_timeout, matrix_command, rerun_command,
 live_devices) = sys.argv[1:22]

_WS_RE = re.compile(r"\s+")
_MARKER_RE = re.compile(r"FALSIFIABILITY[ \t]+REHEARSAL|§6\.AB-discrimination:")


def one_line(s, maxlen=1400):
    if not s:
        return ""
    s = s.replace("\r", " ").replace("\n", " | ")
    s = _WS_RE.sub(" ", s).strip()
    if len(s) > maxlen:
        s = s[:maxlen] + "...(truncated)"
    return s


def read_text(path, limit=None):
    if not path or not os.path.isfile(path):
        return ""
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        data = fh.read()
    if limit and len(data) > limit:
        return data[-limit:]
    return data


def load_json(path):
    if not path or not os.path.isfile(path):
        return None
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            return json.load(fh)
    except Exception:
        return None


def extract_marker_block(kt_path):
    try:
        with open(kt_path, "r", encoding="utf-8", errors="replace") as fh:
            lines = fh.readlines()
    except OSError as exc:
        return None, "(could not read source file %s: %s)" % (kt_path, exc)
    start = None
    for i, ln in enumerate(lines):
        if _MARKER_RE.search(ln):
            start = i
            break
    if start is None:
        return None, None
    end = len(lines)
    for j in range(start, len(lines)):
        if "*/" in lines[j]:
            end = j + 1
            break
    return "".join(lines[start:end]), None


records = []
attestation = load_json(attestation_path)
preflight = load_json(host_preflight_path)
run_log_text = read_text(run_log)
proof_text = read_text(target_proof_log)

rows = (attestation or {}).get("rows") or []
row_by_class = {}
for row in rows:
    for cls in (row.get("test_class") or "").split(","):
        cls = cls.strip()
        if cls:
            row_by_class[cls] = row

# ---------------------------------------------------------------------------
# Record 1 — §6.AH/§6.AG install + runner provenance (category hermetic-script)
# ---------------------------------------------------------------------------
apk_sha = ""
apk_size = 0
if apk_present == "true" and os.path.isfile(apk_path):
    apk_size = os.path.getsize(apk_path)
    h = hashlib.sha256()
    with open(apk_path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    apk_sha = h.hexdigest()

# Real container observations: every distinct "name | image=... " line the
# poller actually saw while the run was in flight. Absence of any such line is
# absence of §6.AH proof, and is treated as a FAIL below — never waved through.
container_obs = []
for ln in proof_text.splitlines():
    stripped = ln.strip()
    if stripped.startswith("lava-emu-") and "image=" in stripped:
        if stripped not in container_obs:
            container_obs.append(stripped)

device_obs = []
for ln in proof_text.splitlines():
    stripped = ln.strip()
    if stripped.startswith("localhost:") or stripped.startswith("emulator-"):
        if stripped not in device_obs:
            device_obs.append(stripped)

containerized_declared = "containerized runner:" in run_log_text
accel = (preflight or {}).get("resolved_accel_backend", "")
gate_eligible = (preflight or {}).get("gate_eligible", None)

row0 = rows[0] if rows else None
diag = (row0 or {}).get("diag") or {}
install_failed = bool(row0 and str(row0.get("test_error", "")).startswith("install failed:"))

prov_raw_lines = [
    "phase-04 live-verification — :api-app install + runner provenance",
    "",
    "artifact under verification:",
    "  path:   %s" % apk_path,
    "  exists: %s" % apk_present,
    "  bytes:  %d" % apk_size,
    "  sha256: %s" % (apk_sha or "<not computed: artifact absent>"),
    "",
    "requested target:",
    "  avds:            %s" % avd_spec,
    "  container image: %s" % container_image_ref,
    "  runtime:         %s" % container_runtime,
    "",
    "invocation:",
    "  %s" % matrix_command,
    "  exit code: %s%s" % (matrix_rc or "<not invoked>",
                           "  (KILLED by the %ss outer bound)" % outer_timeout if timed_out == "true" else ""),
    "",
    "host preflight (written by the matrix script itself):",
    json.dumps(preflight, indent=2) if preflight else "  <absent>",
    "",
    "§6.AH container proof — distinct emulator containers this script OBSERVED",
    "RUNNING while the run was in flight (sampled every 10s by its own poller,",
    "independent of anything the matrix runner reports about itself):",
]
if container_obs:
    prov_raw_lines += ["  %s" % o for o in container_obs]
else:
    prov_raw_lines += ["  <NONE OBSERVED — no §6.AH container proof for this run>"]
prov_raw_lines += [
    "",
    "§6.AG target proof — bridge-visible devices observed during the run:",
]
if device_obs:
    prov_raw_lines += ["  %s" % o for o in device_obs]
else:
    prov_raw_lines += ["  <none observed>"]
prov_raw_lines += [
    "",
    "physical devices attached at phase start (§6.AG — never a target):",
    "  %s" % (one_line(live_devices) or "<none>"),
    "",
    "attestation row diagnostics (Containers submodule's own capture, taken",
    "AFTER the APK reached the device and BEFORE the test — matrix.go:334):",
    json.dumps(diag, indent=2) if diag else "  <absent>",
    "",
    "full attestation:",
    json.dumps(attestation, indent=2) if attestation else "  <absent>",
    "",
    "--- last 40 non-blank lines of the real matrix invocation log ---",
]
prov_raw_lines += [
    ln for ln in [x for x in run_log_text.splitlines() if x.strip()][-40:]
]
prov_raw_lines += [
    "",
    "--- live target proof log (verbatim) ---",
    proof_text,
]

prov_raw_path = os.path.join(script_raw_dir, "install-and-runner-provenance.log")
with open(prov_raw_path, "w", encoding="utf-8") as fh:
    fh.write("\n".join(prov_raw_lines) + "\n")

if apk_present != "true":
    prov_result = "FAIL"
    prov_summary = one_line(
        "The :api-app artifact under live-verification does not exist at %s, so nothing "
        "could be put onto an emulator and the boot-and-serve behaviour of the shipped "
        "APK is UNVERIFIED. phase-01's :api-app debug build step must run first."
        % apk_path
    )
elif not container_obs:
    prov_result = "FAIL"
    prov_summary = one_line(
        "§6.AH NOT PROVEN for this run: this script's own poller sampled '%s ps "
        "--filter name=lava-emu' throughout the invocation and observed ZERO running "
        "emulator container. Requested image was %s, AVD %s, matrix script exit %s, "
        "host preflight accel=%s gate_eligible=%s. Without a real running container "
        "there is no evidence the emulator ran inside a container/VM, and §6.AH "
        "forbids a host-direct fallback, so this is reported as a failure rather than "
        "waved through. Matrix log tail: %s"
        % (container_runtime, container_image_ref, avd_spec, matrix_rc or "<not invoked>",
           accel or "<unknown>", gate_eligible,
           one_line("\n".join([x for x in run_log_text.splitlines() if x.strip()][-6:]), 400))
    )
elif install_failed:
    prov_result = "FAIL"
    prov_summary = one_line(
        "The Containers matrix row reports a REAL failure putting %s onto %s: "
        "test_error=%r. The bridge's own install step did not report Success "
        "(submodules/containers/pkg/emulator/containerized.go:719), so the shipped "
        "artifact never reached the device." % (apk_path, avd_spec, row0.get("test_error"))
    )
else:
    prov_result = "PASS"
    prov_summary = one_line(
        "§6.AH/§6.AG PROVEN for this run by direct observation, not by self-report: the "
        ":api-app debug artifact %s (%d bytes, sha256 %s) was put by the Containers "
        "submodule onto a cold-booted emulator running INSIDE a real container observed by "
        "this script's own poller as %s; the matrix script declared the containerized "
        "runner=%s with host accel=%s; the attestation's post-install diagnostic reports "
        "target=%r runtime sdk=%s device=%r adb_devices_state=%r; and the bridge-visible "
        "target during the run was the emulator's own forwarded port, never a physical "
        "device (physical devices attached at phase start: %s)."
        % (apk_path, apk_size, apk_sha or "<none>",
           one_line("; ".join(container_obs), 400),
           containerized_declared, accel or "<unknown>",
           diag.get("target", ""), diag.get("sdk", ""), diag.get("device", ""),
           one_line(diag.get("adb_devices_state", ""), 200),
           one_line(live_devices) or "<none>")
    )

records.append({
    "test_id": "api-app-live-verify-install-and-runner-provenance",
    "category": "hermetic-script",
    "command": rerun_command,
    "result": prov_result,
    "assertion_summary": prov_summary,
    "raw_file": prov_raw_path,
})

# ---------------------------------------------------------------------------
# Record 2..N — one per verified Challenge class (category
# real-device-challenge), cross-checking TWO independent sources.
# ---------------------------------------------------------------------------
requested = []
with open(manifest_file, "r", encoding="utf-8") as fh:
    for line in fh:
        line = line.rstrip("\n")
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) == 2:
            requested.append((parts[0], parts[1]))

testcases_by_class = {}
xml_files_used = []
all_found_classnames = set()
marker_mtime = os.path.getmtime(marker_file) if os.path.isfile(marker_file) else 0.0
if xml_search_dir and os.path.isdir(xml_search_dir):
    for xf in sorted(glob.glob(os.path.join(xml_search_dir, "**", "TEST-*.xml"), recursive=True)):
        try:
            if os.path.getmtime(xf) < marker_mtime:
                continue
            tree = ET.parse(xf)
        except Exception as exc:  # noqa: BLE001
            print("WARN: could not parse %s: %s" % (xf, exc), file=sys.stderr)
            continue
        xml_files_used.append(xf)
        for tc in tree.getroot().iter("testcase"):
            cn = tc.get("classname", "") or ""
            all_found_classnames.add(cn)
            testcases_by_class.setdefault(cn, []).append(tc)

for fqcn, kt_path in requested:
    marker_block, marker_err = extract_marker_block(kt_path)
    rel_kt = os.path.relpath(kt_path, repo_path)
    row = row_by_class.get(fqcn)
    tcs = testcases_by_class.get(fqcn, [])

    raw_lines = [
        "phase-04 live-verification — :api-app boot-and-serve Challenge on a containerized emulator",
        "",
        "class:        %s" % fqcn,
        "source:       %s" % rel_kt,
        "artifact:     %s (sha256 %s)" % (apk_path, apk_sha or "<absent>"),
        "AVD:          %s   image: %s   runtime: %s" % (avd_spec, container_image_ref, container_runtime),
        "invocation:   %s" % matrix_command,
        "exit code:    %s%s" % (matrix_rc or "<not invoked>",
                                 "  (KILLED by the %ss outer bound)" % outer_timeout if timed_out == "true" else ""),
        "",
        "SOURCE A — Gradle's own host-side JUnit XML (freshness-filtered to THIS run):",
        "  files parsed: %s" % (", ".join(os.path.relpath(x, repo_path) for x in xml_files_used) or "<none>"),
        "  classnames present: %s" % (sorted(all_found_classnames) or "<none>"),
    ]

    if apk_present != "true":
        result = "FAIL"
        summary = one_line(
            "The boot-and-serve Challenge %s could NOT be live-verified: the :api-app debug "
            "artifact is absent at %s, so no APK reached any emulator and the shipped app's "
            "boot-and-serve behaviour is UNVERIFIED." % (fqcn, apk_path)
        )
        raw_lines += ["  <the run never happened — artifact absent>"]
    elif timed_out == "true":
        result = "FAIL"
        summary = one_line(
            "The boot-and-serve live-verification of %s exceeded its %ss outer bound and was "
            "killed (exit %s). The emulator did not produce a usable device + completed "
            "instrumentation within the bound, so the shipped artifact's boot-and-serve "
            "behaviour is UNVERIFIED. Real matrix log tail: %s"
            % (fqcn, outer_timeout, matrix_rc,
               one_line("\n".join([x for x in run_log_text.splitlines() if x.strip()][-8:]), 600))
        )
        raw_lines += ["  <timed out before the report was written>"]
    elif not tcs and row is None:
        result = "FAIL"
        summary = one_line(
            "Neither independent source recorded %s for this run: no <testcase classname=%r> "
            "in any freshly-written Gradle JUnit XML (%d file(s) parsed) AND no matching row "
            "in the Containers attestation (%s). The matrix script exited %s. Real diagnostic "
            "tail: %s"
            % (fqcn, fqcn, len(xml_files_used),
               "attestation absent" if attestation is None else "%d row(s) present" % len(rows),
               matrix_rc or "<not invoked>",
               one_line("\n".join([x for x in run_log_text.splitlines() if x.strip()][-8:]), 600))
        )
    else:
        xml_failures = []
        for tc in tcs:
            fnode = tc.find("failure")
            enode = tc.find("error")
            node = fnode if fnode is not None else enode
            if node is not None:
                xml_failures.append((tc.get("name", ""), "failure" if fnode is not None else "error",
                                     node.get("type", ""), node.get("message", ""), node.text or ""))
        xml_verdict = None
        if tcs:
            xml_verdict = "PASS" if not xml_failures else "FAIL"
        row_verdict = None
        if row is not None:
            row_verdict = "PASS" if row.get("test_passed") else "FAIL"

        raw_lines += ["  testcases found: %d" % len(tcs)]
        for tc in tcs:
            raw_lines += ["    method %r time=%ss" % (tc.get("name", ""), tc.get("time", "?"))]
        for name, kind, ftype, fmsg, ftext in xml_failures:
            raw_lines += [
                "    FAILURE method=%r kind=%s type=%s message=%s" % (name, kind, ftype, fmsg),
                "      stack excerpt: %s" % one_line(ftext, 700),
            ]
        raw_lines += [
            "  SOURCE A verdict: %s" % (xml_verdict or "<no testcase for this class>"),
            "",
            "SOURCE B — Containers submodule attestation row (independent of Gradle's XML):",
            json.dumps(row, indent=2) if row is not None else "  <no row for this class>",
            "  SOURCE B verdict: %s" % (row_verdict or "<none>"),
        ]

        # The header's contract is that a PASS needs BOTH sources: Gradle's own
        # host-side JUnit XML AND the Containers attestation row. Disagreement
        # between them was caught below; TOTAL ABSENCE of one of them was not,
        # and fell through to the PASS branch -- so a run in which only the
        # matrix runner's own self-report existed was reported as
        # "cross-checked against two independent sources", and a record whose
        # summary read "Gradle's own host-side JUnit XML records 0 real
        # testcase(s)" was stamped PASS. Absence of corroboration is a weaker
        # evidential position than disagreement, never a stronger one.
        missing_sources = []
        if xml_verdict is None:
            missing_sources.append(
                "Gradle's own host-side JUnit XML (no <testcase classname=%r> in any of the "
                "%d freshly-written report file(s) parsed for this run; classnames actually "
                "present: %s)" % (fqcn, len(xml_files_used), sorted(all_found_classnames) or "<none>")
            )
        if row_verdict is None:
            missing_sources.append(
                "the Containers attestation row for this class (%s; row test_class values "
                "seen: %s)" % ("attestation file absent" if attestation is None
                               else "%d row(s) present but none matched" % len(rows),
                               sorted(row_by_class) or "<none>")
            )

        if xml_verdict and row_verdict and xml_verdict != row_verdict:
            result = "FAIL"
            summary = one_line(
                "CROSS-CHECK DISAGREEMENT for %s: Gradle's own host-side JUnit XML says %s "
                "while the Containers attestation row says %s (row test_passed=%s, "
                "test_error=%r, failure_summaries=%s). Two independent sources of truth "
                "disagreeing about the same run is itself a failure — the run cannot be "
                "trusted to prove the shipped artifact boots and serves."
                % (fqcn, xml_verdict, row_verdict, row.get("test_passed"),
                   row.get("test_error", ""), one_line(json.dumps(row.get("failure_summaries", [])), 500))
            )
        elif missing_sources and (xml_verdict or row_verdict) != "FAIL":
            result = "FAIL"
            summary = one_line(
                "ONLY ONE of the two independent sources recorded %s for this run, so there "
                "was nothing to cross-check it against. Missing: %s. Present: %s said %s. "
                "This phase's contract is that a PASS requires BOTH Gradle's own host-side "
                "JUnit XML and the Containers attestation row -- the second source exists "
                "precisely so a matrix runner cannot certify its own run, and a source that "
                "produced nothing at all corroborates nothing. Reported as a failure rather "
                "than carried by the surviving source alone. Matrix script exited %s. Real "
                "log tail: %s"
                % (fqcn, "; ".join(missing_sources),
                   "the Containers attestation row" if xml_verdict is None
                   else "Gradle's host-side JUnit XML",
                   row_verdict if xml_verdict is None else xml_verdict,
                   matrix_rc or "<not invoked>",
                   one_line("\n".join([x for x in run_log_text.splitlines() if x.strip()][-8:]), 500))
            )
        elif (xml_verdict or row_verdict) == "FAIL":
            result = "FAIL"
            if xml_failures:
                nm, kind, ftype, fmsg, ftext = xml_failures[0]
                summary = one_line(
                    "The boot-and-serve Challenge %s FAILED on the real containerized emulator "
                    "%s: method %r raised a %s type=%s message=%r (%d of %d real testcase(s) "
                    "failed). The %s artifact therefore does NOT demonstrably boot and serve "
                    "on-device. Stack excerpt: %s"
                    % (fqcn, avd_spec, nm, kind, ftype, one_line(fmsg, 400),
                       len(xml_failures), len(tcs), apk_path, one_line(ftext, 500))
                )
            else:
                summary = one_line(
                    "The Containers attestation row for %s on %s reports test_passed=false "
                    "(test_error=%r, boot_seconds=%s, failure_summaries=%s) — the shipped "
                    "artifact did not complete its boot-and-serve Challenge on the device."
                    % (fqcn, avd_spec, row.get("test_error", ""), row.get("boot_seconds"),
                       one_line(json.dumps(row.get("failure_summaries", [])), 500))
                )
        else:
            result = "PASS"
            methods = ", ".join("%r (%ss)" % (tc.get("name", ""), tc.get("time", "?")) for tc in tcs)
            summary = one_line(
                "LIVE-VERIFIED on a real cold-booted emulator inside a %s container (%s): the "
                ":api-app debug artifact %s (sha256 %s) was put by the Containers submodule "
                "onto %s and its real Compose UI boot-and-serve Challenge %s ran green — "
                "Gradle's own host-side JUnit XML records %d real testcase(s) with no "
                "<failure>/<error>: %s; and the INDEPENDENT Containers attestation row agrees "
                "(test_passed=%s, boot_seconds=%s, test_seconds=%s, concurrent=%s, "
                "diag.sdk=%s, diag.device=%r). The Challenge's own primary assertions are on "
                "real HTTP responses from the on-device embed (/health 200 + real JSON body, "
                "the auth-gated route 401 without the key, and NOT-401 with the key the UI "
                "displayed), so this is proof the shipped app genuinely SERVES on-device, not "
                "merely that it launched."
                % (container_runtime, one_line("; ".join(container_obs), 300) or "<container not sampled>",
                   apk_path, apk_sha or "<none>", avd_spec, fqcn, len(tcs), methods,
                   (row or {}).get("test_passed"),
                   (row or {}).get("boot_seconds"), (row or {}).get("test_seconds"),
                   (row or {}).get("concurrent"), diag.get("sdk", ""), diag.get("device", ""))
            )

    raw_lines += [
        "",
        "--- real KDoc FALSIFIABILITY REHEARSAL marker, verbatim from %s ---" % rel_kt,
    ]
    if marker_block:
        raw_lines.append(marker_block)
    elif marker_err:
        raw_lines.append(marker_err)
    else:
        raw_lines.append(
            "(this Challenge's KDoc carries NO FALSIFIABILITY REHEARSAL / "
            "§6.AB-discrimination: marker — a real, pre-existing gap in the source, not "
            "fabricated here; anti-bluff-validate.sh Rule 4 will honestly REJECT this record)"
        )
    raw_lines += [
        "",
        "--- last 60 non-blank lines of the real matrix invocation log ---",
    ]
    raw_lines += [ln for ln in [x for x in run_log_text.splitlines() if x.strip()][-60:]]

    raw_path = os.path.join(challenge_raw_dir, fqcn.replace(".", "_") + ".log")
    with open(raw_path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(raw_lines) + "\n")

    records.append({
        "test_id": fqcn,
        "category": "real-device-challenge",
        "command": 'LAVA_PIPELINE_LIVE_VERIFY_API_APP_TEST_CLASSES="%s" scripts/pipeline/phase-04-live-verify-api-app.sh %s'
                   % (fqcn, rerun_command.rsplit(" ", 1)[-1]),
        "result": result,
        "assertion_summary": summary,
        "raw_file": raw_path,
    })

for r in records:
    print(json.dumps(r, ensure_ascii=False))
PYEOF
PARSE_RC=$?

if [[ $PARSE_RC -ne 0 ]]; then
  _log "phase-04-live-verify-api-app: ERROR — the evidence parser exited ${PARSE_RC}; no Evidence Records could be built from this run's artifacts"
  OVERALL_OK="false"
fi

while IFS= read -r line; do
  [[ -z "$line" ]] && continue
  IFS=$'\t' read -r f_test_id f_category f_command f_result f_summary f_rawfile \
    <<< "$(jq -r '[.test_id, .category, .command, .result, .assertion_summary, .raw_file] | @tsv' <<< "$line")"
  emit_record "$f_test_id" "$f_category" "$f_command" "$f_result" "$f_summary" "$f_rawfile"
done < "$PARSED_JSONL"

if [[ ${#RECORD_PATHS[@]} -eq 0 ]]; then
  _log "phase-04-live-verify-api-app: ERROR — zero Evidence Records were produced; a live-verification that records nothing proves nothing"
  OVERALL_OK="false"
fi

END_TS=$(date +%s)
DURATION=$((END_TS - START_TS))

PHASE_RESULT="PASS"
if [[ "$OVERALL_OK" != "true" ]]; then
  PHASE_RESULT="FAIL"
fi

append_phase_result "$RUN_ID" "live_verify" "$PHASE_RESULT" "$DURATION" "$PHASE_DIR"

echo ""
echo "phase-04-live-verify-api-app: SUMMARY"
echo "  artifact:            ${APK_PATH} (present=${APK_PRESENT})"
echo "  AVD / image:         ${AVD_SPEC} / ${CONTAINER_IMAGE_REF}"
echo "  matrix exit code:    ${MATRIX_RC:-<not invoked>}"
echo "  duration:            ${DURATION}s"
echo "  Evidence Records:    ${#RECORD_PATHS[@]}"
for i in "${!RECORD_PATHS[@]}"; do
  echo "    ${RECORD_RESULTS[$i]}  ${RECORD_PATHS[$i]} (anti_bluff_status=${RECORD_STATUSES[$i]:-<none>})"
done
echo "  phase result:        ${PHASE_RESULT}"

if [[ "$PHASE_RESULT" == "FAIL" ]]; then
  echo "phase-04-live-verify-api-app: FAILED — see the FAIL/REJECTED records above; the :api-app half of FR-008 is NOT satisfied for this run" >&2
  exit 1
fi

echo "phase-04-live-verify-api-app: the :api-app debug artifact really reached a Containers-orchestrated cold-booted emulator and its real boot-and-serve Challenge passed, cross-checked against two independent sources"
exit 0

#!/usr/bin/env bash
# scripts/pipeline/phase-01-build.sh — tasks.md T020: wires the 5 individual
# artifact-build steps (T015-T019, implemented in
# scripts/pipeline/phase-01-build-android.sh and
# scripts/pipeline/phase-01-build-lava-api-go.sh) into one phase, dispatched
# as genuinely parallel OS processes (per plan.md's Parallel Execution
# Opportunities — the two scripts use entirely separate toolchains/daemons,
# so running them concurrently is safe, unlike running multiple Gradle
# invocations against the same project).
#
# Usage:
#   scripts/pipeline/phase-01-build.sh <run_id> [repo-path]
#
# <run_id> MUST already have a report.json (via lib/run-report.sh's
# init_run_report, called by the top-level orchestrator before this phase
# runs). This script appends to that same report.json — it never creates a
# new run.
#
# Exit codes:
#   0 - both build steps succeeded; every Build Artifact verified to exist
#       as a real non-empty file on disk, and recorded.
#   1 - at least one build step failed; failure is recorded in report.json's
#       "build" phase entry as FAIL, and the process still exits non-zero so
#       the orchestrator halts before phase-02 (FR-009 spirit — never test
#       artifacts that don't genuinely exist).
#   2 - usage/precondition error (missing run_id, report.json absent).

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# shellcheck source=scripts/pipeline/lib/run-report.sh
source "$SCRIPT_DIR/lib/run-report.sh"

RUN_ID="${1:-}"
REPO_PATH_OVERRIDE="${2:-}"

if [[ -z "$RUN_ID" ]]; then
  echo "phase-01-build: usage: $0 <run_id> [repo-path]" >&2
  exit 2
fi

REPORT_PATH=".lava-ci-evidence/pipeline-runs/${RUN_ID}/report.json"
if [[ ! -f "$REPORT_PATH" ]]; then
  echo "phase-01-build: precondition failed — $REPORT_PATH does not exist (call init_run_report first)" >&2
  exit 2
fi

# The two sub-build scripts below are given REPO_PATH_OVERRIDE (when set) as
# the repository to build from. Everything in THIS script that reads
# repository state — the versionName/versionCode stamped onto each Build
# Artifact, the commit each artifact was built from — must read it from that
# same repository, or report.json ends up describing a different checkout
# than the artifacts it lists. It previously read those from $REPO_ROOT (the
# directory this script itself lives in) unconditionally; see
# tests/pipeline/test_phase_01_artifact_verification.sh CASE 3. When no
# override is passed (the normal pipeline invocation) this resolves to
# exactly the old value, so nothing changes for a real run. Same idiom as
# phase-03-install-boot.sh / phase-04-live-verify-api.sh.
REPO_PATH="${REPO_PATH_OVERRIDE:-$REPO_ROOT}"

PHASE_DIR=".lava-ci-evidence/pipeline-runs/${RUN_ID}/phase-01"
mkdir -p "$PHASE_DIR"

ANDROID_LOG="$PHASE_DIR/android-build.log"
GO_LOG="$PHASE_DIR/lava-api-go-build.log"

START_TS=$(date +%s)

echo "phase-01-build: dispatching Android build + lava-api-go build as parallel processes"

# Genuinely parallel: two separate OS processes, two separate toolchains
# (Gradle vs. Go/make) with no shared daemon/lock, unlike running multiple
# Gradle invocations concurrently against the same project (which
# phase-01-build-android.sh's own header explains is unsafe and avoided by
# using ONE combined Gradle invocation for all 4 Android variants).
"$SCRIPT_DIR/phase-01-build-android.sh" "$REPO_PATH_OVERRIDE" >"$ANDROID_LOG" 2>&1 &
ANDROID_PID=$!

"$SCRIPT_DIR/phase-01-build-lava-api-go.sh" "$REPO_PATH_OVERRIDE" >"$GO_LOG" 2>&1 &
GO_PID=$!

wait "$ANDROID_PID"
ANDROID_RC=$?
wait "$GO_PID"
GO_RC=$?

END_TS=$(date +%s)
DURATION=$((END_TS - START_TS))

echo "phase-01-build: android build exit=$ANDROID_RC, lava-api-go build exit=$GO_RC (parallel wall-clock: ${DURATION}s)"
cat "$ANDROID_LOG"
cat "$GO_LOG"

# --- Extract ARTIFACT lines from both logs and record Build Artifacts -----
# phase-01-build-android.sh prints "ARTIFACT <label>: <path>" to stdout on
# success, "ARTIFACT <label>: MISSING (<reason>)" to stderr on failure (both
# captured into the same log file above via 2>&1).
_record_build_artifact() {
  local artifact_id="$1" path="$2"

  local version_name version_code built_from_commit
  built_from_commit="$(cd "$REPO_PATH" && git rev-parse HEAD)"

  case "$artifact_id" in
    app-debug|app-release)
      version_name="$(grep -E '^\s+versionName\s*=' "$REPO_PATH/app/build.gradle.kts" | sed 's/.*"\([^"]*\)".*/\1/')"
      version_code="$(grep -E '^\s+versionCode\s*=' "$REPO_PATH/app/build.gradle.kts" | grep -oE '[0-9]+')"
      ;;
    api-app-debug|api-app-release)
      version_name="$(grep -E '^\s+versionName\s*=' "$REPO_PATH/api-app/build.gradle.kts" | sed 's/.*"\([^"]*\)".*/\1/')"
      version_code="$(grep -E '^\s+versionCode\s*=' "$REPO_PATH/api-app/build.gradle.kts" | grep -oE '[0-9]+')"
      ;;
    lava-api-go)
      # lava-api-go --version prints e.g. "lava-api-go 2.3.34 (build 2334)"
      local version_line
      version_line="$("$path" --version 2>&1 || true)"
      version_name="$(printf '%s' "$version_line" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)"
      version_code="$(printf '%s' "$version_line" | grep -oE 'build [0-9]+' | grep -oE '[0-9]+')"
      ;;
    *)
      version_name="unknown"
      version_code="0"
      ;;
  esac

  # A non-numeric/multi-line version_code makes --argjson reject its input,
  # so this jq invocation CAN fail. It used to be followed by `&& mv`, which
  # correctly skipped the move — and then the function returned 0 anyway and
  # the caller incremented its artifact counter regardless, crediting the
  # phase for a Build Artifact that never reached report.json. Report the
  # failure instead; the caller checks it.
  local tmp
  tmp="$(mktemp)"
  if ! jq \
    --arg id "$artifact_id" \
    --arg vn "${version_name:-unknown}" \
    --argjson vc "${version_code:-0}" \
    --arg path "$path" \
    --arg commit "$built_from_commit" \
    '.build_artifacts += [{
      artifact_id: $id,
      version_name: $vn,
      version_code: $vc,
      build_output_path: $path,
      built_from_commit: $commit
    }]' \
    "$REPORT_PATH" > "$tmp"; then
    echo "phase-01-build: FAILED to record Build Artifact '$artifact_id' — jq could not update $REPORT_PATH (version_name='${version_name}' version_code='${version_code}')" >&2
    rm -f "$tmp"
    return 1
  fi
  if ! mv "$tmp" "$REPORT_PATH"; then
    echo "phase-01-build: FAILED to record Build Artifact '$artifact_id' — could not move the updated report into place" >&2
    rm -f "$tmp"
    return 1
  fi

  echo "phase-01-build: recorded Build Artifact '$artifact_id' (version=$version_name/$version_code) -> $path"
  return 0
}

# An ARTIFACT line is a CLAIM made by a sub-build script's stdout, not proof.
# Verify the claim against the filesystem before recording or counting it
# (forensic anchor, 2026-08-21): this loop used to record and count every
# non-MISSING ARTIFACT line without ever asking whether the path was real,
# and _record_build_artifact's own failure was never checked either — the
# counter was incremented unconditionally right after calling it. A
# sub-build whose `cp` failed therefore produced a phase that announced
# "5 of 5 expected Build Artifacts recorded" and "all 5 Build Artifacts
# produced and recorded", exit 0, with build_artifacts[] pointing at a file
# that does not exist. Downstream, phase-02-test.sh resolves the
# release-canary APK out of exactly that array, and phase-05/distribute read
# it too. Regression coverage:
# tests/pipeline/test_phase_01_artifact_verification.sh CASE 2.
_total_artifacts=0
_unverified_artifacts=0
while IFS= read -r line; do
  if [[ "$line" =~ ^ARTIFACT\ ([a-zA-Z0-9_-]+):\ (.+)$ ]]; then
    label="${BASH_REMATCH[1]}"
    value="${BASH_REMATCH[2]}"
    if [[ "$value" == MISSING* ]]; then
      echo "phase-01-build: NOT recording '$label' as a Build Artifact — $value" >&2
    elif [[ ! -f "$value" ]]; then
      echo "phase-01-build: NOT recording '$label' as a Build Artifact — the declared path does not exist on disk: $value" >&2
      _unverified_artifacts=$((_unverified_artifacts + 1))
    elif [[ ! -s "$value" ]]; then
      echo "phase-01-build: NOT recording '$label' as a Build Artifact — the declared path exists but is empty (0 bytes): $value" >&2
      _unverified_artifacts=$((_unverified_artifacts + 1))
    elif _record_build_artifact "$label" "$value"; then
      _total_artifacts=$((_total_artifacts + 1))
    else
      _unverified_artifacts=$((_unverified_artifacts + 1))
    fi
  fi
done < <(cat "$ANDROID_LOG" "$GO_LOG")

echo "phase-01-build: $_total_artifacts of 5 expected Build Artifacts recorded (${_unverified_artifacts} claimed-but-unverifiable)"

PHASE_RESULT="PASS"
if [[ $ANDROID_RC -ne 0 || $GO_RC -ne 0 || $_total_artifacts -lt 5 || $_unverified_artifacts -gt 0 ]]; then
  PHASE_RESULT="FAIL"
fi

append_phase_result "$RUN_ID" "build" "$PHASE_RESULT" "$DURATION" "$PHASE_DIR"

if [[ "$PHASE_RESULT" == "FAIL" ]]; then
  if [[ $_unverified_artifacts -gt 0 ]]; then
    echo "phase-01-build: FAILED — ${_unverified_artifacts} artifact(s) were announced by a sub-build but could not be verified on disk (or could not be recorded). An ARTIFACT line is a claim; a build phase that cannot point at the real file it claims to have produced has produced nothing testable." >&2
  fi
  echo "phase-01-build: FAILED — android_rc=$ANDROID_RC go_rc=$GO_RC artifacts_recorded=$_total_artifacts/5 unverifiable=$_unverified_artifacts" >&2
  exit 1
fi

echo "phase-01-build: all 5 Build Artifacts produced, verified on disk, and recorded"
exit 0

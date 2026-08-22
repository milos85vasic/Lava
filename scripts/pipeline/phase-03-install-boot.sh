#!/usr/bin/env bash
# scripts/pipeline/phase-03-install-boot.sh — tasks.md T033/T034/T035/T038:
# FR-005 (install + start), FR-006 (real health check, not merely
# process-running), FR-007 (stale-instance cleanup) for the lava-api-go
# systemd --user service.
#
# This phase does NOT reimplement systemd wiring. A real, already-shipped,
# already-verified implementation exists (commit 7951bf4f):
#   systemd/user/lava-api.service.template
#   scripts/systemd-install.sh
#   scripts/systemd-status.sh
#   scripts/systemd-uninstall.sh
# Per research.md R-012 (correcting the plan's original R-006/R-007 drafts,
# which assumed no such wiring existed) and this project's Decoupled
# Reusable Architecture / Local-Only CI/CD "no parallel implementation"
# principle, this script is thin glue: it invokes scripts/systemd-uninstall.sh
# and scripts/systemd-install.sh --start VERBATIM (no reimplementation of
# their unit-rendering/daemon-reload/enable/linger logic), and adds only the
# two things that don't already exist anywhere:
#   (a) the FR-007 stale-instance pre-check (query
#       `systemctl --user is-active lava-api.service` before deciding
#       whether to uninstall-then-reinstall vs. install-fresh), and
#   (b) parsing `tools/lava-containers/bin/lava-containers -cmd=status`
#       directly for the real `Healthy:` field.
#
# On (b): this script calls the lava-containers binary directly rather than
# the human-readable scripts/systemd-status.sh wrapper, because this script
# needs a machine-parseable signal. Confirmed by reading
# tools/lava-containers/internal/orchestrator/manager.go's Manager.Status()
# (called by the binary's "status" subcommand): it ALWAYS returns nil
# regardless of the probed health value, so `lava-containers -cmd=status`
# ALWAYS exits 0 even when the service is unhealthy — trusting the exit code
# alone would be exactly the §6.B "container Up is not application-healthy"
# bluff this project's Anti-Bluff Pact forbids. The real signal is the
# printed "Healthy:      true" / "Healthy:      false" line itself, which
# this script parses with a real regex against the real captured output.
#
# Usage:
#   scripts/pipeline/phase-03-install-boot.sh <run_id> [repo-path]
#
# <run_id> MUST already have a report.json (via lib/run-report.sh's
# init_run_report, called by the top-level orchestrator — or, for a
# standalone/manual run, by the caller directly — before this phase runs).
# This script appends to that same report.json under phase name
# "install_boot" (lib/run-report.sh's _RUN_REPORT_PHASE_NAMES); it never
# creates a new run.
#
# WARNING: this script has REAL side effects on the invoking host — it
# genuinely installs/starts (or stops/removes then reinstalls/restarts) the
# `lava-api.service` systemd --user unit for the real lava-api-go container.
# It is safe to re-run (systemd-install.sh + systemd-uninstall.sh are both
# idempotent per their own documentation at
# docs/scripts/systemd-install.sh.md), but it is not a dry-run / simulation.
#
# Exit codes:
#   0 - stale-check (if needed) + install + health-check all succeeded
#       (Healthy: true); one Evidence Record written and anti-bluff-
#       validated as "validated" with result PASS.
#   1 - a real failure occurred (stale-instance cleanup failed, install
#       failed, or the health check reported Healthy: false / was
#       unparseable), OR the Evidence Record was REJECTED by
#       anti-bluff-validate.sh. The failure is recorded as FAIL in
#       report.json's "install_boot" phase entry either way — never
#       fabricated as success.
#   2 - usage/precondition error (missing run_id, report.json absent).

set -uo pipefail
# Deliberately NOT `set -e`: every risky step below (systemctl, the install/
# uninstall scripts, the health-check binary) is explicitly guarded via `if`
# + direct `$?` capture, because a non-zero exit from any one of them is a
# REAL, WANTED signal for this phase's own outcome — not a script bug to
# abort on. Relying on inherited errexit here would stop this script before
# it could record the honest failure it was invoked to observe.

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
  echo "phase-03-install-boot: usage: $0 <run_id> [repo-path]" >&2
  exit 2
fi

REPO_PATH="${REPO_PATH_OVERRIDE:-$REPO_ROOT}"

REPORT_PATH=".lava-ci-evidence/pipeline-runs/${RUN_ID}/report.json"
if [[ ! -f "$REPORT_PATH" ]]; then
  echo "phase-03-install-boot: precondition failed — $REPORT_PATH does not exist (call init_run_report first)" >&2
  exit 2
fi

PHASE_DIR=".lava-ci-evidence/pipeline-runs/${RUN_ID}/phase-03"
RAW_DIR="${PHASE_DIR}/hermetic-script/raw"
mkdir -p "$RAW_DIR"

COMBINED_LOG="${RAW_DIR}/install-boot-combined.log"
: > "$COMBINED_LOG"

_log() {
  echo "$*" | tee -a "$COMBINED_LOG"
}

START_TS=$(date +%s)

_log "phase-03-install-boot: repo=${REPO_PATH}"
_log "phase-03-install-boot: run_id=${RUN_ID}"
_log ""

OVERALL_OK="true"
FAILURE_REASON=""
STALE_UNINSTALLED="false"

# ---------------------------------------------------------------------------
# Step 1 (FR-007): stale-instance check.
# ---------------------------------------------------------------------------
_log "=== Step 1: stale-instance check (FR-007) ==="
_log "\$ systemctl --user is-active lava-api.service"
PRIOR_STATE="$(systemctl --user is-active lava-api.service 2>&1)"
PRIOR_RC=$?  # vacuous-pass-ok: `systemctl --user is-active` returns non-zero for the perfectly normal 'inactive' state, so its rc is not a verdict; the verdict comes from PRIOR_STATE (its stdout).
_log "output: ${PRIOR_STATE}"
_log "exit_code: ${PRIOR_RC}"

if [[ "$PRIOR_STATE" == "active" ]]; then
  _log "phase-03-install-boot: lava-api.service is already active — invoking scripts/systemd-uninstall.sh before (re)install"
  _log "\$ ${REPO_PATH}/scripts/systemd-uninstall.sh"
  if ( cd "$REPO_PATH" && ./scripts/systemd-uninstall.sh ) >>"$COMBINED_LOG" 2>&1; then
    STALE_UNINSTALLED="true"
    _log "phase-03-install-boot: stale instance cleanly stopped and removed"
  else
    UNINSTALL_RC=$?  # vacuous-pass-ok: captured inside an `if ! ...` branch, so the failure is already detected structurally; the rc is only quoted in FAILURE_REASON.
    OVERALL_OK="false"
    FAILURE_REASON="stale-instance cleanup failed — scripts/systemd-uninstall.sh exited ${UNINSTALL_RC}"
    _log "phase-03-install-boot: FAILED — ${FAILURE_REASON}"
  fi
else
  _log "phase-03-install-boot: no stale instance detected (is-active reported '${PRIOR_STATE}') — no uninstall needed"
fi
_log ""

# ---------------------------------------------------------------------------
# Step 2 (FR-005): install + start, reusing scripts/systemd-install.sh
# verbatim. Skipped if step 1 already failed (nothing to safely install onto
# in that case — attempting it would risk masking the real stale-cleanup
# failure with a second, unrelated failure).
# ---------------------------------------------------------------------------
_log "=== Step 2: install + start (FR-005) ==="
INSTALL_RC=""
if [[ "$OVERALL_OK" == "true" ]]; then
  _log "\$ ${REPO_PATH}/scripts/systemd-install.sh --start"
  if ( cd "$REPO_PATH" && ./scripts/systemd-install.sh --start ) >>"$COMBINED_LOG" 2>&1; then
    INSTALL_RC=0
    _log "phase-03-install-boot: scripts/systemd-install.sh --start succeeded"
  else
    INSTALL_RC=$?  # vacuous-pass-ok: captured inside an `if ! ...` branch — failure already detected; the rc is only quoted in FAILURE_REASON and the summary.
    OVERALL_OK="false"
    FAILURE_REASON="scripts/systemd-install.sh --start failed (exit ${INSTALL_RC})"
    _log "phase-03-install-boot: FAILED — ${FAILURE_REASON}"
  fi
else
  _log "phase-03-install-boot: SKIPPED — step 1 (stale-instance cleanup) already failed"
fi
_log ""

# ---------------------------------------------------------------------------
# Step 3 (FR-006): real health check. Always attempted (even after a step
# 1/2 failure) because it is read-only and its real output is itself useful
# evidence of the actual current state — never skipped, never faked.
# ---------------------------------------------------------------------------
_log "=== Step 3: health check (FR-006) ==="
_log "\$ ${REPO_PATH}/tools/lava-containers/bin/lava-containers -cmd=status"
HEALTH_OUTPUT="$("$REPO_PATH/tools/lava-containers/bin/lava-containers" -cmd=status 2>&1)"
HEALTH_RC=$?  # vacuous-pass-ok: DELIBERATELY not the verdict. Per §6.B the status binary exits 0 while reporting `Healthy: false` — proven for real during T035 by stopping the container out-of-band — so trusting this rc is exactly the 'container Up is not application-healthy' bluff. The verdict is parsed from the `Healthy:` line.
_log "${HEALTH_OUTPUT}"
_log "exit_code: ${HEALTH_RC}"

# Parse the real "Healthy:" line. Per manager.go's Status(), the binary's
# own exit code is NOT a reliable health signal (it exits 0 even when
# unhealthy) — the printed field is the load-bearing signal, matched
# case-insensitively against true/false.
HEALTH_LINE="$(printf '%s\n' "$HEALTH_OUTPUT" | grep -E '^Healthy:' | head -n1)"
HEALTH_BOOL="unknown"
if [[ "$HEALTH_LINE" =~ [Tt]rue ]]; then
  HEALTH_BOOL="true"
elif [[ "$HEALTH_LINE" =~ [Ff]alse ]]; then
  HEALTH_BOOL="false"
fi

_log "phase-03-install-boot: parsed health line: '${HEALTH_LINE}' -> healthy=${HEALTH_BOOL}"

if [[ "$HEALTH_BOOL" != "true" ]]; then
  OVERALL_OK="false"
  if [[ "$HEALTH_BOOL" == "unknown" ]]; then
    reason="health-check output did not contain a parseable 'Healthy:' field"
  else
    reason="health-check reported Healthy: false"
  fi
  if [[ -z "$FAILURE_REASON" ]]; then
    FAILURE_REASON="$reason"
  else
    FAILURE_REASON="${FAILURE_REASON}; ${reason}"
  fi
  _log "phase-03-install-boot: FAILED — ${reason}"
fi
_log ""

END_TS=$(date +%s)
DURATION=$((END_TS - START_TS))

# ---------------------------------------------------------------------------
# Step 4: write ONE Evidence Record summarizing the real install+health
# outcome, and anti-bluff-validate it.
# ---------------------------------------------------------------------------
_log "=== Step 4: Evidence Record ==="

RESULT="PASS"
if [[ "$OVERALL_OK" != "true" ]]; then
  RESULT="FAIL"
fi

TEST_ID="lava-api-go-install-boot"
CATEGORY="hermetic-script"
COMMAND_STR="systemctl --user is-active lava-api.service && ([ stale ] && scripts/systemd-uninstall.sh); scripts/systemd-install.sh --start; tools/lava-containers/bin/lava-containers -cmd=status"

if [[ "$RESULT" == "PASS" ]]; then
  ASSERTION_SUMMARY="stale-check: prior systemctl --user is-active state was '${PRIOR_STATE}' (stale instance uninstalled first: ${STALE_UNINSTALLED}); install: scripts/systemd-install.sh --start exited 0; health-check: tools/lava-containers/bin/lava-containers -cmd=status reported '${HEALTH_LINE}' (parsed healthy=${HEALTH_BOOL}) — lava-api-go container is genuinely serving its real /health endpoint, not merely process-running (§6.B)"
else
  ASSERTION_SUMMARY="FAILED: ${FAILURE_REASON}. stale-check: prior systemctl --user is-active state was '${PRIOR_STATE}' (stale instance uninstalled first: ${STALE_UNINSTALLED}); install exit=${INSTALL_RC:-not-attempted}; health-check reported '${HEALTH_LINE:-<no Healthy: line found>}' (parsed healthy=${HEALTH_BOOL})"
fi

RECORD_PATH=""
if ! RECORD_PATH="$(write_evidence_record "$PHASE_DIR" "$TEST_ID" "$CATEGORY" "$COMMAND_STR" "$RESULT" "$ASSERTION_SUMMARY" "$COMBINED_LOG")"; then
  echo "phase-03-install-boot: ERROR — write_evidence_record failed" >&2
  append_phase_result "$RUN_ID" "install_boot" "FAIL" "$DURATION" "$PHASE_DIR" || true
  exit 1
fi

ANTI_BLUFF_STATUS="REJECTED: validate_evidence_record did not run"
if validate_evidence_record "$RECORD_PATH" >/dev/null 2>&1; then
  ANTI_BLUFF_STATUS="validated"
  _log "phase-03-install-boot: Evidence Record anti_bluff_status=validated (${RECORD_PATH})"
else
  ANTI_BLUFF_STATUS="$(jq -r '.anti_bluff_status' "$RECORD_PATH" 2>/dev/null || echo "REJECTED: unknown")"
  OVERALL_OK="false"
  RESULT="FAIL"
  _log "phase-03-install-boot: Evidence Record REJECTED by anti-bluff-validate.sh: ${ANTI_BLUFF_STATUS} (${RECORD_PATH})"
fi

# ---------------------------------------------------------------------------
# Step 5: record the phase result into report.json.
# ---------------------------------------------------------------------------
PHASE_RESULT="PASS"
if [[ "$RESULT" != "PASS" ]]; then
  PHASE_RESULT="FAIL"
fi

append_phase_result "$RUN_ID" "install_boot" "$PHASE_RESULT" "$DURATION" "$PHASE_DIR"

echo ""
echo "phase-03-install-boot: SUMMARY"
echo "  stale instance uninstalled first: ${STALE_UNINSTALLED}"
echo "  install exit code:                ${INSTALL_RC:-not-attempted}"
echo "  health-check Healthy field:       ${HEALTH_BOOL}"
echo "  Evidence Record:                  ${RECORD_PATH} (anti_bluff_status=${ANTI_BLUFF_STATUS})"
echo "  phase result:                     ${PHASE_RESULT}"

if [[ "$PHASE_RESULT" == "FAIL" ]]; then
  echo "phase-03-install-boot: FAILED — ${FAILURE_REASON:-Evidence Record was rejected by anti-bluff-validate.sh}" >&2
  exit 1
fi

echo "phase-03-install-boot: lava-api-go installed, started, and confirmed healthy"
exit 0

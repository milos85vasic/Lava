#!/usr/bin/env bash
# scripts/pipeline/phase-04-live-verify-api.sh — FR-008 live-verification
# phase, lava-api-go half ONLY. The :api-app-on-emulator half of FR-008 (the
# on-device API-server app driven via a real Android emulator) is a SEPARATE
# concern, implemented by a separate script — this script never touches
# Gradle, ADB, or any emulator; it makes real HTTP requests over the network
# against the already-installed, already-running standalone lava-api-go
# systemd --user service (installed by phase-03-install-boot.sh).
#
# This phase does NOT reimplement health-checking (phase-03 already does
# that, via tools/lava-containers/bin/lava-containers -cmd=status, and
# records its own Evidence Record for "is the container healthy"). What
# THIS phase adds, per FR-008, is proof that the REAL, RUNNING service
# genuinely answers REAL application-level HTTP requests with REAL,
# meaningful response bodies — not merely that its process/container
# lifecycle is "Up" (§6.B: "container Up is not application-healthy" — the
# exact bluff class this phase exists to rule out at the HTTP-application
# layer, one layer above phase-03's container/orchestrator-level check).
#
# Real base URL discovery (§6.R "no hardcoding a host/port literal"): this
# script contains NO hardcoded port number. It reads the REAL configured
# listener from, in priority order:
#   1. ".env" at the repo root, if it defines LAVA_API_LISTEN (a real local
#      operator override would live there);
#   2. "docker-compose.yml"'s `lava-api-go:` service `environment:` block,
#      whose `LAVA_API_LISTEN: ":8443"` line is the actual value the
#      currently-running container was launched with (confirmed by reading
#      docker-compose.yml directly, not assumed) — this is the value
#      actually in effect on this host right now, per this task's own
#      real-world verification.
# Host is always 127.0.0.1: docker-compose.yml's `network_mode: host` on
# the lava-api-go service means `LAVA_API_LISTEN: ":PORT"` binds ALL host
# interfaces (including loopback), which this script's own verification
# pass confirmed by real curl requests. TLS is always required — the same
# compose block unconditionally mounts LAVA_API_TLS_CERT/LAVA_API_TLS_KEY
# for this service (self-signed cert; requests use curl -k, matching the
# existing precedent at scripts/distribute-api-remote.sh:206
# `curl -fsSk "https://$REMOTE_HOST:$PORT/health"`).
#
# Real endpoints exercised (per lava-api-go/internal/router/router.go —
# read directly, not guessed): GET /health, GET /ready, GET /providers.
# These three are registered BEFORE the Lava-Auth middleware in Build() (see
# router.go's comments on /health+/ready and on /providers), so they are the
# ONLY endpoints reachable without a real Lava-Auth HMAC header — every
# /v1/:provider/... route requires that header, and per this phase's own
# scope (no destructive calls, no real tracker credentials) those routes are
# intentionally NOT exercised here. All three ARE real, meaningful,
# read-only, safe endpoints: /health (liveness), /ready (readiness — real
# downstream dependency check per observability.ReadinessHandler), /providers
# (the real provider catalogue — genuine business content: provider ids,
# capabilities, authType, base URLs).
#
# Usage:
#   scripts/pipeline/phase-04-live-verify-api.sh <run_id> [repo-path]
#
# <run_id> MUST already have a report.json (via lib/run-report.sh's
# init_run_report). This script appends to that same report.json under
# phase name "live_verify" (lib/run-report.sh's _RUN_REPORT_PHASE_NAMES) —
# it never creates a new run. If a sibling phase (the api-app/emulator half)
# also appends under "live_verify", both entries legitimately coexist in the
# report's phases[] array — the schema allows repeated phase names, each
# call to append_phase_result adds one more entry.
#
# WARNING: this script has REAL side effects on the network — it issues
# real GET requests against the real, already-running lava-api-go service
# on this host. It does not start, stop, or modify that service; it does
# not touch Gradle, ADB, or any emulator.
#
# Exit codes:
#   0 - base URL determined AND all 3 real HTTP requests returned a real
#       2xx status WITH a real non-empty response body AND all 3 Evidence
#       Records were anti-bluff-validated.
#   1 - a real failure occurred (base URL undeterminable, a request
#       genuinely failed at the connection level, returned non-2xx, or
#       returned 2xx with an empty body, or any Evidence Record was
#       REJECTED by anti-bluff-validate.sh). The
#       failure is recorded as FAIL in report.json's "live_verify" phase
#       entry either way — never fabricated as success.
#   2 - usage/precondition error (missing run_id, report.json absent).

set -uo pipefail
# Deliberately NOT `set -e`: every risky step below (URL discovery, each
# curl request) is explicitly guarded via `if`/direct `$?` capture, because
# a non-zero exit from any one of them is a REAL, WANTED signal for this
# phase's own outcome, not a script bug to abort on. Relying on inherited
# errexit here would stop this script before it could record the honest
# failure it was invoked to observe.

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
  echo "phase-04-live-verify-api: usage: $0 <run_id> [repo-path]" >&2
  exit 2
fi

REPO_PATH="${REPO_PATH_OVERRIDE:-$REPO_ROOT}"

REPORT_PATH=".lava-ci-evidence/pipeline-runs/${RUN_ID}/report.json"
if [[ ! -f "$REPORT_PATH" ]]; then
  echo "phase-04-live-verify-api: precondition failed — $REPORT_PATH does not exist (call init_run_report first)" >&2
  exit 2
fi

PHASE_DIR=".lava-ci-evidence/pipeline-runs/${RUN_ID}/phase-04"
RAW_DIR="${PHASE_DIR}/hermetic-script/raw"
mkdir -p "$RAW_DIR"

SUMMARY_LOG="${RAW_DIR}/live-verify-api-summary.log"
: > "$SUMMARY_LOG"

_log() {
  echo "$*" | tee -a "$SUMMARY_LOG"
}

START_TS=$(date +%s)

_log "phase-04-live-verify-api: repo=${REPO_PATH}"
_log "phase-04-live-verify-api: run_id=${RUN_ID}"
_log ""

OVERALL_OK="true"
FAILURE_REASON=""

# ---------------------------------------------------------------------------
# Step 1: determine the REAL base URL (§6.R — no hardcoded host/port literal
# in this script; the value is read from real configuration).
# ---------------------------------------------------------------------------
_log "=== Step 1: determine real base URL ==="

LISTEN_VAL=""
LISTEN_SOURCE=""

ENV_FILE="${REPO_PATH}/.env"
if [[ -f "$ENV_FILE" ]]; then
  ENV_LISTEN_LINE="$(grep -E '^LAVA_API_LISTEN=' "$ENV_FILE" | tail -1 || true)"
  if [[ -n "$ENV_LISTEN_LINE" ]]; then
    LISTEN_VAL="${ENV_LISTEN_LINE#LAVA_API_LISTEN=}"
    LISTEN_SOURCE="${ENV_FILE} (LAVA_API_LISTEN=...)"
  fi
fi

COMPOSE_FILE="${REPO_PATH}/docker-compose.yml"
if [[ -z "$LISTEN_VAL" && -f "$COMPOSE_FILE" ]]; then
  COMPOSE_LISTEN_LINE="$(grep -E '^\s*LAVA_API_LISTEN:' "$COMPOSE_FILE" | head -1 || true)"
  if [[ -n "$COMPOSE_LISTEN_LINE" ]]; then
    LISTEN_VAL="$(printf '%s' "$COMPOSE_LISTEN_LINE" | sed -E 's/^[^"]*"([^"]*)".*/\1/')"
    LISTEN_SOURCE="${COMPOSE_FILE} (lava-api-go: environment: LAVA_API_LISTEN: \"${LISTEN_VAL}\")"
  fi
fi

PORT="$(printf '%s' "$LISTEN_VAL" | tr -dc '0-9')"

if [[ -z "$PORT" ]]; then
  OVERALL_OK="false"
  FAILURE_REASON="could not determine the real lava-api-go listen port from either .env or docker-compose.yml"
  _log "phase-04-live-verify-api: FAILED — ${FAILURE_REASON}"
  BASE_URL=""
else
  BASE_URL="https://127.0.0.1:${PORT}"
  _log "phase-04-live-verify-api: real listen value '${LISTEN_VAL}' read from ${LISTEN_SOURCE}"
  _log "phase-04-live-verify-api: real base URL = ${BASE_URL}"
fi
_log ""

END_TS_STEP1=$(date +%s)

# ---------------------------------------------------------------------------
# Step 2: make REAL HTTP requests against the REAL running service, one
# Evidence Record per request (per this feature's task instructions — a
# deliberate deviation from phase-03's single-summary-record pattern,
# because here each request IS its own independently falsifiable check).
# ---------------------------------------------------------------------------
_log "=== Step 2: real HTTP requests against the real running service ==="

# test_id:path pairs. All three are registered in router.go's Build()
# BEFORE the Lava-Auth middleware, so all three are real, safe,
# unauthenticated, non-destructive, read-only endpoints (see file header).
REQUESTS=(
  "lava-api-go-live-verify-health:/health"
  "lava-api-go-live-verify-ready:/ready"
  "lava-api-go-live-verify-providers:/providers"
)

declare -a RECORD_PATHS=()
declare -a RECORD_STATUSES=()

for entry in "${REQUESTS[@]}"; do
  test_id="${entry%%:*}"
  req_path="${entry#*:}"

  if [[ -z "$BASE_URL" ]]; then
    _log "--- ${test_id} (${req_path}) — SKIPPED: no base URL available ---"
    raw_file="${RAW_DIR}/${test_id}.log"
    printf 'phase-04-live-verify-api: request skipped — base URL could not be determined (see step 1 failure)\n' > "$raw_file"
    assertion_summary="GET ${req_path} was not attempted because the real lava-api-go base URL could not be determined from .env or docker-compose.yml (see step 1 failure: ${FAILURE_REASON})"
    record_path=""
    if ! record_path="$(write_evidence_record "$PHASE_DIR" "$test_id" "hermetic-script" "GET ${req_path}" "SKIPPED" "$assertion_summary" "$raw_file")"; then
      echo "phase-04-live-verify-api: ERROR — write_evidence_record failed for ${test_id}" >&2
      OVERALL_OK="false"
      continue
    fi
    if validate_evidence_record "$record_path" >/dev/null 2>&1; then
      RECORD_STATUSES+=("validated")
    else
      RECORD_STATUSES+=("$(jq -r '.anti_bluff_status' "$record_path" 2>/dev/null || echo 'REJECTED: unknown')")
      OVERALL_OK="false"
    fi
    RECORD_PATHS+=("$record_path")
    continue
  fi

  url="${BASE_URL}${req_path}"
  raw_file="${RAW_DIR}/${test_id}.log"
  body_file="${raw_file}.body"

  _log "--- ${test_id} (${req_path}) ---"
  _log "\$ curl -sk -o <body> -w '%{http_code}' \"${url}\""

  HTTP_STATUS="$(curl -sk -o "$body_file" -w '%{http_code}' --max-time 15 "$url" 2>"${raw_file}.stderr")"
  CURL_RC=$?
  RESPONSE_BODY="$(cat "$body_file" 2>/dev/null || true)"
  CURL_STDERR="$(cat "${raw_file}.stderr" 2>/dev/null || true)"
  rm -f "${raw_file}.stderr"

  {
    echo "command: curl -sk -o <body> -w '%{http_code}' \"${url}\""
    echo "curl_exit_code: ${CURL_RC}"
    echo "http_status: ${HTTP_STATUS}"
    echo "response_body:"
    echo "${RESPONSE_BODY}"
    if [[ -n "$CURL_STDERR" ]]; then
      echo "curl_stderr:"
      echo "${CURL_STDERR}"
    fi
  } > "$raw_file"

  _log "http_status: ${HTTP_STATUS} (curl_exit_code=${CURL_RC})"

  # An empty body is not a passing answer (forensic anchor, 2026-08-21).
  # This phase's whole reason to exist, per this file's own header, is to
  # prove the running service "genuinely answers REAL application-level HTTP
  # requests with REAL, meaningful response bodies — not merely that its
  # process/container lifecycle is 'Up' (§6.B)". The verdict below used to be
  # computed from curl's exit code and the status code only: the body was
  # read, truncated to 300 chars, interpolated into assertion_summary — and
  # never asserted on. A service answering 200 with zero bytes therefore
  # passed the entire phase, producing three anti-bluff-VALIDATED Evidence
  # Records whose assertion_summary ended "...returned HTTP 200 with response
  # body: " and nothing after it. The anti-bluff validator could not catch it
  # either: its "raw_output_ref must be non-empty" rule was satisfied by the
  # scaffolding lines this script writes into the raw file itself, which are
  # there whether or not the server said anything. All three endpoints
  # exercised here return real JSON in the real service
  # (lava-api-go/internal/observability/health.go returns {"status":"alive"}
  # and {"status":"ready"}; /providers returns the provider catalogue), so
  # this rejects nothing the real service legitimately does. Regression
  # coverage: tests/pipeline/test_phase_04_live_verify_response_body.sh.
  BODY_STRIPPED="$(printf '%s' "$RESPONSE_BODY" | tr -d '[:space:]')"

  REQ_OK="true"
  REQ_REASON=""
  if [[ $CURL_RC -ne 0 ]]; then
    REQ_OK="false"
    REQ_REASON="curl exited ${CURL_RC} (connection-level failure) — ${CURL_STDERR:-no stderr captured}"
  elif [[ ! "$HTTP_STATUS" =~ ^2[0-9][0-9]$ ]]; then
    REQ_OK="false"
    REQ_REASON="non-2xx HTTP status ${HTTP_STATUS}"
  elif [[ -z "$BODY_STRIPPED" ]]; then
    REQ_OK="false"
    REQ_REASON="HTTP ${HTTP_STATUS} with an EMPTY response body — a 2xx carrying no content proves a socket answered, not that the application served anything (§6.B). GET ${req_path} on the real service returns a real JSON body."
  fi

  # A real, specific snippet of the real response body (first 300 chars) —
  # never a generic phrase (anti-bluff-validate.sh's rule 1 rejects those).
  SNIPPET="$(printf '%s' "$RESPONSE_BODY" | head -c 300)"

  RESULT="PASS"
  if [[ "$REQ_OK" == "true" ]]; then
    ASSERTION_SUMMARY="GET ${req_path} against the real running lava-api-go service at ${url} returned HTTP ${HTTP_STATUS} with response body: ${SNIPPET}"
  else
    RESULT="FAIL"
    OVERALL_OK="false"
    ASSERTION_SUMMARY="GET ${req_path} against ${url} FAILED: ${REQ_REASON}. curl_exit_code=${CURL_RC} http_status=${HTTP_STATUS:-<none>} response_body_snippet: ${SNIPPET}"
    _log "phase-04-live-verify-api: FAILED — ${REQ_REASON}"
    # Real, honest diagnostic before concluding it's genuinely broken vs. a
    # request-shape mistake — per this task's own verification instructions.
    _log "phase-04-live-verify-api: diagnostic — recent lava-api.service journal:"
    JOURNAL_SNIPPET="$(journalctl --user -u lava-api.service --no-pager -n 20 2>&1 || true)"
    _log "${JOURNAL_SNIPPET}"
  fi

  COMMAND_STR="curl -sk -w '%{http_code}' \"${url}\""
  record_path=""
  if ! record_path="$(write_evidence_record "$PHASE_DIR" "$test_id" "hermetic-script" "$COMMAND_STR" "$RESULT" "$ASSERTION_SUMMARY" "$raw_file")"; then
    echo "phase-04-live-verify-api: ERROR — write_evidence_record failed for ${test_id}" >&2
    OVERALL_OK="false"
    continue
  fi

  if validate_evidence_record "$record_path" >/dev/null 2>&1; then
    RECORD_STATUSES+=("validated")
    _log "phase-04-live-verify-api: Evidence Record anti_bluff_status=validated (${record_path})"
  else
    ANTI_BLUFF_STATUS="$(jq -r '.anti_bluff_status' "$record_path" 2>/dev/null || echo 'REJECTED: unknown')"
    RECORD_STATUSES+=("$ANTI_BLUFF_STATUS")
    OVERALL_OK="false"
    _log "phase-04-live-verify-api: Evidence Record REJECTED by anti-bluff-validate.sh: ${ANTI_BLUFF_STATUS} (${record_path})"
  fi

  RECORD_PATHS+=("$record_path")
  _log ""
done

END_TS=$(date +%s)
DURATION=$((END_TS - START_TS))

# ---------------------------------------------------------------------------
# Step 3: record the phase result into report.json.
# ---------------------------------------------------------------------------
PHASE_RESULT="PASS"
if [[ "$OVERALL_OK" != "true" ]]; then
  PHASE_RESULT="FAIL"
fi

append_phase_result "$RUN_ID" "live_verify" "$PHASE_RESULT" "$DURATION" "$PHASE_DIR"

echo ""
echo "phase-04-live-verify-api: SUMMARY"
echo "  base URL:            ${BASE_URL:-<undetermined>}"
echo "  requests attempted:  ${#RECORD_PATHS[@]}"
for i in "${!RECORD_PATHS[@]}"; do
  echo "    ${RECORD_PATHS[$i]} (anti_bluff_status=${RECORD_STATUSES[$i]:-<none>})"
done
echo "  phase result:        ${PHASE_RESULT}"

if [[ "$PHASE_RESULT" == "FAIL" ]]; then
  echo "phase-04-live-verify-api: FAILED — ${FAILURE_REASON:-at least one real HTTP request or Evidence Record failed; see records above}" >&2
  exit 1
fi

echo "phase-04-live-verify-api: all real HTTP requests against the real running lava-api-go service succeeded and were anti-bluff-validated"
exit 0

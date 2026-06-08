#!/usr/bin/env bash
# validate-jackett-sidecar.sh — end-to-end readiness check for the Jackett
# Torznab sidecar (+ optional FlareSolverr) in the Lava local stack.
#
# WHAT IT PROVES (anti-bluff, §6.B / §6.J):
#   "Up" is NOT "healthy". This script does NOT assert container State==running.
#   It brings the sidecar up, waits for the compose healthchecks to go HEALTHY,
#   then independently re-runs the REAL user-visible surface from the host:
#     - Jackett Torznab caps:  GET .../results/torznab/api?t=caps&apikey=…
#                              => must be HTTP 200 + a parseable <caps>/<error>
#                                 XML body (an api_key-authenticated response).
#     - FlareSolverr liveness: POST /v1 {"cmd":"sessions.list"} (no GET /health
#                              endpoint exists) => must be HTTP 200 + JSON
#                              {"status":"ok",...}.
#   Verbatim responses are printed so a green result is auditable, not asserted.
#   It always tears the sidecar down on exit (success OR failure).
#
# RUNTIME: auto-detects podman (preferred) then docker. Rootless. NO sudo (§6.U).
# RESOURCE: read-only curls + one short-lived sidecar; no Gradle, no emulator,
#           no build (§6.T.2). Bash-portable on macOS (no GNU-only flags).
#
# §6.R no-hardcoding: ports/hosts/api_key all come from .env (loopback bind by
# default). The env var names match lava-api-go's config exactly
# (LAVA_API_JACKETT_APIKEY / LAVA_API_JACKETT_DEFAULT_INDEXER) and the compose
# fragment's host-port knobs (LAVA_JACKETT_HOST_PORT / LAVA_FLARESOLVERR_HOST_PORT).
#
# PRECONDITION (cannot be automated — Jackett indexer config is stateful):
#   The Jackett /config volume must already hold a generated api_key, and at
#   least one indexer must be added for the caps probe to return a non-error
#   <caps>. On a brand-new /config the FIRST run writes a fresh api_key but has
#   ZERO indexers; the caps probe for `all` still returns 200 with an empty
#   <caps>, which this script accepts as "Torznab surface live + api_key valid".
#   See docs/qa/jackett-sidecar-deployment.md for the one-time indexer-add step.
#
# Usage:
#   scripts/validate-jackett-sidecar.sh                 # jackett only
#   scripts/validate-jackett-sidecar.sh --cloudflare    # + FlareSolverr (IPTorrents)
#   scripts/validate-jackett-sidecar.sh --keep          # leave the stack up after
#   scripts/validate-jackett-sidecar.sh --runtime docker
#
# Exit: 0 = all probed surfaces PASS; 1 = a probe FAILED; 2 = config/usage error.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FRAGMENT="${REPO_ROOT}/tools/lava-containers/docker-compose.jackett.yml"
ENV_FILE="${REPO_ROOT}/.env"

PROFILE="jackett"          # default: jackett only
WITH_CLOUDFLARE=0
KEEP=0
RUNTIME=""                 # auto-detect
PROJECT="lava-jackett-validate"   # isolated compose project name

while [[ $# -gt 0 ]]; do
  case "$1" in
    --cloudflare) PROFILE="cloudflare"; WITH_CLOUDFLARE=1; shift ;;
    --keep)       KEEP=1; shift ;;
    --runtime)    RUNTIME="${2:-}"; shift 2 ;;
    -h|--help)    grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "validate-jackett-sidecar: unknown arg: $1" >&2; exit 2 ;;
  esac
done

# --- container runtime detection (podman preferred, then docker) ------------
if [[ -z "$RUNTIME" ]]; then
  if command -v podman >/dev/null 2>&1; then RUNTIME="podman"
  elif command -v docker >/dev/null 2>&1; then RUNTIME="docker"
  else
    echo "ERROR: neither podman nor docker on PATH. Cannot validate." >&2
    exit 2
  fi
fi
if ! command -v "$RUNTIME" >/dev/null 2>&1; then
  echo "ERROR: --runtime='$RUNTIME' but the binary is not on PATH." >&2
  exit 2
fi
COMPOSE=("$RUNTIME" compose)
echo "== Jackett sidecar validation =="
echo "   runtime:  $RUNTIME"
echo "   fragment: $FRAGMENT"
echo "   profile:  $PROFILE"

[[ -f "$FRAGMENT" ]] || { echo "ERROR: compose fragment not found: $FRAGMENT" >&2; exit 2; }

# --- load .env (§6.R: api_key + ports come from here, never literals) -------
if [[ -f "$ENV_FILE" ]]; then
  set -a; . "$ENV_FILE"; set +a
  echo "   .env:     loaded ($ENV_FILE)"
else
  echo "   .env:     NOT FOUND — relying on the shell environment."
fi

# Required: the api_key. Without it the healthcheck (and lava-api-go) cannot
# authenticate. Fail loudly per §6.J rather than probing an unauth surface.
if [[ -z "${LAVA_API_JACKETT_APIKEY:-}" || "${LAVA_API_JACKETT_APIKEY}" == "your_jackett_api_key" ]]; then
  echo "ERROR: LAVA_API_JACKETT_APIKEY is unset or still the placeholder." >&2
  echo "       Start Jackett once, copy the generated api_key from its /config" >&2
  echo "       (ServerConfig.json) into .env, then re-run. See" >&2
  echo "       docs/qa/jackett-sidecar-deployment.md." >&2
  exit 2
fi

JK_HOST="${LAVA_JACKETT_BIND_HOST:-127.0.0.1}"
JK_PORT="${LAVA_JACKETT_HOST_PORT:-9117}"
FS_HOST="${LAVA_FLARESOLVERR_BIND_HOST:-127.0.0.1}"
FS_PORT="${LAVA_FLARESOLVERR_HOST_PORT:-8191}"
INDEXER="${LAVA_API_JACKETT_DEFAULT_INDEXER:-all}"

# --- teardown trap (always runs unless --keep) ------------------------------
teardown() {
  if [[ "$KEEP" -eq 1 ]]; then
    echo "== --keep: leaving the sidecar running (project: $PROJECT) =="
    return
  fi
  echo "== teardown =="
  "${COMPOSE[@]}" -p "$PROJECT" -f "$FRAGMENT" --profile jackett --profile cloudflare \
    down --remove-orphans >/dev/null 2>&1 || true
}
trap teardown EXIT

# --- ensure the external lava-net bridge exists -----------------------------
# The fragment declares lava-net as external; create it standalone if the root
# stack hasn't already (idempotent).
if ! "$RUNTIME" network inspect lava-net >/dev/null 2>&1; then
  echo "   creating external network: lava-net"
  "$RUNTIME" network create lava-net >/dev/null
fi

# --- bring the sidecar up ---------------------------------------------------
echo "== bringing up sidecar (profile: $PROFILE) =="
UP_PROFILES=(--profile jackett)
[[ "$WITH_CLOUDFLARE" -eq 1 ]] && UP_PROFILES+=(--profile cloudflare)
"${COMPOSE[@]}" -p "$PROJECT" -f "$FRAGMENT" "${UP_PROFILES[@]}" up -d

# --- wait for compose healthchecks to go HEALTHY ----------------------------
# We poll the runtime's reported health state (the §6.B-correct caps probe runs
# inside the container). Bounded wait so we never hang the host.
wait_healthy() {
  local cname="$1" max="${2:-60}" i=0 state
  echo "   waiting for $cname to report healthy (max ${max}s)..."
  while [[ "$i" -lt "$max" ]]; do
    state="$("$RUNTIME" inspect -f '{{.State.Health.Status}}' "$cname" 2>/dev/null || echo "unknown")"
    case "$state" in
      healthy)   echo "   $cname: healthy"; return 0 ;;
      unhealthy) echo "   $cname: UNHEALTHY"; return 1 ;;
    esac
    sleep 2; i=$((i + 2))
  done
  echo "   $cname: TIMEOUT after ${max}s (last state: ${state:-unknown})"
  return 1
}

OVERALL=0

if ! wait_healthy lava-jackett 90; then
  echo "   --- lava-jackett logs (tail) ---"
  "$RUNTIME" logs --tail 40 lava-jackett 2>&1 || true
  OVERALL=1
fi
if [[ "$WITH_CLOUDFLARE" -eq 1 ]]; then
  if ! wait_healthy lava-flaresolverr 120; then
    echo "   --- lava-flaresolverr logs (tail) ---"
    "$RUNTIME" logs --tail 40 lava-flaresolverr 2>&1 || true
    OVERALL=1
  fi
fi

# --- independent host-side probes (the user-visible surface) ----------------
# Re-running the surface from the HOST (not just trusting the in-container
# healthcheck) is the §6.J discipline: prove the published loopback port is the
# same Torznab surface lava-api-go will consume.

echo "== probe 1/2: Jackett Torznab caps (HTTP 200 + parseable XML) =="
CAPS_URL="http://${JK_HOST}:${JK_PORT}/api/v2.0/indexers/${INDEXER}/results/torznab/api?t=caps&apikey=${LAVA_API_JACKETT_APIKEY}"
# Redacted URL for the log (never print the api_key — §6.H).
echo "   GET http://${JK_HOST}:${JK_PORT}/api/v2.0/indexers/${INDEXER}/results/torznab/api?t=caps&apikey=<redacted>"
CAPS_BODY_FILE="$(mktemp)"; CAPS_CODE="000"
CAPS_CODE="$(curl -fsS -o "$CAPS_BODY_FILE" -w '%{http_code}' --max-time 20 "$CAPS_URL" 2>/dev/null || echo "000")"
echo "   HTTP status: $CAPS_CODE"
echo "   --- body (first 30 lines, verbatim) ---"
sed -n '1,30p' "$CAPS_BODY_FILE" 2>/dev/null || true
echo "   ----------------------------------------"
# Accept: 200 AND a Torznab XML document. An empty <caps/> (no indexers yet) is
# acceptable; a <error code=...> with an auth failure is NOT.
if [[ "$CAPS_CODE" == "200" ]] && grep -qiE '<caps|<\?xml|<indexers|<rss' "$CAPS_BODY_FILE"; then
  if grep -qiE 'incorrect api ?key|invalid api ?key|<error[^>]*code="?100' "$CAPS_BODY_FILE"; then
    echo "   PROBE 1: FAIL (api_key rejected by Jackett)"; OVERALL=1
  else
    echo "   PROBE 1: PASS (Torznab surface live + api_key accepted)"
  fi
else
  echo "   PROBE 1: FAIL (status $CAPS_CODE / body not a Torznab XML doc)"; OVERALL=1
fi
rm -f "$CAPS_BODY_FILE"

if [[ "$WITH_CLOUDFLARE" -eq 1 ]]; then
  echo "== probe 2/2: FlareSolverr liveness (POST /v1 sessions.list → 200 JSON) =="
  FS_URL="http://${FS_HOST}:${FS_PORT}/v1"
  echo "   POST ${FS_URL}  {\"cmd\":\"sessions.list\"}"
  FS_BODY_FILE="$(mktemp)"; FS_CODE="000"
  FS_CODE="$(curl -fsS -o "$FS_BODY_FILE" -w '%{http_code}' --max-time 20 \
    -H 'Content-Type: application/json' -d '{"cmd":"sessions.list"}' "$FS_URL" 2>/dev/null || echo "000")"
  echo "   HTTP status: $FS_CODE"
  echo "   --- body (verbatim) ---"
  cat "$FS_BODY_FILE" 2>/dev/null; echo
  echo "   -----------------------"
  if [[ "$FS_CODE" == "200" ]] && grep -qiE '"status"[[:space:]]*:[[:space:]]*"ok"' "$FS_BODY_FILE"; then
    echo "   PROBE 2: PASS (FlareSolverr ready)"
  else
    echo "   PROBE 2: FAIL (status $FS_CODE / no status:ok)"; OVERALL=1
  fi
  rm -f "$FS_BODY_FILE"
else
  echo "== probe 2/2: FlareSolverr — SKIPPED (run with --cloudflare to validate IPTorrents path) =="
fi

echo
if [[ "$OVERALL" -eq 0 ]]; then
  echo "== RESULT: PASS — Jackett sidecar is READY for lava-api-go =="
else
  echo "== RESULT: FAIL — see probe output + container logs above =="
fi
exit "$OVERALL"

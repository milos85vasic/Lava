#!/usr/bin/env bash
# scripts/autonomous-qa/lib-jackett.sh
# ---------------------------------------------------------------------------
# Bring up / tear down the Jackett (Torznab) + FlareSolverr (Cloudflare-solver)
# sidecar so lava-api-go can serve Cloudflare-protected tracker searches
# (notably IPTorrents) in the Phase-2 autonomous-QA matrix.
#
# This is a thin autonomous-QA wrapper around the SAME mechanism the project's
# canonical validator uses (scripts/validate-jackett-sidecar.sh):
#   - runtime: podman (preferred) then docker — rootless, NO sudo (§6.U).
#   - bring-up: `<runtime> compose -p <project> -f <fragment>
#                --profile jackett --profile cloudflare up -d`
#     against tools/lava-containers/docker-compose.jackett.yml.
#   - the Jackett/FlareSolverr ports bind to host LOOPBACK only (127.0.0.1);
#     lava-api-go (host-net) reaches Jackett at http://127.0.0.1:9117 and holds
#     the api_key server-side. The Android app NEVER talks to Jackett (§6.H).
#
# Why raw `<runtime> compose` here and NOT ./start.sh / lava-containers:
#   The Jackett fragment is bridge + loopback (no `network_mode: host`), so
#   plain compose orchestrates it correctly — matching validate-jackett-sidecar.
#   ./start.sh + BUILDAH_FORMAT=docker are only needed for the host-net api-go
#   profile (whose locally-built image must carry a HEALTHCHECK); the Jackett /
#   FlareSolverr images are PULLED (lscr.io / ghcr.io) with their HEALTHCHECK
#   baked in + redeclared inline in the fragment, so no local build happens here.
#
# Anti-bluff (§6.B / §6.J): "Up" is NOT "healthy". jackett_up does not assert
# container State==running; it polls the REAL user-visible Torznab `t=caps`
# surface from the host until HTTP 200 (and, with the cloudflare profile, the
# FlareSolverr POST /v1 sessions.list surface) and FAILS LOUDLY on timeout.
#
# §6.R no-hardcoding: every host/port/api_key/image/indexer comes from .env
# (placeholders in .env.example). The var names match lava-api-go's config and
# the compose fragment EXACTLY (LAVA_API_JACKETT_APIKEY /
# LAVA_API_JACKETT_DEFAULT_INDEXER / LAVA_JACKETT_HOST_PORT /
# LAVA_FLARESOLVERR_HOST_PORT / LAVA_JACKETT_BIND_HOST / ...).
#
# §6.H: the Jackett api_key is NEVER echoed. URLs are logged with the key
# redacted to <redacted>.
#
# Functions:  jackett_up  jackett_down
#
# Usage (source, then call):
#   source scripts/autonomous-qa/lib-jackett.sh
#   jackett_up      # bring up jackett + flaresolverr, block until ready
#   ...run Phase-2 IPTorrents searches against lava-api-go...
#   jackett_down    # tear it all down
#
# Env knobs (all optional; defaults mirror .env / the fragment):
#   LAVA_QA_CONTAINER_RUNTIME   force "podman" or "docker" (default: auto-detect)
#   LAVA_QA_JACKETT_CLOUDFLARE  0 = jackett only (skip the RAM-heavy FlareSolverr);
#                               default 1 = jackett + cloudflare (IPTorrents path)
#   LAVA_QA_JACKETT_TIMEOUT     readiness timeout seconds (default 120)
#
# Exit/return: 0 = sidecar READY (probed); non-zero = failed (logs dumped).
# ---------------------------------------------------------------------------
set -euo pipefail

QA_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$QA_DIR/../.." && pwd)"
JACKETT_FRAGMENT="$REPO_ROOT/tools/lava-containers/docker-compose.jackett.yml"
JACKETT_PROJECT="lava-jackett-qa"          # isolated compose project name
JACKETT_NET="lava-net"                     # external bridge the fragment expects

# --- internal: container runtime (podman preferred, then docker) ------------
_jackett_runtime() {
  if [[ -n "${LAVA_QA_CONTAINER_RUNTIME:-}" ]]; then
    command -v "$LAVA_QA_CONTAINER_RUNTIME" >/dev/null 2>&1 \
      && { echo "$LAVA_QA_CONTAINER_RUNTIME"; return 0; }
    echo "[jackett] ERROR LAVA_QA_CONTAINER_RUNTIME='$LAVA_QA_CONTAINER_RUNTIME' not on PATH" >&2
    return 1
  fi
  if command -v podman >/dev/null 2>&1; then echo "podman"; return 0; fi
  if command -v docker >/dev/null 2>&1; then echo "docker"; return 0; fi
  echo "[jackett] ERROR neither podman nor docker on PATH" >&2
  return 1
}

# --- internal: load .env safely; NEVER echo a secret value (§6.H) -----------
# Mirrors validate-jackett-sidecar.sh: api_key + ports come from .env, not
# literals (§6.R). Anchored to REPO_ROOT so it works no matter the caller's cwd.
_jackett_load_env() {
  set -a
  # shellcheck disable=SC1091
  [ -f "$REPO_ROOT/.env" ] && . "$REPO_ROOT/.env"
  set +a
}

# jackett_up — bring up Jackett (+ FlareSolverr unless disabled) and BLOCK until
# the Torznab surface answers, or fail loudly on timeout.
jackett_up() {
  local runtime; runtime="$(_jackett_runtime)" || return 1
  _jackett_load_env

  [[ -f "$JACKETT_FRAGMENT" ]] || {
    echo "[jackett] ERROR compose fragment not found: $JACKETT_FRAGMENT" >&2; return 1; }

  # Resolve the loopback surface from .env (defaults match the fragment).
  local jk_host="${LAVA_JACKETT_BIND_HOST:-127.0.0.1}"
  local jk_port="${LAVA_JACKETT_HOST_PORT:-9117}"
  local fs_host="${LAVA_FLARESOLVERR_BIND_HOST:-127.0.0.1}"
  local fs_port="${LAVA_FLARESOLVERR_HOST_PORT:-8191}"
  local indexer="${LAVA_API_JACKETT_DEFAULT_INDEXER:-all}"
  local with_cf="${LAVA_QA_JACKETT_CLOUDFLARE:-1}"
  local timeout="${LAVA_QA_JACKETT_TIMEOUT:-120}"
  local apikey="${LAVA_API_JACKETT_APIKEY:-}"

  # The caps probe (the real surface lava-api-go consumes) is api_key-authenticated.
  # Without a valid key we cannot prove readiness — fail loudly (§6.J) rather than
  # probe an unauth surface. This is the same contract validate-jackett-sidecar enforces.
  if [[ -z "$apikey" || "$apikey" == "your_jackett_api_key" ]]; then
    echo "[jackett] ERROR LAVA_API_JACKETT_APIKEY is unset or still the placeholder." >&2
    echo "[jackett]       Start Jackett once, copy the generated api_key from its" >&2
    echo "[jackett]       /config (ServerConfig.json) into .env, then re-run." >&2
    echo "[jackett]       See docs/qa/jackett-sidecar-deployment.md." >&2
    return 1
  fi

  local -a compose=("$runtime" compose)
  local -a profiles=(--profile jackett)
  [[ "$with_cf" == "1" ]] && profiles+=(--profile cloudflare)

  echo "[jackett] runtime=$runtime fragment=$JACKETT_FRAGMENT profiles=${profiles[*]}" >&2

  # The fragment declares lava-net as `external: true`; create it standalone if
  # the root stack hasn't already (idempotent — mirrors validate-jackett-sidecar).
  if ! "$runtime" network inspect "$JACKETT_NET" >/dev/null 2>&1; then
    echo "[jackett] creating external network: $JACKETT_NET" >&2
    "$runtime" network create "$JACKETT_NET" >/dev/null
  fi

  echo "[jackett] bringing up sidecar..." >&2
  "${compose[@]}" -p "$JACKETT_PROJECT" -f "$JACKETT_FRAGMENT" "${profiles[@]}" up -d \
    || { echo "[jackett] ERROR compose up failed" >&2; return 1; }

  # --- poll the Jackett Torznab caps readiness endpoint (§6.B real surface) ---
  # GET .../results/torznab/api?t=caps&apikey=<key>  must return HTTP 200 with a
  # parseable Torznab XML doc. The key is REDACTED in every log line (§6.H).
  local caps_url="http://${jk_host}:${jk_port}/api/v2.0/indexers/${indexer}/results/torznab/api?t=caps&apikey=${apikey}"
  echo "[jackett] waiting for Jackett Torznab caps (max ${timeout}s):" >&2
  echo "[jackett]   GET http://${jk_host}:${jk_port}/api/v2.0/indexers/${indexer}/results/torznab/api?t=caps&apikey=<redacted>" >&2

  local waited=0 code body_file
  body_file="$(mktemp)"
  while (( waited < timeout )); do
    code="$(curl -fsS -o "$body_file" -w '%{http_code}' --max-time 15 "$caps_url" 2>/dev/null || echo "000")"
    if [[ "$code" == "200" ]] && grep -qiE '<caps|<\?xml|<indexers|<rss' "$body_file"; then
      # 200 + Torznab XML. Reject an authentication failure body explicitly (§6.J).
      if grep -qiE 'incorrect api ?key|invalid api ?key|<error[^>]*code="?100' "$body_file"; then
        echo "[jackett] ERROR Jackett rejected the api_key (caps returned an auth error)." >&2
        rm -f "$body_file"; jackett_down; return 1
      fi
      echo "[jackett] Jackett ready after ${waited}s (Torznab surface live, api_key accepted)" >&2
      rm -f "$body_file"
      break
    fi
    sleep 3; waited=$((waited + 3))
  done
  if (( waited >= timeout )); then
    echo "[jackett] ERROR Jackett caps probe never returned 200 after ${timeout}s (last code: ${code:-none})" >&2
    rm -f "$body_file"
    "$runtime" logs --tail 40 lava-jackett 2>&1 | sed 's/^/[jackett:log] /' >&2 || true
    jackett_down
    return 1
  fi

  # --- poll FlareSolverr readiness (only with the cloudflare profile) ---------
  # FlareSolverr has NO GET /health; POST /v1 {"cmd":"sessions.list"} → 200 JSON
  # {"status":"ok",...} is the de-facto liveness probe. Chromium warm-up is slow.
  if [[ "$with_cf" == "1" ]]; then
    local fs_url="http://${fs_host}:${fs_port}/v1"
    echo "[jackett] waiting for FlareSolverr (POST /v1 sessions.list, max ${timeout}s): $fs_url" >&2
    waited=0
    local fs_file; fs_file="$(mktemp)"
    while (( waited < timeout )); do
      code="$(curl -fsS -o "$fs_file" -w '%{http_code}' --max-time 15 \
        -H 'Content-Type: application/json' -d '{"cmd":"sessions.list"}' "$fs_url" 2>/dev/null || echo "000")"
      if [[ "$code" == "200" ]] && grep -qiE '"status"[[:space:]]*:[[:space:]]*"ok"' "$fs_file"; then
        echo "[jackett] FlareSolverr ready after ${waited}s" >&2
        rm -f "$fs_file"
        break
      fi
      sleep 3; waited=$((waited + 3))
    done
    if (( waited >= timeout )); then
      echo "[jackett] ERROR FlareSolverr never returned status:ok after ${timeout}s (last code: ${code:-none})" >&2
      rm -f "$fs_file"
      "$runtime" logs --tail 40 lava-flaresolverr 2>&1 | sed 's/^/[flaresolverr:log] /' >&2 || true
      jackett_down
      return 1
    fi
  else
    echo "[jackett] cloudflare profile disabled (LAVA_QA_JACKETT_CLOUDFLARE=0) — FlareSolverr NOT started" >&2
  fi

  echo "[jackett] sidecar READY for lava-api-go" >&2
  return 0
}

# jackett_down — tear the sidecar down (both profiles, idempotent).
jackett_down() {
  local runtime; runtime="$(_jackett_runtime)" || return 1
  [[ -f "$JACKETT_FRAGMENT" ]] || {
    echo "[jackett] WARN compose fragment not found; nothing to tear down: $JACKETT_FRAGMENT" >&2; return 0; }
  echo "[jackett] tearing down sidecar (project: $JACKETT_PROJECT)" >&2
  "$runtime" compose -p "$JACKETT_PROJECT" -f "$JACKETT_FRAGMENT" \
    --profile jackett --profile cloudflare down --remove-orphans >/dev/null 2>&1 || true
  echo "[jackett] sidecar down" >&2
}

# TODO(verify): the readiness timeout default (120s) is UNCONFIRMED against the
# target gate host — the compose fragment itself notes the .NET/Chromium
# cold-start seconds are UNCONFIRMED (start_period 40s/60s are placeholders).
# Measure real cold-boot on the Linux x86_64 gate host and tune
# LAVA_QA_JACKETT_TIMEOUT (or this default) once observed; do not assume.

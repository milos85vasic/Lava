#!/usr/bin/env bash
# scripts/sonar-scan.sh — run a SonarQube analysis of the Lava workspace against
# the local rootless SonarQube stack (docker-compose.sonar.yml).
#
# This is THIN GLUE per the Decoupled Reusable Architecture rule:
#   1. auto-detect podman or docker (§6.U: NEVER sudo/su),
#   2. verify the local Sonar server (127.0.0.1:9000) reports status UP,
#   3. run the sonarsource/sonar-scanner-cli container against the workspace
#      using the SONAR_TOKEN read from .env (§6.R: token from env ONLY, never a
#      literal in this script),
#   4. write scanner output under
#      .lava-ci-evidence/completeness-program/sonar/<date>/.
#
# Usage:
#   ./scripts/sonar-scan.sh up          # bring up the local Sonar stack
#   ./scripts/sonar-scan.sh down        # tear the local Sonar stack down
#   ./scripts/sonar-scan.sh scan        # run the scanner (server must be UP)
#   ./scripts/sonar-scan.sh             # default: scan
#
# Inputs:
#   .env (gitignored) — SONAR_TOKEN (required for `scan`)
#   docker-compose.sonar.yml — the local server + db stack
#   sonar-project.properties — analysis configuration
#
# Constitutional bindings:
#   §6.U  No sudo/su — runtime auto-detect only; no privilege escalation.
#   §6.R  No hardcoding — SONAR_TOKEN comes from .env / env, never a literal.
#   §6.J  Anti-Bluff — real failures propagate (set -euo pipefail); a scan that
#         did not run does NOT print success. No fabricated "passed" output.

set -euo pipefail

# ── Resolve repo root (this script lives in scripts/) ────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
COMPOSE_FILE="${REPO_ROOT}/docker-compose.sonar.yml"
SONAR_HOST_URL="${SONAR_HOST_URL:-http://127.0.0.1:9000}"
DATE_UTC="$(date -u +%Y-%m-%d)"
EVIDENCE_DIR="${REPO_ROOT}/.lava-ci-evidence/completeness-program/sonar/${DATE_UTC}"

# ── §6.U: auto-detect a rootless container runtime, no sudo ──────────────────
detect_runtime() {
  if command -v podman >/dev/null 2>&1; then
    echo "podman"
  elif command -v docker >/dev/null 2>&1; then
    echo "docker"
  else
    echo "" >&2
    echo "ERROR: neither podman nor docker found on PATH (§6.U: no sudo install)." >&2
    return 1
  fi
}

compose() {
  local rt="$1"; shift
  # Both runtimes support the `compose` subcommand on modern installs.
  "${rt}" compose -f "${COMPOSE_FILE}" "$@"
}

load_env_token() {
  # §6.R: read SONAR_TOKEN from .env if not already in the environment.
  if [ -z "${SONAR_TOKEN:-}" ] && [ -f "${REPO_ROOT}/.env" ]; then
    # shellcheck disable=SC2046
    local v
    v="$(grep -E '^SONAR_TOKEN=' "${REPO_ROOT}/.env" | head -n1 | cut -d= -f2-)"
    SONAR_TOKEN="${v}"
  fi
}

cmd_up() {
  local rt; rt="$(detect_runtime)"
  echo ">> Bringing up SonarQube stack via ${rt} (rootless, 127.0.0.1 only)…"
  compose "${rt}" up -d
  echo ">> Stack started. Poll readiness with: $0 status"
}

cmd_down() {
  local rt; rt="$(detect_runtime)"
  echo ">> Tearing down SonarQube stack via ${rt}…"
  compose "${rt}" down
}

cmd_status() {
  echo ">> Querying ${SONAR_HOST_URL}/api/system/status …"
  if curl -sf "${SONAR_HOST_URL}/api/system/status" 2>/dev/null; then
    echo
    return 0
  fi
  echo "Sonar server not reachable / not UP yet at ${SONAR_HOST_URL}." >&2
  return 1
}

cmd_scan() {
  local rt; rt="$(detect_runtime)"
  load_env_token
  if [ -z "${SONAR_TOKEN:-}" ]; then
    echo "ERROR: SONAR_TOKEN is not set. Add it to .env (see .env.example) or" >&2
    echo "       export it. Generate a token in the local Sonar UI:" >&2
    echo "       ${SONAR_HOST_URL} → My Account → Security → Generate Tokens." >&2
    echo "       (§6.R: the token is read from env ONLY — never hardcoded here.)" >&2
    return 1
  fi

  echo ">> Verifying Sonar server is UP before scanning (anti-bluff: no scan" \
       "against a down server)…"
  if ! curl -sf "${SONAR_HOST_URL}/api/system/status" 2>/dev/null \
        | grep -q '"status":"UP"'; then
    echo "ERROR: Sonar server at ${SONAR_HOST_URL} is not UP. Run '$0 up' and" >&2
    echo "       wait for '$0 status' to report UP, then re-run the scan." >&2
    return 1
  fi

  mkdir -p "${EVIDENCE_DIR}"
  local log="${EVIDENCE_DIR}/scanner-output.log"
  echo ">> Running sonar-scanner-cli (${rt}) → evidence: ${EVIDENCE_DIR}"

  # On podman/docker, host loopback from inside a container is reachable via the
  # runtime's host alias. We pass SONAR_HOST_URL through so the operator can
  # override (e.g. http://host.containers.internal:9000 on rootless podman).
  local in_container_host
  if [ "${rt}" = "podman" ]; then
    in_container_host="${SONAR_SCANNER_HOST_URL:-http://host.containers.internal:9000}"
  else
    in_container_host="${SONAR_SCANNER_HOST_URL:-http://host.docker.internal:9000}"
  fi

  set +e
  "${rt}" run --rm \
    -e SONAR_HOST_URL="${in_container_host}" \
    -e SONAR_TOKEN="${SONAR_TOKEN}" \
    -v "${REPO_ROOT}:/usr/src:ro,z" \
    sonarsource/sonar-scanner-cli:latest \
    2>&1 | tee "${log}"
  local rc=${PIPESTATUS[0]}
  set -e

  if [ "${rc}" -eq 0 ]; then
    echo ">> Scan completed. Output + report-task: ${EVIDENCE_DIR}"
  else
    echo "ERROR: sonar-scanner exited ${rc}. See ${log}. (Not a bluff pass.)" >&2
  fi
  return "${rc}"
}

main() {
  case "${1:-scan}" in
    up)     cmd_up ;;
    down)   cmd_down ;;
    status) cmd_status ;;
    scan)   cmd_scan ;;
    *)
      echo "Usage: $0 {up|down|status|scan}" >&2
      return 2
      ;;
  esac
}

main "$@"

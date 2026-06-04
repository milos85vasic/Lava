#!/usr/bin/env bash
# scripts/snyk-scan.sh — run Snyk dependency + SAST scans of the Lava Gradle and
# Go projects using the containerized snyk/snyk image (rootless podman/docker).
#
# This is THIN GLUE per the Decoupled Reusable Architecture rule:
#   1. auto-detect podman or docker (§6.U: NEVER sudo/su),
#   2. require SNYK_TOKEN from .env / env (§6.R: token from env ONLY; NEVER run
#      interactive `snyk auth`, which is forbidden here),
#   3. run `snyk test` (open-source dependency vulnerabilities) and
#      `snyk code test` (SAST) against the workspace,
#   4. write SARIF/JSON output under
#      .lava-ci-evidence/completeness-program/snyk/<date>/.
#
# Usage:
#   ./scripts/snyk-scan.sh            # deps + code (SAST) scans, all projects
#   ./scripts/snyk-scan.sh deps       # dependency scan only
#   ./scripts/snyk-scan.sh code       # SAST (snyk code) only
#
# Inputs:
#   .env (gitignored) — SNYK_TOKEN (required)
#   .snyk             — ignore policy (optional)
#
# Constitutional bindings:
#   §6.U  No sudo/su — runtime auto-detect only.
#   §6.R  No hardcoding — SNYK_TOKEN from .env / env, never a literal.
#   §6.J  Anti-Bluff — if SNYK_TOKEN is missing the script EXITS with a clear
#         message; it does NOT print a fake "passed". Real scan exit codes
#         (1 = vulns found, 2 = error) are surfaced, not swallowed.

set -uo pipefail   # NB: not -e — snyk returns 1 when issues are found, which is
                   # a successful scan with findings, not a script failure.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DATE_UTC="$(date -u +%Y-%m-%d)"
EVIDENCE_DIR="${REPO_ROOT}/.lava-ci-evidence/completeness-program/snyk/${DATE_UTC}"
SNYK_IMAGE="${SNYK_IMAGE:-snyk/snyk:linux}"

detect_runtime() {
  if command -v podman >/dev/null 2>&1; then
    echo "podman"
  elif command -v docker >/dev/null 2>&1; then
    echo "docker"
  else
    echo "ERROR: neither podman nor docker found on PATH (§6.U: no sudo install)." >&2
    return 1
  fi
}

load_env_token() {
  if [ -z "${SNYK_TOKEN:-}" ] && [ -f "${REPO_ROOT}/.env" ]; then
    local v
    v="$(grep -E '^SNYK_TOKEN=' "${REPO_ROOT}/.env" | head -n1 | cut -d= -f2-)"
    SNYK_TOKEN="${v}"
  fi
}

require_token() {
  load_env_token
  if [ -z "${SNYK_TOKEN:-}" ]; then
    echo "ERROR: SNYK_TOKEN is not set. Set SNYK_TOKEN in .env (see .env.example)." >&2
    echo "       Obtain it from https://app.snyk.io/account (Auth Token)." >&2
    echo "       Interactive 'snyk auth' is NOT used here by design (§6.R/§6.U)." >&2
    echo "       This is NOT a passed scan — nothing ran." >&2
    return 1
  fi
}

# run_snyk <runtime> <evidence-basename> <snyk-subcommand...>
run_snyk() {
  local rt="$1"; shift
  local out_base="$1"; shift
  local sarif="${EVIDENCE_DIR}/${out_base}.sarif"
  local log="${EVIDENCE_DIR}/${out_base}.log"

  echo ">> snyk ${*} → ${sarif}"
  # --all-projects discovers the Gradle + Go manifests under /project.
  # --sarif-file-output writes machine-readable findings inside the container,
  #   to a path on the mounted volume so it lands in the evidence dir.
  "${rt}" run --rm \
    -e SNYK_TOKEN="${SNYK_TOKEN}" \
    -v "${REPO_ROOT}:/project:z" \
    -w /project \
    "${SNYK_IMAGE}" \
    "$@" \
    --sarif-file-output="/project/.lava-ci-evidence/completeness-program/snyk/${DATE_UTC}/${out_base}.sarif" \
    2>&1 | tee "${log}"
  local rc=${PIPESTATUS[0]}
  case "${rc}" in
    0) echo ">> ${out_base}: no issues found." ;;
    1) echo ">> ${out_base}: scan ran, issues FOUND (see ${sarif})." ;;
    *) echo "ERROR: ${out_base}: snyk error (exit ${rc}). See ${log}." >&2 ;;
  esac
  return "${rc}"
}

cmd_deps() {
  local rt; rt="$(detect_runtime)" || return 1
  require_token || return 1
  mkdir -p "${EVIDENCE_DIR}"
  run_snyk "${rt}" "deps-test" test --all-projects --detection-depth=4
}

cmd_code() {
  local rt; rt="$(detect_runtime)" || return 1
  require_token || return 1
  mkdir -p "${EVIDENCE_DIR}"
  run_snyk "${rt}" "code-test" code test
}

main() {
  local mode="${1:-all}"
  local overall=0
  case "${mode}" in
    deps) cmd_deps; overall=$? ;;
    code) cmd_code; overall=$? ;;
    all)
      cmd_deps; local a=$?
      cmd_code; local b=$?
      # exit 1 (issues found) on either; 2 on a hard error from either.
      if [ "${a}" -ge 2 ] || [ "${b}" -ge 2 ]; then overall=2
      elif [ "${a}" -eq 1 ] || [ "${b}" -eq 1 ]; then overall=1
      else overall=0; fi
      ;;
    *)
      echo "Usage: $0 {deps|code|all}" >&2
      return 2
      ;;
  esac
  return "${overall}"
}

main "$@"

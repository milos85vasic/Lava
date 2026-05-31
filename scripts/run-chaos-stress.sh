#!/usr/bin/env bash
# run-chaos-stress.sh — Lava-side glue for the §11.4.85 (HelixConstitution) Stress + Chaos
# suite. Phase 1: in-process lava-api-go stress/chaos (no Postgres, no emulator, no sudo).
# Phase 1.b: --with-podman adds the operator-gated Postgres-kill (C3) + pool-exhaustion (C4b).
#
# §6.U: NO sudo / su anywhere here.
# §6.T.2: resource-capped — GOMAXPROCS=2 and a single test binary; no Gradle thrash.
# §6.J / §11.4.6: this script runs REAL tests and surfaces REAL exit codes; it fabricates
#                 no metrics. Operator-gated dimensions that don't run are recorded ran=false
#                 by the test itself.
#
# Usage:
#   scripts/run-chaos-stress.sh                 # in-process suite only (Phase 1)
#   scripts/run-chaos-stress.sh --with-podman   # + C3/C4b real-Postgres chaos (Phase 1.b)
#
# Exit: 0 = suite PASS, non-zero = at least one ran dimension FAILED or build error.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
API_DIR="${REPO_ROOT}/lava-api-go"
WITH_PODMAN=0

for arg in "$@"; do
  case "$arg" in
    --with-podman) WITH_PODMAN=1 ;;
    -h|--help)
      grep '^#' "$0" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *) echo "unknown arg: $arg" >&2; exit 2 ;;
  esac
done

echo "== §11.4.85 Stress + Chaos — Phase 1 (in-process, lava-api-go) =="
echo "   repo: ${REPO_ROOT}"
echo "   git:  $(git -C "${REPO_ROOT}" rev-parse HEAD 2>/dev/null || echo UNKNOWN)"
echo "   go:   $(go version 2>/dev/null || echo 'go NOT FOUND')"

cd "${API_DIR}"

# Resource cap per §6.T.2. The stress test is intentionally light (a few thousand loopback
# requests); GOMAXPROCS=2 keeps it well under the host-resource ceiling.
GOMAXPROCS=2 go test -tags stress -run TestStressChaos -v ./tests/stress/...
RC=$?

if [[ "${WITH_PODMAN}" -eq 1 ]]; then
  echo "== Phase 1.b: operator-gated Postgres-kill (C3) + pool-exhaustion (C4b) =="
  if ! command -v podman >/dev/null 2>&1 && ! command -v docker >/dev/null 2>&1; then
    echo "   SKIP: no podman/docker on PATH. C3/C4b remain OPERATOR_GATED (ran=false). Not faked."
  else
    echo "   NOTE: Phase 1.b real-Postgres chaos driver is owed (build-tag 'stresspg')."
    echo "         The container lifecycle reuses lava-api-go's existing podman compose for PG."
    echo "         Until that driver lands, C3/C4b stay OPERATOR_GATED in the evidence file."
  fi
fi

echo "== latest evidence =="
LATEST="$(ls -1dt "${API_DIR}"/tests/stress/evidence/*/ 2>/dev/null | head -1 || true)"
if [[ -n "${LATEST}" ]]; then
  echo "   ${LATEST}stress-chaos.json"
  echo "   ${LATEST}stress-chaos.md"
fi

exit ${RC}

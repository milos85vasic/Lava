#!/usr/bin/env bash
# scripts/run-vm-distro-matrix.sh — cross-OS distro matrix wrapper.
# Drives cmd/vm-matrix against (alpine, debian, fedora) × x86_64 = 3
# configs. Each VM starts proxy.jar + lava-api-go in background and
# probes 4 endpoints (proxy_health, proxy_search, goapi_health,
# goapi_metrics). Post-processing rejects when any of 4×3=12 booleans
# is false.
#
# Bluff vector this catches: distro-specific runtime behavior — a
# proxy or Go binary that runs cleanly on alpine but fails on debian
# (musl vs glibc, systemd vs OpenRC, packaged JRE differences). The
# Sixth Law clause 4 acceptance gate ("works for a real user end-to-
# end") fails silently if we only test on one distro family.
#
# Pre-requisites:
#   - proxy/build/libs/app.jar exists (./gradlew :proxy:buildFatJar)
#   - lava-api-go/build/lava-api-go binary exists
#   - tools/lava-containers/vm-images.json has REAL hashes (placeholder
#     zeros reject in pkg/cache.Store.Get())
#
# Exit codes:
#   0 — all 4 probes succeeded on all 3 distros (12/12 true)
#   1 — at least one probe failed OR a probe-output.json is missing
#   2 — configuration error (missing inputs)
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

# --tag <T> places the run's evidence directory under
# .lava-ci-evidence/<T>/vm-distro/<UTC>/ so scripts/tag.sh's
# require_matrix_attestation_clause_6_I (which find-walks all
# subdirectories of the per-tag pack-dir) discovers it. Without --tag
# the run lives outside any per-tag pack and is purely diagnostic /
# developer-iteration.
TAG_OVERRIDE=""
EVIDENCE_DIR_OVERRIDE=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag) TAG_OVERRIDE="${2:-}"; shift 2 ;;
    --evidence-dir) EVIDENCE_DIR_OVERRIDE="${2:-}"; shift 2 ;;
    -h|--help)
      cat <<USAGE
Usage: $0 [--tag <tag>] [--evidence-dir <path>]

Drive the VM distro matrix (3 distros × x86_64 = 3 configs) and
post-process per-row probe-output.json (4 booleans × 3 rows = 12).

OPTIONS
  --tag <tag>           Place the run's evidence directory under
                        .lava-ci-evidence/<tag>/vm-distro/<UTC>/ so
                        scripts/tag.sh discovers it (Sixth Law
                        clause 6.I gate).
  --evidence-dir <path> Explicit evidence-dir override; wins over
                        --tag if both are provided.
  -h, --help            Show this help and exit.

Evidence-path resolution priority:
  1. --evidence-dir <path>     (wins)
  2. --tag <tag>               (.lava-ci-evidence/<tag>/vm-distro/<UTC>/)
  3. default                   (.lava-ci-evidence/vm-distro/<UTC>/)
USAGE
      exit 0
      ;;
    *) echo "ERROR: unknown option: $1 (try --help)" >&2; exit 2 ;;
  esac
done

if [[ -n "$EVIDENCE_DIR_OVERRIDE" ]]; then
  EVIDENCE_DIR="$EVIDENCE_DIR_OVERRIDE"
elif [[ -n "$TAG_OVERRIDE" ]]; then
  EVIDENCE_DIR=".lava-ci-evidence/${TAG_OVERRIDE}/vm-distro/$(date -u +%Y-%m-%dT%H-%M-%SZ)"
else
  EVIDENCE_DIR=".lava-ci-evidence/vm-distro/$(date -u +%Y-%m-%dT%H-%M-%SZ)"
fi
mkdir -p "$EVIDENCE_DIR"

BIN_DIR="$PROJECT_DIR/build/vm-matrix"
mkdir -p "$BIN_DIR"
( cd "$PROJECT_DIR/Submodules/Containers" && go build -o "$BIN_DIR/vm-matrix" ./cmd/vm-matrix/ )

PROXY="proxy/build/libs/app.jar"
GOAPI="lava-api-go/build/lava-api-go"
SCRIPT="tests/vm-distro/boot-and-probe.sh"

if [[ ! -f "$PROXY" || ! -f "$GOAPI" ]]; then
  echo "ERROR: distro matrix requires $PROXY and $GOAPI to exist." >&2
  echo "  - Run ./gradlew :proxy:buildFatJar (produces $PROXY)" >&2
  echo "  - Build lava-api-go (e.g. cd lava-api-go && make build)" >&2
  exit 2
fi

"$BIN_DIR/vm-matrix" \
  --image-manifest tools/lava-containers/vm-images.json \
  --targets alpine-3.20-x86_64,debian-12-x86_64,fedora-40-x86_64 \
  --uploads "$PROXY:/tmp/proxy.jar,$GOAPI:/tmp/lava-api-go,$SCRIPT:/tmp/boot-and-probe.sh" \
  --script /tmp/boot-and-probe.sh \
  --captures "/tmp/probe-output.json:probe-output.json" \
  --evidence-dir "$EVIDENCE_DIR" \
  --concurrent 1 --cold-boot

# Post-processing: each row's probe-output.json contains 4 booleans
# (proxy_health, proxy_search, goapi_health, goapi_metrics). Any false
# fails the matrix. Tested by tests/vm-distro/test_distro_matrix_*.sh
# fixtures inlining this same loop.
failures=0
for row_id in alpine-3.20-x86_64 debian-12-x86_64 fedora-40-x86_64; do
  pf="$EVIDENCE_DIR/$row_id/probe-output.json"
  if [[ ! -f "$pf" ]]; then
    echo "MISSING: $pf" >&2
    failures=1
    continue
  fi
  for field in proxy_health proxy_search goapi_health goapi_metrics; do
    val=$(jq -r ".$field" "$pf")
    if [[ "$val" != "true" ]]; then
      echo "$row_id: $field = $val"
      failures=1
    fi
  done
done

if [[ $failures -ne 0 ]]; then
  echo "DISTRO MATRIX FAILED" >&2
  exit 1
fi
echo "DISTRO MATRIX PASSED — all 4 probes succeeded on all 3 distros."
exit 0

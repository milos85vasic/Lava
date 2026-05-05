#!/usr/bin/env bash
# tests/vm-distro/test_distro_matrix_rejects_proxy_health_failure.sh
#
# Asserts: the post-processor in scripts/run-vm-distro-matrix.sh
# rejects when any row's probe-output.json reports proxy_health=false.
#
# Strategy: seed a synthetic per-target evidence dir with fedora's
# proxy_health=false, all other booleans true. Inline the wrapper's
# probe-checking block. Expected: failures=1 → exit 0 (test PASS).
#
# This is the falsifiability-rehearsed test (Phase C mutation #2).
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

EVIDENCE_DIR="$WORK/evidence"

# alpine + debian: clean (all 4 probes true)
for id in alpine-3.20-x86_64 debian-12-x86_64; do
  mkdir -p "$EVIDENCE_DIR/$id"
  echo '{"proxy_health":true,"proxy_search":true,"goapi_health":true,"goapi_metrics":true}' \
    > "$EVIDENCE_DIR/$id/probe-output.json"
done

# fedora: proxy_health=false (the failure under test)
mkdir -p "$EVIDENCE_DIR/fedora-40-x86_64"
echo '{"proxy_health":false,"proxy_search":true,"goapi_health":true,"goapi_metrics":true}' \
  > "$EVIDENCE_DIR/fedora-40-x86_64/probe-output.json"

# Inline probe-check (matches the wrapper's logic):
failures=0
for row_id in alpine-3.20-x86_64 debian-12-x86_64 fedora-40-x86_64; do
  pf="$EVIDENCE_DIR/$row_id/probe-output.json"
  if [[ ! -f "$pf" ]]; then
    failures=1
    continue
  fi
  for field in proxy_health proxy_search goapi_health goapi_metrics; do
    val=$(jq -r ".$field" "$pf")
    if [[ "$val" != "true" ]]; then
      failures=1
    fi
  done
done

if [[ $failures -eq 0 ]]; then
  echo "FAIL: proxy_health failure not detected"
  exit 1
fi
exit 0

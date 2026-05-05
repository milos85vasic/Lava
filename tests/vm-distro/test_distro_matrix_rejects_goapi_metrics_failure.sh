#!/usr/bin/env bash
# tests/vm-distro/test_distro_matrix_rejects_goapi_metrics_failure.sh
#
# Asserts: the post-processor rejects when any row's probe-output.json
# reports goapi_metrics=false. Covers the "Prometheus endpoint not
# served on this distro" failure mode.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

EVIDENCE_DIR="$WORK/evidence"

# alpine + fedora: clean
for id in alpine-3.20-x86_64 fedora-40-x86_64; do
  mkdir -p "$EVIDENCE_DIR/$id"
  echo '{"proxy_health":true,"proxy_search":true,"goapi_health":true,"goapi_metrics":true}' \
    > "$EVIDENCE_DIR/$id/probe-output.json"
done

# debian: goapi_metrics=false
mkdir -p "$EVIDENCE_DIR/debian-12-x86_64"
echo '{"proxy_health":true,"proxy_search":true,"goapi_health":true,"goapi_metrics":false}' \
  > "$EVIDENCE_DIR/debian-12-x86_64/probe-output.json"

# Inline probe-check.
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
  echo "FAIL: goapi_metrics failure not detected"
  exit 1
fi
exit 0

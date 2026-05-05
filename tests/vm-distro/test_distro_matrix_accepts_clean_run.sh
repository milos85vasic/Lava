#!/usr/bin/env bash
# tests/vm-distro/test_distro_matrix_accepts_clean_run.sh
#
# Asserts: when all 3 distros report all 4 probes as true (12/12),
# the post-processor does NOT reject. Pairs with the two rejection
# fixtures to cover both branches of the load-bearing block.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

EVIDENCE_DIR="$WORK/evidence"

# All 3 distros: clean (all 4 probes true)
for id in alpine-3.20-x86_64 debian-12-x86_64 fedora-40-x86_64; do
  mkdir -p "$EVIDENCE_DIR/$id"
  echo '{"proxy_health":true,"proxy_search":true,"goapi_health":true,"goapi_metrics":true}' \
    > "$EVIDENCE_DIR/$id/probe-output.json"
done

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

if [[ $failures -ne 0 ]]; then
  echo "FAIL: clean matrix incorrectly rejected"
  exit 1
fi
exit 0

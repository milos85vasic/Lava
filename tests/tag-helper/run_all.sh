#!/usr/bin/env bash
# tests/tag-helper/run_all.sh — execute every test_tag_*.sh in this
# directory; exit non-zero if any fail.
set -u
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fails=0
total=0
for t in "$SCRIPT_DIR"/test_tag_*.sh; do
  total=$((total + 1))
  if bash "$t"; then
    echo "[tag-helper] PASS: $(basename "$t")"
  else
    echo "[tag-helper] FAIL: $(basename "$t")"
    fails=$((fails + 1))
  fi
done
echo "[tag-helper] $((total - fails))/$total passed"
exit $fails

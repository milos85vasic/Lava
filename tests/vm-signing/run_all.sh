#!/usr/bin/env bash
# tests/vm-signing/run_all.sh — runs every test_*.sh in this directory.
# Used by Lava-side CI as the unit-level acceptance gate for the
# signing-matrix wrapper's post-processing logic.
set -u
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fails=0
total=0
for t in "$SCRIPT_DIR"/test_*.sh; do
  [[ -f "$t" ]] || continue
  total=$((total + 1))
  if bash "$t"; then
    echo "[vm-signing] PASS: $(basename "$t")"
  else
    echo "[vm-signing] FAIL: $(basename "$t")"
    fails=$((fails + 1))
  fi
done
echo "[vm-signing] $((total - fails))/$total passed"
exit $(( fails > 0 ? 1 : 0 ))

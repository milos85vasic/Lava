#!/usr/bin/env bash
set -u
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fails=0; total=0
for t in "$SCRIPT_DIR"/test_*.sh; do
    total=$((total + 1))
    if bash "$t"; then echo "[compose-layout] PASS: $(basename "$t")"; else echo "[compose-layout] FAIL: $(basename "$t")"; fails=$((fails + 1)); fi
done
echo "[compose-layout] $((total - fails))/$total passed"
exit $(( fails > 0 ? 1 : 0 ))

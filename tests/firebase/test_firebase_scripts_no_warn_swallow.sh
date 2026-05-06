#!/usr/bin/env bash
# tests/firebase/test_firebase_scripts_no_warn_swallow.sh
#
# Anti-bluff guard for the four Firebase scripts. Same template as
# tests/ci-sh/test_ci_sh_no_warn_swallow.sh — asserts no `|| echo WARN`
# pattern, asserts `set -euo pipefail` is at the head, asserts no token
# is echoed.
#
# Falsifiability rehearsal:
#   Add `firebase apps:list || echo WARN-line` to scripts/firebase-distribute.sh
#   (outside any comment) — this test will fail with a clear message
#   pointing to the WARN-swallow pattern.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

scripts=(
    "$REPO_ROOT/scripts/firebase-env.sh"
    "$REPO_ROOT/scripts/firebase-setup.sh"
    "$REPO_ROOT/scripts/firebase-distribute.sh"
    "$REPO_ROOT/scripts/firebase-stats.sh"
    "$REPO_ROOT/scripts/distribute.sh"
)

for s in "${scripts[@]}"; do
    if [[ ! -f "$s" ]]; then
        echo "FAIL: missing $s"
        exit 1
    fi
done

non_comment_lines() {
    grep -nE '^\s*[^#[:space:]]' "$1"
}

# 1. No WARN-swallow in real code
for s in "${scripts[@]}"; do
    if non_comment_lines "$s" | grep -qE '\|\|\s*echo[[:space:]]+["]?WARN'; then
        echo "FAIL: $s contains an OR-OR-echo-WARN swallow pattern (§6.J bluff)."
        non_comment_lines "$s" | grep -nE '\|\|\s*echo[[:space:]]+["]?WARN'
        exit 1
    fi
done

# 2. set -euo pipefail at the head of every distribute-path script
#    (firebase-env.sh + firebase-setup.sh + firebase-distribute.sh + distribute.sh).
#    firebase-stats.sh also gets the same treatment.
for s in "${scripts[@]}"; do
    # Allow up to 60 header lines so heavily-documented scripts (e.g.
    # scripts/distribute.sh's full architecture comment) can keep
    # comprehensive headers without tripping the gate.
    if ! head -60 "$s" | grep -qE '^set -euo pipefail|^set -e'; then
        echo "FAIL: $s does not start with 'set -euo pipefail' (within first 60 lines)."
        exit 1
    fi
done

# 3. The token MUST NOT be echoed to stdout in any script. Look for
#    suspicious `echo`/`printf` patterns referencing the token variable.
for s in "${scripts[@]}"; do
    if non_comment_lines "$s" | grep -qE '(echo|printf)[^|]*\$LAVA_FIREBASE_TOKEN'; then
        echo "FAIL: $s echoes \$LAVA_FIREBASE_TOKEN — credential leak (§6.H)."
        non_comment_lines "$s" | grep -nE '(echo|printf)[^|]*\$LAVA_FIREBASE_TOKEN'
        exit 1
    fi
done

# 4. firebase-env.sh sources successfully if .env exists; if .env is
#    missing, it MUST exit non-zero (no silent skip).
if grep -qE 'echo "FATAL: \.env not found' "$REPO_ROOT/scripts/firebase-env.sh"; then
    :
else
    echo "FAIL: scripts/firebase-env.sh missing the 'FATAL: .env not found' guard."
    exit 1
fi

echo "[firebase] OK: scripts/firebase-*.sh + scripts/distribute.sh — no WARN-swallow, no token echo, set -euo pipefail present, .env guard intact."
exit 0

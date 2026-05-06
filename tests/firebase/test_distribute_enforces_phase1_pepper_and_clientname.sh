#!/usr/bin/env bash
# tests/firebase/test_distribute_enforces_phase1_pepper_and_clientname.sh
#
# Anti-bluff guard for Phase-1 Gates 4 + 5 in scripts/firebase-distribute.sh:
#
#   Gate 4: pepper-rotation refusal — the current LAVA_AUTH_OBFUSCATION_PEPPER's
#           SHA-256 must not already appear in
#           .lava-ci-evidence/distribute-changelog/firebase-app-distribution/pepper-history.sha256
#   Gate 5: LAVA_AUTH_CURRENT_CLIENT_NAME must equal android-${APP_VERSION}-${APP_VERSION_CODE}
#           AND the named entry must appear in LAVA_AUTH_ACTIVE_CLIENTS.
#
# Falsifiability rehearsal: deleting either guard block from
# scripts/firebase-distribute.sh causes this test to FAIL with a clear
# message naming the missing gate.
#
# This test is a structural assertion (the same shape as
# test_distribute_enforces_version_monotonicity.sh). The behavioral
# assertion (an actual distribute that's rejected by these gates) is the
# Phase-16 release-tag operator workflow when the operator pushes a new
# build with a stale pepper.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DIST_SH="$REPO_ROOT/scripts/firebase-distribute.sh"

if [[ ! -f "$DIST_SH" ]]; then
    echo "FAIL: scripts/firebase-distribute.sh not found at $DIST_SH"
    exit 1
fi

# Gate 4: pepper-rotation gate — must reference the pepper-history file
# AND must reject when the SHA already exists.
if ! grep -q 'pepper-history.sha256' "$DIST_SH"; then
    echo "FAIL Phase 1 Gate 4: scripts/firebase-distribute.sh missing pepper-history.sha256 reference."
    echo "      Expected a per-distribute file under .lava-ci-evidence/distribute-changelog/firebase-app-distribution/"
    echo "      that records each shipped pepper SHA so the next session refuses to reuse it."
    exit 1
fi

if ! grep -qE 'pepper SHA.*already used|grep -qF "\$PEPPER_SHA"' "$DIST_SH"; then
    echo "FAIL Phase 1 Gate 4: scripts/firebase-distribute.sh missing the pepper-rejection check."
    echo "      Expected a guard like \`if grep -qF \"\$PEPPER_SHA\" \"\$PEPPER_HISTORY\"; then ... exit 1; fi\`."
    exit 1
fi

# Gate 4 anti-bluff cross-check: the script MUST persist the new pepper
# SHA after a successful distribute, otherwise the rejection on the next
# session is a no-op.
if ! grep -qE 'echo.*PEPPER_SHA.*>>.*PEPPER_HISTORY|>> "\$PEPPER_HISTORY"' "$DIST_SH"; then
    echo "FAIL Phase 1 Gate 4: scripts/firebase-distribute.sh does not append the new pepper SHA to history after distribute."
    echo "      Without the persist step, the rotation gate becomes a no-op on the next session."
    exit 1
fi

# Gate 5: LAVA_AUTH_CURRENT_CLIENT_NAME consistency.
if ! grep -q 'LAVA_AUTH_CURRENT_CLIENT_NAME' "$DIST_SH"; then
    echo "FAIL Phase 1 Gate 5: scripts/firebase-distribute.sh missing LAVA_AUTH_CURRENT_CLIENT_NAME read."
    echo "      Expected a guard that compares the .env value against android-\${APP_VERSION}-\${APP_VERSION_CODE}."
    exit 1
fi

if ! grep -qE 'EXPECTED_NAME="android-\$APP_VERSION-\$APP_VERSION_CODE"|"android-\$APP_VERSION-\$APP_VERSION_CODE"' "$DIST_SH"; then
    echo "FAIL Phase 1 Gate 5: scripts/firebase-distribute.sh missing the EXPECTED_NAME=android-\${APP_VERSION}-\${APP_VERSION_CODE} comparison."
    exit 1
fi

if ! grep -q 'LAVA_AUTH_ACTIVE_CLIENTS' "$DIST_SH"; then
    echo "FAIL Phase 1 Gate 5: scripts/firebase-distribute.sh missing LAVA_AUTH_ACTIVE_CLIENTS read."
    echo "      Expected a guard that asserts the current client name appears in the active list."
    exit 1
fi

echo "[firebase] OK: scripts/firebase-distribute.sh enforces Phase-1 Gates 4 + 5 (pepper rotation + client-name consistency)."
exit 0

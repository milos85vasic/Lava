#!/usr/bin/env bash
# tests/firebase/test_distribute_enforces_version_monotonicity.sh
#
# Anti-bluff guard for §6.P (Distribution Versioning + Changelog Mandate).
# Asserts scripts/firebase-distribute.sh contains the three §6.P gates:
#
#   Gate 1: monotonic version code check vs last-version state file
#   Gate 2: CHANGELOG.md entry presence check for current version
#   Gate 3: per-version snapshot file existence check
#
# Falsifiability rehearsal: remove any of the three guard blocks from
# scripts/firebase-distribute.sh — this test fails pointing at the
# missing gate.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DIST_SH="$REPO_ROOT/scripts/firebase-distribute.sh"

if [[ ! -f "$DIST_SH" ]]; then
    echo "FAIL: scripts/firebase-distribute.sh not found at $DIST_SH"
    exit 1
fi

# Gate 1: monotonic versionCode check
if ! grep -qE 'APP_VERSION_CODE.*-le.*LAST_DISTRIBUTED|LAST_DISTRIBUTED.*-ge.*APP_VERSION_CODE' "$DIST_SH"; then
    echo "FAIL §6.P: scripts/firebase-distribute.sh missing the monotonic versionCode gate."
    echo "      Expected a guard like \`if [[ \"\$APP_VERSION_CODE\" -le \"\$LAST_DISTRIBUTED\" ]]; then ... exit 1; fi\`."
    exit 1
fi

# Gate 2: CHANGELOG.md entry check
if ! grep -qE 'grep .*CHANGELOG\.md' "$DIST_SH"; then
    echo "FAIL §6.P: scripts/firebase-distribute.sh missing the CHANGELOG.md entry gate."
    echo "      Expected a check that CHANGELOG.md contains an entry for \$APP_VERSION (\$APP_VERSION_CODE)."
    exit 1
fi

# Gate 3: per-version snapshot file check
if ! grep -qE 'SNAPSHOT_FILE|distribute-changelog/' "$DIST_SH"; then
    echo "FAIL §6.P: scripts/firebase-distribute.sh missing the per-version snapshot gate."
    echo "      Expected a check for .lava-ci-evidence/distribute-changelog/<channel>/<version>-<code>.md."
    exit 1
fi

# Gate 4 (anti-bluff cross-check): the script MUST persist the new
# last-version after a successful distribute, otherwise the next session
# could re-distribute the same code.
if ! grep -qE 'echo.*APP_VERSION_CODE.*>.*LAST_VERSION_FILE|echo.*\$APP_VERSION_CODE.*last-version' "$DIST_SH"; then
    echo "FAIL §6.P: scripts/firebase-distribute.sh does not persist the new last-version after distribute."
    echo "      Without the persist step, the monotonic gate becomes a no-op on the next session."
    exit 1
fi

echo "[firebase] OK: scripts/firebase-distribute.sh enforces all 3 §6.P gates + persists last-version after distribute."
exit 0

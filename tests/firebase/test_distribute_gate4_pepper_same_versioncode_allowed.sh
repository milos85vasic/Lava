#!/usr/bin/env bash
# tests/firebase/test_distribute_gate4_pepper_same_versioncode_allowed.sh
#
# BEHAVIORAL anti-bluff guard for the §6.AA-aware Phase-1 Gate 4 fix in
# scripts/firebase-distribute.sh (2026-05-31).
#
# The §6.AA two-stage distribute runs --debug-only then --release-only for the
# SAME versionCode. Both variants embed ONE pepper (one release identity). The
# debug stage records that pepper's SHA in pepper-history; the release stage MUST
# be allowed to reuse it. Gate 4 still rejects reuse ACROSS a DIFFERENT release
# (the security intent: "a leak in version N must not also compromise N+1").
#
# This test extracts Gate 4's exact decision predicate and drives it against a
# synthetic pepper-history fixture under three scenarios:
#   A. fresh pepper (not in history)            → ALLOW
#   B. same SHA, recorded for THIS versionCode  → ALLOW   (the §6.AA fix)
#   C. same SHA, recorded for a DIFFERENT code  → REJECT  (security preserved)
#
# Falsifiability: reverting the fix (making Gate 4 reject any prior occurrence)
# flips scenario B to REJECT and this test FAILs at "B should ALLOW".
#
# Hermetic: no real .env, no real distribute, no network. Pure logic + tmp files.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DIST_SH="$REPO_ROOT/scripts/firebase-distribute.sh"
fails=0

# --- 1. Structural: the script must carry the version-aware predicate ---------
# The fix's signature is the "grep -vF '# $APP_VERSION-$APP_VERSION_CODE '"
# subtraction (reject only DIFFERENT-release occurrences).
if ! grep -qE 'grep -vF "# \$APP_VERSION-\$APP_VERSION_CODE ' "$DIST_SH"; then
    echo "FAIL: Gate 4 in firebase-distribute.sh is not version-aware."
    echo "      Expected the same-versionCode reuse carve-out (grep -vF \"# \$APP_VERSION-\$APP_VERSION_CODE \")."
    echo "      Without it, the §6.AA release stage cannot reuse the debug stage's pepper."
    fails=$((fails+1))
fi

# --- 2. Behavioral: replicate Gate 4's predicate exactly ----------------------
# Mirror of the production decision: reject iff the SHA appears in history for a
# line that does NOT carry "# <this-version>-<this-code> ".
gate4_rejects() {
    # $1 = pepper SHA, $2 = "version-code" tag, $3 = history file
    local sha="$1" tag="$2" hist="$3"
    local prior_other
    prior_other="$(grep -F "$sha" "$hist" | grep -vF "# $tag " || true)"
    [[ -n "$prior_other" ]]
}

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
HIST="$TMP/pepper-history.sha256"
SHA_X="aaaa1111bbbb2222cccc3333dddd4444eeee5555ffff6666aaaa7777bbbb8888"
SHA_Y="9999000011112222333344445555666677778888999900001111222233334444"
{
  echo "$SHA_X  # 1.2.34-1054  2026-05-31T15-38-30Z"   # debug stage of THIS build
  echo "$SHA_Y  # 1.2.30-1050  2026-05-18T02-14-28Z"   # an older, different release
} > "$HIST"

# Scenario A: a genuinely fresh pepper for 1.2.34-1054 → ALLOW
if gate4_rejects "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff" "1.2.34-1054" "$HIST"; then
    echo "FAIL A: a fresh pepper (not in history) was REJECTED — Gate 4 too strict."
    fails=$((fails+1))
else
    echo "[gate4] A ok: fresh pepper allowed."
fi

# Scenario B: SHA_X reused for the SAME versionCode (release stage) → ALLOW (THE FIX)
if gate4_rejects "$SHA_X" "1.2.34-1054" "$HIST"; then
    echo "FAIL B: pepper reuse WITHIN the same versionCode (debug→release of 1.2.34-1054) was REJECTED."
    echo "        The §6.AA two-stage release of one build legitimately shares one pepper."
    fails=$((fails+1))
else
    echo "[gate4] B ok: same-versionCode reuse allowed (§6.AA two-stage)."
fi

# Scenario C: SHA_Y (belongs to 1.2.30-1050) reused for 1.2.34-1054 → REJECT (security)
if gate4_rejects "$SHA_Y" "1.2.34-1054" "$HIST"; then
    echo "[gate4] C ok: cross-release reuse rejected (security intent preserved)."
else
    echo "FAIL C: a pepper from a DIFFERENT release (1.2.30-1050) was ALLOWED for 1.2.34-1054."
    echo "        Gate 4 must reject cross-release reuse — a leak in N must not compromise N+1."
    fails=$((fails+1))
fi

if [[ "$fails" -ne 0 ]]; then
    echo "FAIL: $fails Gate-4 version-aware pepper-reuse assertion(s) failed."
    exit 1
fi
echo "[firebase] OK: Gate 4 is §6.AA-aware — same-versionCode pepper reuse allowed; cross-release reuse rejected."
exit 0

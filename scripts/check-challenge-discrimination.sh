#!/usr/bin/env bash
# scripts/check-challenge-discrimination.sh — §6.AB mechanical enforcement.
#
# Per §6.AB.3 (Anti-Bluff Test-Suite Reinforcement, 27th §6.L invocation):
# every Challenge Test MUST be auditable for the §6.J spirit-test
# property — would this test fail if the user-visible feature broke in
# a non-crashing way? The author MUST construct a deliberately-broken-
# but-non-crashing version of the production code and confirm the
# Challenge Test fails with a clear assertion message.
#
# This scanner mechanically enforces that every Challenge*Test.kt
# carries a falsifiability rehearsal block in its KDoc.
#
# Acceptable markers (any one is sufficient):
#   1. KDoc contains FALSIFIABILITY REHEARSAL (canonical, optionally
#      prefixed with §6.AB.3)
#   2. KDoc contains §6.AB-discrimination: block
#   3. Companion file .lava-ci-evidence/sp3a-challenges/<TestName>-*.json
#      with falsifiability_rehearsal or discrimination field
#
# Default: STRICT mode (matches §6.AC pattern). Set
# LAVA_CHALLENGE_DISCRIMINATION_STRICT=0 to revert to advisory.
#
# Inheritance: HelixConstitution §11.4 (anti-bluff) + Lava §6.AB.
# Classification: project-specific (the scanner is Lava-side bash; the
# §6.AB mandate it enforces is universal per Anti-Bluff Pact).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

STRICT="${LAVA_CHALLENGE_DISCRIMINATION_STRICT:-1}"

challenge_dir="app/src/androidTest/kotlin/lava/app/challenges"
if [[ ! -d "$challenge_dir" ]]; then
    echo "==> §6.AB scan: no Challenge tests found (looked in $challenge_dir)"
    exit 0
fi

violations=()
total=0
while IFS= read -r f; do
    [[ -z "$f" ]] && continue
    total=$((total + 1))
    bn=$(basename "$f" .kt)
    if grep -qE 'FALSIFIABILITY[ \t]+REHEARSAL|§6\.AB-discrimination:' "$f"; then
        continue
    fi
    if ls .lava-ci-evidence/sp3a-challenges/${bn}-*.json 2>/dev/null | head -1 | grep -q .; then
        if grep -lE 'falsifiability_rehearsal|discrimination|bluff_classification' .lava-ci-evidence/sp3a-challenges/${bn}-*.json 2>/dev/null | head -1 | grep -q .; then
            continue
        fi
    fi
    violations+=("$f")
done < <(find "$challenge_dir" -name 'Challenge*Test.kt')

echo "==> §6.AB Challenge-Test discrimination scan"
echo "    Challenge tests: $total"
echo "    Lacking discrimination marker / companion evidence: ${#violations[@]}"

if [[ ${#violations[@]} -eq 0 ]]; then
    echo "    ✓ all Challenge tests carry §6.AB.3 falsifiability rehearsal documentation"
    exit 0
fi

echo ""
echo "    First 10 violations (file):"
printf '      %s\n' "${violations[@]:0:10}"
if [[ ${#violations[@]} -gt 10 ]]; then
    echo "      ... and $((${#violations[@]} - 10)) more"
fi
echo ""
echo "    Remediation per violation: add a KDoc block of the form"
echo "      §6.AB.3 FALSIFIABILITY REHEARSAL (non-crashing failure mode):"
echo "        1. <deliberate non-crashing mutation in production code>"
echo "        2. Re-run on the gating emulator/device."
echo "        3. Expected failure: <assertion message>"
echo "        4. Restore <production code>; re-run; passes."
echo "    OR ship a companion .lava-ci-evidence/sp3a-challenges/<TestName>-<sha>.json"
echo "    with a 'falsifiability_rehearsal' / 'discrimination' field."

if [[ "$STRICT" == "1" ]]; then
    echo ""
    echo "    LAVA_CHALLENGE_DISCRIMINATION_STRICT=1 — failing on ${#violations[@]} violation(s)."
    exit 1
else
    echo ""
    echo "    Advisory mode. Set LAVA_CHALLENGE_DISCRIMINATION_STRICT=1 to fail."
    exit 0
fi

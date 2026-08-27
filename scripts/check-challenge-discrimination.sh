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

# ---------------------------------------------------------------------------
# §6.J anti-bluff corpus floor (added 2026-08-26, LVA vacuous-pass sweep F7).
#
# Before this floor, BOTH of the empty-corpus routes below reported success:
#
#   challenge_dir absent            -> "no Challenge tests found"          exit 0
#   challenge_dir present, 0 files  -> "Challenge tests: 0"                exit 0
#                                      "✓ all Challenge tests carry ...    "
#                                      "✓ all Challenge test bodies ...    "
#
# The second is the worse of the two: it prints two explicit ✓ claims ABOUT
# ALL CHALLENGE TESTS having examined none of them. "Nothing was learned"
# reported as "nothing failed" is the shape §6.J forbids, and the same shape
# the clause-6.H credential floor (check-constitution.sh:188) and the
# verify-all registry floor (verify-all-constitution-rules.sh:290) already
# guard against elsewhere in this tree.
#
# The expectation is DERIVED from the git index rather than hardcoded, for the
# same reason .gitmodules is the source of truth for the propagation floor: a
# hardcoded number goes stale the moment a Challenge is added or removed, and a
# stale floor is this same defect wearing a different mask. The git index is
# the repository's own declaration of which Challenge files are supposed to
# exist, so it moves in lockstep with the corpus.
#
# awk, not `grep -c`: `grep -c` exits 1 on a zero count, which under `set -e`
# in a pipeline is its own hazard (the very failure mode this sweep records).
# The `|| true` inside the braces is deliberate: `git ls-files` exits 128
# outside a repository, and under `set -euo pipefail` that would abort this
# script with NO message at all — fail-closed, but with a diagnosis so empty it
# sends the reader nowhere. Degrading to a declared count of 0 lets the
# not-a-checkout branch below say what actually happened.
declared_challenges="$(
  { git ls-files -- "$challenge_dir" 2>/dev/null || true; } |
  awk '/\/Challenge[^\/]*Test\.kt$/{n++} END{print n+0}'
)"

if [[ ! -d "$challenge_dir" ]]; then
    echo "§6.AB VIOLATION: the Challenge-discrimination scan corpus directory is ABSENT." >&2
    echo "  → Examined: 0 Challenge*Test.kt files (looked in $challenge_dir)" >&2
    echo "  → Expected: ${declared_challenges} (derived from 'git ls-files -- $challenge_dir')" >&2
    if [[ "$declared_challenges" -gt 0 ]]; then
        echo "  → Cause distinguished: the git index DECLARES ${declared_challenges} Challenge file(s)," >&2
        echo "    but the directory is missing from the working tree. This is working-tree" >&2
        echo "    drift (a deletion, a bad checkout, or a partial clone), not an absent feature." >&2
        echo "  → Do: restore the tree — 'git checkout -- $challenge_dir' — and re-run." >&2
    else
        echo "  → Cause distinguished: the git index declares ZERO Challenge files either, so" >&2
        echo "    this is not a Lava checkout, or the scan is running from the wrong root." >&2
        echo "  → Do: run this script from the Lava repository root and re-run." >&2
    fi
    echo "  → A PASS here would assert §6.AB.3 compliance for a corpus that was never read." >&2
    exit 1
fi

violations=()
body_violations=()
total=0
while IFS= read -r f; do
    [[ -z "$f" ]] && continue
    total=$((total + 1))
    bn=$(basename "$f" .kt)

    # ===== Layer 1: KDoc marker check (existing) =====
    has_marker=false
    if grep -qE 'FALSIFIABILITY[ \t]+REHEARSAL|§6\.AB-discrimination:' "$f"; then
        has_marker=true
    elif ls .lava-ci-evidence/sp3a-challenges/${bn}-*.json 2>/dev/null | head -1 | grep -q .; then
        if grep -lE 'falsifiability_rehearsal|discrimination|bluff_classification' .lava-ci-evidence/sp3a-challenges/${bn}-*.json 2>/dev/null | head -1 | grep -q .; then
            has_marker=true
        fi
    fi
    if [[ "$has_marker" != "true" ]]; then
        violations+=("$f")
        continue
    fi

    # ===== Layer 2: BODY structural check (added 2026-05-15 from
    # bluff-hunt audit at .lava-ci-evidence/bluff-hunt/
    # 2026-05-15-challenge-body-structural-audit.json) =====
    # A test that has the FALSIFIABILITY REHEARSAL marker but NO real
    # assertion in its body is a §6.J spirit bluff: the doc claims
    # discrimination, the body proves nothing.
    #
    # Acceptable real-assertion patterns (any one is sufficient):
    #   - composeRule UI assertions: onNode|onAllNodes|assertIs|assertText|
    #     assertExists|fetchSemanticsNodes|composeRule\.waitUntil
    #   - JUnit assertions with semantic content: assertEquals|assertTrue|
    #     assertFalse|assertNotNull|assertSame|assertContains|assertThat
    #   - `check()` / `require()` with non-trivial condition (more than a
    #     toString().isNotEmpty() check)
    #   - `Class.forName()` / `::class.java` for classpath verification
    #     (the documented minimal-by-design pattern for C30-C35 — these
    #     are not bluffs because Class.forName throws ClassNotFoundException
    #     on missing class)
    # The acceptable-pattern alternation is broad by design — false-negatives
    # (a real bluff slipping through) are worse than false-positives (a real
    # test rejected). The §11.4.6 spirit is "either prove the thing or mark
    # it explicitly". Tests that have NONE of these patterns + still claim
    # discrimination via the KDoc marker ARE bluffs by construction.
    if ! grep -qE 'onNode|onAllNodes|assertIs|assertText|assertExists|fetchSemanticsNodes|composeRule\.waitUntil|assertEquals|assertTrue|assertFalse|assertNotNull|assertSame|assertContains|assertThat|Class\.forName|::class\.java|::[a-zA-Z_][a-zA-Z0-9_]*|\bcheck\(|\brequire\(' "$f"; then
        body_violations+=("$f")
        continue
    fi
done < <(find "$challenge_dir" -name 'Challenge*Test.kt')

echo "==> §6.AB Challenge-Test discrimination scan"
echo "    Challenge tests: $total"
echo "    Lacking discrimination marker / companion evidence: ${#violations[@]}"
echo "    Marker present but body has NO real assertion: ${#body_violations[@]}"

# §6.J anti-bluff corpus floor, part 2 (LVA vacuous-pass sweep F7). Runs BEFORE
# the ✓ claims below, because those claims are universally quantified over the
# corpus and a universally quantified claim over an empty set is vacuously true
# — which is precisely what makes it a bluff.
if [[ "$total" -eq 0 ]]; then
    echo "" >&2
    echo "§6.AB VIOLATION: the Challenge-discrimination scan examined ZERO Challenge tests." >&2
    echo "  → Examined: 0 file(s) under $challenge_dir" >&2
    echo "  → Expected: ${declared_challenges} (derived from 'git ls-files -- $challenge_dir')" >&2
    if [[ "$declared_challenges" -gt 0 ]]; then
        echo "  → Cause distinguished: the git index DECLARES ${declared_challenges} Challenge file(s)" >&2
        echo "    but the directory holds none. This is working-tree drift, not an empty feature set." >&2
        echo "  → Do: 'git checkout -- $challenge_dir' and re-run." >&2
    else
        echo "  → Cause distinguished: the git index declares ZERO Challenge files, so the corpus" >&2
        echo "    is genuinely absent from this checkout rather than merely unpopulated." >&2
        echo "  → Do: verify you are at the Lava repository root with the app/ module present." >&2
    fi
    echo "  → The two ✓ lines this gate prints are claims about ALL Challenge tests; over an" >&2
    echo "    empty corpus they are vacuously true and therefore assert nothing (§6.J)." >&2
    exit 1
fi

# Partial-corpus floor. A floor that only fires at exactly zero is a floor with
# one stair: 73 declared and 2 present passes just as cleanly as 73 and 73.
if [[ "$declared_challenges" -gt 0 && "$total" -lt "$declared_challenges" ]]; then
    echo "" >&2
    echo "§6.AB VIOLATION: the Challenge-discrimination scan examined a PARTIAL corpus." >&2
    echo "  → Examined: ${total} Challenge*Test.kt file(s) under $challenge_dir" >&2
    echo "  → Expected: ${declared_challenges} (derived from 'git ls-files -- $challenge_dir')" >&2
    echo "  → Missing from the working tree:" >&2
    { git ls-files -- "$challenge_dir" 2>/dev/null || true; } |
      awk '/\/Challenge[^\/]*Test\.kt$/{print}' |
      while read -r _decl; do
        [[ -f "$_decl" ]] && continue
        echo "      ${_decl}" >&2
      done
    echo "  → A PASS over ${total} of ${declared_challenges} files asserts nothing about the other" >&2
    echo "    $((declared_challenges - total)); the verdict would be a function of checkout state" >&2
    echo "    rather than of §6.AB.3 compliance." >&2
    echo "  → Do: 'git checkout -- $challenge_dir' and re-run." >&2
    exit 1
fi

if [[ ${#violations[@]} -eq 0 && ${#body_violations[@]} -eq 0 ]]; then
    echo "    ✓ all Challenge tests carry §6.AB.3 falsifiability rehearsal documentation"
    echo "    ✓ all Challenge test bodies contain real assertions (UI / JUnit / classpath)"
    exit 0
fi

echo ""
if [[ ${#violations[@]} -gt 0 ]]; then
    echo "    Layer 1 violations (missing FALSIFIABILITY REHEARSAL marker):"
    printf '      %s\n' "${violations[@]:0:10}"
    if [[ ${#violations[@]} -gt 10 ]]; then
        echo "      ... and $((${#violations[@]} - 10)) more"
    fi
fi
if [[ ${#body_violations[@]} -gt 0 ]]; then
    echo "    Layer 2 violations (marker present but body has no real assertion):"
    printf '      %s\n' "${body_violations[@]:0:10}"
    if [[ ${#body_violations[@]} -gt 10 ]]; then
        echo "      ... and $((${#body_violations[@]} - 10)) more"
    fi
    echo ""
    echo "    Layer 2 remediation: add at least one of the following to the test body:"
    echo "      - Compose UI: composeRule.onAllNodesWithText(\"X\").fetchSemanticsNodes().isNotEmpty()"
    echo "      - JUnit: assertEquals(...) / assertTrue(...) / assertNotNull(...) / etc."
    echo "      - Classpath verification: Class.forName(\"...\") OR ::class.java + property check"
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
    total_violations=$((${#violations[@]} + ${#body_violations[@]}))
    echo ""
    echo "    LAVA_CHALLENGE_DISCRIMINATION_STRICT=1 — failing on $total_violations violation(s) (Layer 1: ${#violations[@]}, Layer 2: ${#body_violations[@]})."
    exit 1
else
    echo ""
    echo "    Advisory mode. Set LAVA_CHALLENGE_DISCRIMINATION_STRICT=1 to fail."
    exit 0
fi

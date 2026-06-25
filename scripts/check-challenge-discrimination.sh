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
# === Strengthening (added 2026-06-25, UI Wave 3 anti-bluff gap closure) ===
# Two real weaknesses were exposed this session and are now closed:
#
#   WEAKNESS #1 — Layer 2 accepted classpath/reflection-ONLY bodies as
#   "real assertions". A Challenge whose ONLY assertion is
#   `Class.forName("…").name == "…"`, `Foo::class.java.name == "…"`,
#   `assertNotNull(someClass)`, or `ref.toString().isNotEmpty()` is a
#   §6.AB.3 bluff by construction: a blank/broken screen passes it (the
#   class is on the classpath regardless of whether its screen renders).
#   Layer 2's acceptable-pattern alternation deliberately listed
#   `Class.forName` / `::class.java` as "acceptable", so it NEVER flagged
#   the C31–C35 classpath-only bluffs found in UI Wave 3. NEW Layer 3
#   (below) flags any Challenge whose assertion-shaped lines are
#   EXCLUSIVELY classpath/reflection — i.e. it carries NO genuine
#   render / interaction / runtime-value assertion. It is tuned to NOT
#   false-positive a legit non-UI integration Challenge that uses
#   `::class.java` incidentally (e.g. `Room…(ctx, AppDatabase::class.java)`,
#   `getFeature(SearchableTracker::class)`) while asserting on real
#   runtime data via `assertEquals(expected, actual)` (C43/C44/C45).
#
#   WEAKNESS #2 — only `:app` challenges were scanned, never the api-app's
#   (`api-app/src/androidTest/.../challenges/`). Those Challenges carry
#   §6.AB markers but were entirely unscanned. ALL three layers now scan
#   both directories.
#
# Layer-3 residual gap (documented honestly per §6.J / §11.4.6): a bluff
# that wraps a class reference in a bare `assertNotNull(classVar)` whose
# variable was assigned from `Class.forName`/`::class.java` on a PRIOR
# line is matched only when the classpath token also appears on the
# assertion line. The dominant real-world signature (the one found this
# session) is `<ref>.name == "…"` / `Class.forName(…).name` /
# `.toString().isNotEmpty()`, which Layer 3 catches exactly.
#
# Inheritance: HelixConstitution §11.4 (anti-bluff) + Lava §6.AB.
# Classification: project-specific (the scanner is Lava-side bash; the
# §6.AB mandate it enforces is universal per Anti-Bluff Pact).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

STRICT="${LAVA_CHALLENGE_DISCRIMINATION_STRICT:-1}"

# Layer 1/2/3 scan BOTH the :app and the api-app androidTest challenge
# trees (WEAKNESS #2 fix). Either may be absent in a given checkout.
# LAVA_CHALLENGE_DIRS (space-separated) overrides the defaults — used by
# the hermetic test under tests/check-constitution/ to run against
# fixtures without touching the real Challenge sources.
if [[ -n "${LAVA_CHALLENGE_DIRS:-}" ]]; then
    # shellcheck disable=SC2206
    challenge_dirs=(${LAVA_CHALLENGE_DIRS})
else
    challenge_dirs=(
        "app/src/androidTest/kotlin/lava/app/challenges"
        "api-app/src/androidTest/kotlin/lava/api/app/challenges"
    )
fi
existing_dirs=()
for d in "${challenge_dirs[@]}"; do
    [[ -d "$d" ]] && existing_dirs+=("$d")
done
if [[ ${#existing_dirs[@]} -eq 0 ]]; then
    echo "==> §6.AB scan: no Challenge tests found (looked in ${challenge_dirs[*]})"
    exit 0
fi

# Layer 3 — classpath-only-bluff detector.
#
# ASSERT_RE matches an assertion-shaped line (JUnit assert*/check/require,
# or a Compose interaction/assertion). COMMENT/import lines are stripped
# before classification so a KDoc that merely MENTIONS onNodeWithText
# cannot disguise a classpath-only body as "real".
#
# REFL_RE matches a reflection/classpath assertion — the bluff subject is
# a class identity (.name/.simpleName/.qualifiedName ==), a classpath
# probe (Class.forName / ::class.java.name), or the degenerate
# .toString().isNotEmpty() existence check.
#
# A Challenge is a classpath-only bluff IFF it has >=1 assertion-shaped
# line AND every such line is reflection-only (0 genuine assertions).
L3_ASSERT_RE='(\bassert[A-Za-z]*[[:space:]]*\(|\bcheck[[:space:]]*\(|\brequire[[:space:]]*\(|onNodeWith|onAllNodesWith|\.onNode|\.onAllNodes|\.performClick|\.performTextInput|\.performScrollTo|\.performKeyInput|fetchSemanticsNodes|\.waitUntil|\.assertIs|\.assertText|\.assertExists|\.assertDoesNotExist)'
L3_REFL_RE='((\.name|\.simpleName|\.qualifiedName)[[:space:]]*==|Class\.forName|\.toString\(\)\.isNotEmpty\(\)|::class\.java\.name)'

violations=()
body_violations=()
classpath_only_violations=()
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

    # ===== Layer 3: classpath-only-bluff structural check (added
    # 2026-06-25 — WEAKNESS #1 fix). A test that passes Layer 2 (it HAS
    # an assertion-shaped line) but whose assertion-shaped lines are ALL
    # reflection/classpath checks is a §6.AB.3 bluff: a blank or broken
    # screen passes `Class.forName("…Foo").name == "…Foo"`.
    #
    # Classification is over non-comment, non-import lines so a KDoc that
    # mentions a render token cannot disguise the body. real_asserts =
    # assertion-shaped lines that are NOT reflection; refl_asserts =
    # assertion-shaped lines that ARE reflection. Flag IFF the body has
    # >=1 reflection assertion and 0 genuine (render/value) assertions.
    body_lines="$(grep -vE '^[[:space:]]*(\*|//|/\*|import )' "$f" || true)"
    real_asserts=$(printf '%s\n' "$body_lines" | grep -E "$L3_ASSERT_RE" | grep -vcE "$L3_REFL_RE" || true)
    refl_asserts=$(printf '%s\n' "$body_lines" | grep -E "$L3_ASSERT_RE" | grep -cE "$L3_REFL_RE" || true)
    if [[ "$real_asserts" -eq 0 && "$refl_asserts" -ge 1 ]]; then
        classpath_only_violations+=("$f")
        continue
    fi
done < <(find "${existing_dirs[@]}" -name 'Challenge*Test.kt')

echo "==> §6.AB Challenge-Test discrimination scan"
echo "    Scanned dirs: ${existing_dirs[*]}"
echo "    Challenge tests: $total"
echo "    Lacking discrimination marker / companion evidence: ${#violations[@]}"
echo "    Marker present but body has NO assertion-shaped line: ${#body_violations[@]}"
echo "    Body assertions are classpath/reflection-ONLY (Layer 3 bluff): ${#classpath_only_violations[@]}"

if [[ ${#violations[@]} -eq 0 && ${#body_violations[@]} -eq 0 && ${#classpath_only_violations[@]} -eq 0 ]]; then
    echo "    ✓ all Challenge tests carry §6.AB.3 falsifiability rehearsal documentation"
    echo "    ✓ all Challenge test bodies contain an assertion-shaped line"
    echo "    ✓ no Challenge relies on a classpath/reflection-ONLY assertion"
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
    echo "    Layer 2 violations (marker present but body has no assertion-shaped line):"
    printf '      %s\n' "${body_violations[@]:0:10}"
    if [[ ${#body_violations[@]} -gt 10 ]]; then
        echo "      ... and $((${#body_violations[@]} - 10)) more"
    fi
    echo ""
    echo "    Layer 2 remediation: add at least one assertion-shaped line to the test body:"
    echo "      - Compose UI: composeRule.onAllNodesWithText(\"X\").fetchSemanticsNodes().isNotEmpty()"
    echo "      - JUnit: assertEquals(...) / assertTrue(...) / assertNotNull(...) / etc."
fi
if [[ ${#classpath_only_violations[@]} -gt 0 ]]; then
    echo "    Layer 3 violations (assertions are classpath/reflection-ONLY — §6.AB.3 bluff:"
    echo "    a blank or broken screen passes \`Class.forName(\"…\").name == \"…\"\`):"
    printf '      %s\n' "${classpath_only_violations[@]:0:20}"
    if [[ ${#classpath_only_violations[@]} -gt 20 ]]; then
        echo "      ... and $((${#classpath_only_violations[@]} - 20)) more"
    fi
    echo ""
    echo "    Layer 3 remediation: replace the classpath probe with a REAL"
    echo "    render / interaction / runtime-value assertion that a blank or"
    echo "    broken screen CANNOT pass:"
    echo "      - Compose UI: composeRule.onNodeWithText(\"<visible text>\").assertIsDisplayed()"
    echo "      - Interaction: composeRule.onNodeWithText(\"<btn>\").performClick() + assert on result"
    echo "      - Runtime value: assertEquals(expected, <real computed/persisted value>)"
    echo "    A bare Class.forName/::class.java classpath check is NOT sufficient."
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
    total_violations=$((${#violations[@]} + ${#body_violations[@]} + ${#classpath_only_violations[@]}))
    echo ""
    echo "    LAVA_CHALLENGE_DISCRIMINATION_STRICT=1 — failing on $total_violations violation(s) (Layer 1: ${#violations[@]}, Layer 2: ${#body_violations[@]}, Layer 3: ${#classpath_only_violations[@]})."
    exit 1
else
    echo ""
    echo "    Advisory mode. Set LAVA_CHALLENGE_DISCRIMINATION_STRICT=1 to fail."
    exit 0
fi

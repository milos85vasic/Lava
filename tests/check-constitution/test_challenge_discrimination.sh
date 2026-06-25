#!/usr/bin/env bash
# Tests for scripts/check-challenge-discrimination.sh (§6.AB.3 enforcement).
#
# Builds synthetic Challenge*Test.kt fixtures in a throwaway dir, points
# the scanner at them via LAVA_CHALLENGE_DIRS, and asserts on the verdict.
# Hermetic: never touches the real Challenge sources (a parallel build may
# be compiling them).
#
# Coverage:
#   - Layer 1: missing FALSIFIABILITY REHEARSAL marker → flagged.
#   - Layer 2: marker present but body has no assertion-shaped line → flagged.
#   - Layer 3 (the 2026-06-25 strengthening): body whose assertions are
#       classpath/reflection-ONLY is the §6.AB.3 bluff → flagged. This is
#       the load-bearing falsifiability test for WEAKNESS #1.
#   - No-false-positive: a real render/interaction/value Challenge that
#       ALSO uses ::class.java incidentally (Room builder, getFeature) → PASS.
#   - Advisory mode: violations do not fail the gate.
set -euo pipefail

_safe_tmpdir() { local d; d=$(command mktemp -d) || { echo "FATAL: mktemp -d failed" >&2; exit 1; }; [[ -n "$d" && -d "$d" && "$d" == /* ]] || { echo "FATAL: invalid tmpdir [$d]" >&2; exit 1; }; printf "%s\n" "$d"; }

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCANNER="$REPO_ROOT/scripts/check-challenge-discrimination.sh"

# Run the scanner against a fixture dir; $2 = STRICT (default 1).
run_scanner() {
    local fixture_dir=$1
    local rc=0
    local out
    out=$(LAVA_CHALLENGE_DIRS="$fixture_dir" \
        LAVA_CHALLENGE_DISCRIMINATION_STRICT="${2:-1}" \
        bash "$SCANNER" 2>&1) || rc=$?
    printf '%s\nexit=%s\n' "$out" "$rc"
}

# Canonical KDoc marker every fixture carries so Layer 1 passes (except the
# Layer-1 test, which omits it on purpose).
KDOC='/**
 * §6.AB.3 FALSIFIABILITY REHEARSAL (non-crashing failure mode):
 *   1. Break the production code path in a non-crashing way.
 *   2. Re-run on the gating emulator.
 *   3. Expected failure: assertion message.
 *   4. Restore; re-run; passes.
 */'

# ---------------------------------------------------------------------------
# Layer 1 — missing marker is flagged.
test_layer1_missing_marker_flagged() {
    local f; f=$(_safe_tmpdir)
    cat > "$f/Challenge01NoMarkerTest.kt" <<KT
package x
class Challenge01NoMarkerTest {
    @Test fun t() { composeRule.onNodeWithText("Hi").assertIsDisplayed() }
}
KT
    local out; out=$(run_scanner "$f" 1)
    if echo "$out" | grep -q "exit=1" && echo "$out" | grep -qi "Lacking discrimination marker.*1"; then
        echo "PASS test_layer1_missing_marker_flagged"
    else
        echo "FAIL test_layer1_missing_marker_flagged: $out"; exit 1
    fi
}

# ---------------------------------------------------------------------------
# Layer 2 — marker present but NO assertion-shaped line at all is flagged.
test_layer2_no_assertion_flagged() {
    local f; f=$(_safe_tmpdir)
    cat > "$f/Challenge02NoAssertTest.kt" <<KT
package x
$KDOC
class Challenge02NoAssertTest {
    @Test fun t() { val vm = SomeViewModel() }
}
KT
    local out; out=$(run_scanner "$f" 1)
    if echo "$out" | grep -q "exit=1" && echo "$out" | grep -qi "NO assertion-shaped line: 1"; then
        echo "PASS test_layer2_no_assertion_flagged"
    else
        echo "FAIL test_layer2_no_assertion_flagged: $out"; exit 1
    fi
}

# ---------------------------------------------------------------------------
# Layer 3 (WEAKNESS #1) — classpath/reflection-ONLY body is the bluff.
# THIS is the load-bearing test: before the strengthening this body passed.
test_layer3_classpath_only_flagged() {
    local f; f=$(_safe_tmpdir)
    cat > "$f/Challenge03ClasspathOnlyTest.kt" <<KT
package x
$KDOC
class Challenge03ClasspathOnlyTest {
    @Test fun reachable() {
        val vmClass = Class.forName("lava.foo.FooViewModel")
        check(vmClass.name == "lava.foo.FooViewModel") { "absent" }
    }
}
KT
    local out; out=$(run_scanner "$f" 1)
    if echo "$out" | grep -q "exit=1" \
        && echo "$out" | grep -qi "classpath/reflection-ONLY (Layer 3 bluff): 1" \
        && echo "$out" | grep -q "Challenge03ClasspathOnlyTest"; then
        echo "PASS test_layer3_classpath_only_flagged"
    else
        echo "FAIL test_layer3_classpath_only_flagged: $out"; exit 1
    fi
}

# Layer 3 — the ::class.java.name and toString().isNotEmpty() variants.
test_layer3_reflection_variants_flagged() {
    local f; f=$(_safe_tmpdir)
    cat > "$f/Challenge04ClassRefTest.kt" <<KT
package x
$KDOC
class Challenge04ClassRefTest {
    @Test fun reachable() { check(FooViewModel::class.java.name == "lava.FooViewModel") { "x" } }
}
KT
    cat > "$f/Challenge05ToStringTest.kt" <<KT
package x
$KDOC
class Challenge05ToStringTest {
    @Test fun reachable() { val ref = ::someComposable; check(ref.toString().isNotEmpty()) { "x" } }
}
KT
    local out; out=$(run_scanner "$f" 1)
    if echo "$out" | grep -q "exit=1" \
        && echo "$out" | grep -qi "classpath/reflection-ONLY (Layer 3 bluff): 2"; then
        echo "PASS test_layer3_reflection_variants_flagged"
    else
        echo "FAIL test_layer3_reflection_variants_flagged: $out"; exit 1
    fi
}

# ---------------------------------------------------------------------------
# No-false-positive — a REAL Challenge that renders + interacts + asserts a
# runtime value, and ALSO uses ::class.java incidentally (Room builder,
# getFeature). Must PASS. This proves Layer 3 is not over-aggressive.
test_real_challenge_with_incidental_classjava_passes() {
    local f; f=$(_safe_tmpdir)
    cat > "$f/Challenge06RealTest.kt" <<KT
package x
$KDOC
class Challenge06RealTest {
    @Test fun renders_and_persists() {
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        val tracker = sdk.getFeature(SearchableTracker::class)!!
        composeRule.onNodeWithText("Favorites").assertIsDisplayed()
        composeRule.onNodeWithText("Add").performClick()
        assertEquals("Prince", db.dao().first().title)
    }
}
KT
    local out; out=$(run_scanner "$f" 1)
    if echo "$out" | grep -q "exit=0" \
        && echo "$out" | grep -qi "classpath/reflection-ONLY (Layer 3 bluff): 0"; then
        echo "PASS test_real_challenge_with_incidental_classjava_passes"
    else
        echo "FAIL test_real_challenge_with_incidental_classjava_passes: $out"; exit 1
    fi
}

# A pure-JUnit non-UI Challenge asserting a real runtime value (no UI, no
# classpath token) must PASS — Layer 3 only fires when reflection is the
# SOLE assertion class.
test_real_value_only_challenge_passes() {
    local f; f=$(_safe_tmpdir)
    cat > "$f/Challenge07ValueTest.kt" <<KT
package x
$KDOC
class Challenge07ValueTest {
    @Test fun computes() { assertEquals(2, addOneToOne()) }
}
KT
    local out; out=$(run_scanner "$f" 1)
    if echo "$out" | grep -q "exit=0"; then
        echo "PASS test_real_value_only_challenge_passes"
    else
        echo "FAIL test_real_value_only_challenge_passes: $out"; exit 1
    fi
}

# ---------------------------------------------------------------------------
# WEAKNESS #2 — both :app and api-app dirs are scanned. A bluff dropped in
# an api-app-style path is still caught.
test_api_app_dir_is_scanned() {
    local f; f=$(_safe_tmpdir)
    mkdir -p "$f/app/src/androidTest/kotlin/lava/app/challenges" \
             "$f/api-app/src/androidTest/kotlin/lava/api/app/challenges"
    cat > "$f/api-app/src/androidTest/kotlin/lava/api/app/challenges/Challenge08ApiBluffTest.kt" <<KT
package x
$KDOC
class Challenge08ApiBluffTest {
    @Test fun reachable() { check(Class.forName("lava.Api").name == "lava.Api") { "x" } }
}
KT
    # Point at the fixture's two dirs explicitly (api-app one has the bluff).
    local out; out=$(run_scanner \
        "$f/app/src/androidTest/kotlin/lava/app/challenges $f/api-app/src/androidTest/kotlin/lava/api/app/challenges" 1)
    if echo "$out" | grep -q "exit=1" && echo "$out" | grep -q "Challenge08ApiBluffTest"; then
        echo "PASS test_api_app_dir_is_scanned"
    else
        echo "FAIL test_api_app_dir_is_scanned: $out"; exit 1
    fi
}

# ---------------------------------------------------------------------------
# Advisory mode — a bluff does not fail the gate (exit 0) but is reported.
test_advisory_mode_does_not_fail() {
    local f; f=$(_safe_tmpdir)
    cat > "$f/Challenge09BluffTest.kt" <<KT
package x
$KDOC
class Challenge09BluffTest {
    @Test fun reachable() { check(Class.forName("lava.Z").name == "lava.Z") { "x" } }
}
KT
    local out; out=$(run_scanner "$f" 0)
    if echo "$out" | grep -q "exit=0" && echo "$out" | grep -qi "Layer 3 bluff): 1"; then
        echo "PASS test_advisory_mode_does_not_fail"
    else
        echo "FAIL test_advisory_mode_does_not_fail: $out"; exit 1
    fi
}

test_layer1_missing_marker_flagged
test_layer2_no_assertion_flagged
test_layer3_classpath_only_flagged
test_layer3_reflection_variants_flagged
test_real_challenge_with_incidental_classjava_passes
test_real_value_only_challenge_passes
test_api_app_dir_is_scanned
test_advisory_mode_does_not_fail
echo "all challenge-discrimination tests passed"

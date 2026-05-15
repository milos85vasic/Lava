#!/usr/bin/env bash
# Tests for scripts/check-challenge-coverage.sh (§6.AE.1 enforcement).
# Builds a synthetic feature/* + Challenge*Test.kt fixture, runs the
# scanner, asserts on the result.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCANNER="$REPO_ROOT/scripts/check-challenge-coverage.sh"

run_scanner() {
    local fixture_dir=$1
    LAVA_REPO_ROOT="$fixture_dir" \
        LAVA_CHALLENGE_COVERAGE_STRICT="${2:-1}" \
        bash "$SCANNER" 2>&1
    echo "exit=$?"
}

# Test 1: feature without any Challenge → strict mode rejects
test_uncovered_feature_rejected() {
    local f
    f=$(mktemp -d)
    cd "$f"
    mkdir -p feature/widgetx app/src/androidTest/kotlin/lava/app/challenges
    touch feature/widgetx/build.gradle.kts
    output=$(run_scanner "$f" 1 || true)
    if echo "$output" | grep -qE "Uncovered.*1|widgetx"; then
        echo "PASS test_uncovered_feature_rejected"
    else
        echo "FAIL test_uncovered_feature_rejected: expected widgetx flagged, got: $output"
        exit 1
    fi
}

# Test 2: feature with a Challenge that imports it → accepted
test_covered_via_import_accepted() {
    local f
    f=$(mktemp -d)
    cd "$f"
    mkdir -p feature/widgety app/src/androidTest/kotlin/lava/app/challenges
    touch feature/widgety/build.gradle.kts
    cat > app/src/androidTest/kotlin/lava/app/challenges/ChallengeXX_WidgetyTest.kt <<'KT'
import lava.widgety.WidgetyScreen
class ChallengeXX_WidgetyTest
KT
    output=$(run_scanner "$f" 1 || true)
    if echo "$output" | grep -qE "Uncovered: 0|all features"; then
        echo "PASS test_covered_via_import_accepted"
    else
        echo "FAIL test_covered_via_import_accepted: expected 0 uncovered, got: $output"
        exit 1
    fi
}

# Test 3: feature with explicit AE-exempt marker in evidence dir → accepted
test_exempted_via_marker_accepted() {
    local f
    f=$(mktemp -d)
    cd "$f"
    mkdir -p feature/widgetz app/src/androidTest/kotlin/lava/app/challenges \
             .lava-ci-evidence
    touch feature/widgetz/build.gradle.kts
    cat > .lava-ci-evidence/challenge-coverage-exemptions.md <<'MD'
// AE-exempt: widgetz
- WHAT: feature/widgetz
- WHY: pre-wired infra not yet user-reachable
MD
    output=$(run_scanner "$f" 1 || true)
    if echo "$output" | grep -qE "Exempted.*1|widgetz"; then
        echo "PASS test_exempted_via_marker_accepted"
    else
        echo "FAIL test_exempted_via_marker_accepted: expected widgetz exempted, got: $output"
        exit 1
    fi
}

# Test 4: advisory mode with uncovered → exit 0 (still warns)
test_advisory_mode_does_not_fail() {
    local f
    f=$(mktemp -d)
    cd "$f"
    mkdir -p feature/widgetw app/src/androidTest/kotlin/lava/app/challenges
    touch feature/widgetw/build.gradle.kts
    output=$(run_scanner "$f" 0 || true)
    if echo "$output" | grep -q "exit=0"; then
        echo "PASS test_advisory_mode_does_not_fail"
    else
        echo "FAIL test_advisory_mode_does_not_fail: expected exit=0 in advisory mode, got: $output"
        exit 1
    fi
}

test_uncovered_feature_rejected
test_covered_via_import_accepted
test_exempted_via_marker_accepted
test_advisory_mode_does_not_fail
echo "all challenge-coverage tests passed"

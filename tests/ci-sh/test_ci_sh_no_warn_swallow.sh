#!/usr/bin/env bash
# tests/ci-sh/test_ci_sh_no_warn_swallow.sh — anti-bluff guard for
# scripts/ci.sh's gate-output-vs-real-failure boundary.
#
# Forensic anchor: 2026-05-05 (TENTH anti-bluff invocation, Phase R4)
# the Compose UI Challenge Tests gate in scripts/ci.sh used the form
#   ./gradlew :app:connectedDebugAndroidTest --tests "lava.app.challenges.*" \
#     "OR-OR" echo WARN-message
# AGP 8.9+ rejects the legacy --tests flag with "Unknown command-line
# option", AND the OR-OR-echo-WARN suffix swallowed the resulting
# BUILD FAILED. The script then unconditionally printed "All gates
# passed" — a textbook section-6.J bluff (gate reports green while
# reality is broken).
#
# This test guards the boundary by asserting scripts/ci.sh contains
# NO patterns that swallow real build failures into log lines while
# letting the script's overall exit code stay zero.
#
# Falsifiability rehearsal: re-introduce the WARN-swallow line into
# scripts/ci.sh (outside any comment); this test fails with a clear
# message naming the swallow pattern.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CI_SH="$REPO_ROOT/scripts/ci.sh"

if [[ ! -f "$CI_SH" ]]; then
    echo "FAIL: scripts/ci.sh not found at $CI_SH"
    exit 1
fi

# Filter scripts/ci.sh to its NON-COMMENT lines so that the test
# doesn't false-positive on comments that REFERENCE the removed
# patterns (e.g., the comment block explaining the 2026-05-05 fix).
non_comment_lines() {
    grep -nE '^\s*[^#[:space:]]' "$CI_SH"
}

# Anti-bluff pattern 1: an OR-OR-echo-WARN swallow in real code (not
# in a comment).
if non_comment_lines | grep -qE '\|\|\s*echo[[:space:]]+["]?WARN'; then
    echo "FAIL: scripts/ci.sh contains an OR-OR-echo-WARN swallow pattern in non-comment code."
    echo "      This is a section-6.J bluff: a failed gate reports as a log line, then the script unconditionally claims 'All gates passed'."
    echo "      Either propagate the failure (rely on set -euo pipefail) OR fail the script explicitly with an exit 1."
    non_comment_lines | grep -nE '\|\|\s*echo[[:space:]]+["]?WARN'
    exit 1
fi

# Anti-bluff pattern 2: a legacy --tests flag invocation against the
# connectedDebugAndroidTest task. AGP 8.9+ rejects --tests; the
# replacement is -Pandroid.testInstrumentationRunnerArguments.<key>=<val>.
if non_comment_lines | grep -qE 'connectedDebugAndroidTest\b.*--tests'; then
    echo "FAIL: scripts/ci.sh uses the legacy 'connectedDebugAndroidTest --tests <pattern>' invocation, which AGP 8.9+ rejects with 'Unknown command-line option'."
    echo "      Replace with -Pandroid.testInstrumentationRunnerArguments.<class|package>=<value>."
    non_comment_lines | grep -nE 'connectedDebugAndroidTest\b.*--tests'
    exit 1
fi

# Anti-bluff pattern 3: a generic 'always echo passed at the end'
# without strict-error-mode. Verified by ensuring the head of the
# script declares 'set -euo pipefail' (or 'set -e') so intermediate
# failures propagate.
if ! head -30 "$CI_SH" | grep -qE '^set -euo pipefail|^set -e'; then
    echo "FAIL: scripts/ci.sh does not start with 'set -euo pipefail' (or equivalent)."
    echo "      Without strict-error mode, intermediate failures don't propagate, and the final 'All gates passed' line becomes a section-6.J bluff."
    exit 1
fi

echo "[ci-sh] OK: scripts/ci.sh has no WARN-swallow, no legacy --tests, set -euo pipefail present"
exit 0

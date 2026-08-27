#!/usr/bin/env bash
# Tests for scripts/check-constitution.sh — §6.N/§6.O/§6.P/§6.Q propagation
# gate: the submodule-contribution FLOOR (finding F3).
#
# THE DEFECT UNDER TEST
# ---------------------
# Block 9 of scripts/check-constitution.sh seeds `propagation_targets` with 5
# root docs and then extends it from a glob:
#
#     for sub in submodules/*/CLAUDE.md; do
#       [[ -f "$sub" ]] && propagation_targets+=("$sub")
#     done
#
# With `nullglob` unset (it is), an unmatched glob yields the literal string
# `submodules/*/CLAUDE.md`; `[[ -f ... ]]` is false and nothing is appended.
# The failing `[[ ... ]]` is the non-final command of an `&&` list, which
# `set -e` explicitly exempts, so the script does NOT abort -- it continues and
# the gate PASSES having examined only the 5 root docs. Every submodule is
# silently unexamined.
#
# The consequence is that the SAME repository state yields OPPOSITE verdicts
# depending only on whether submodules happen to be initialised:
#   * initialised   -> real propagation drift is caught, exit 1
#   * uninitialised -> nothing examined, "PASS", exit 0
# A gate that reports "nothing failed" when it means "nothing was learned" is
# the shape §6.J forbids, and the same file already guards against it at the
# §6.H credential-scan corpus check ("examined ZERO tracked files ... a PASS
# here would assert nothing").
#
# This is not hypothetical. The block's own comment records the identical
# no-op having happened before: after the §11.4.29 snake_case rename the
# hard-coded CamelCase paths stopped resolving and "silently skipped EVERY
# submodule ... hiding real propagation drift" (fixed 2026-07-02 by switching
# to a glob). The glob fixed the rename; the FLOOR was never added, so the
# uninitialised-submodule route into the same no-op stayed open.
#
# TEST STRATEGY (anti-bluff)
# --------------------------
# The block under test is EXTRACTED VERBATIM from the live
# scripts/check-constitution.sh rather than re-implemented, so these tests
# track the real code and cannot drift from it. Two marker-anchored ranges are
# taken (anchored on text, not line numbers, so they survive edits above):
#   1. the `doc_inherits_clause() { ... }` helper
#   2. `declare -a propagation_targets=(` through the SECOND `done`
#      (the array seed, the submodule glob loop, and the §6.N verification loop)
#
# Tests 1 and 2 are the REGRESSION cases and FAIL against current code.
# Tests 3 and 4 are CONTROLS that pass both before and after the fix; they are
# what proves a floor does not break the legitimate initialised-submodule
# workflow (3) and does not mask real drift detection (4).
#
# EXPECTED RESULT AGAINST CURRENT (UNFIXED) CODE: tests 1 and 2 FAIL.
# EXPECTED RESULT AGAINST A CORRECT FIX:          all four tests PASS.
#
# Classification: project-specific.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GATE="$REPO_ROOT/scripts/check-constitution.sh"

if [[ ! -f "$GATE" ]]; then
  echo "FAIL: gate not found at $GATE"
  exit 1
fi

# Build a runnable harness containing the VERBATIM block under test.
build_harness() {
  local out=$1
  {
    echo '#!/usr/bin/env bash'
    echo 'set -euo pipefail'
    sed -n '/^doc_inherits_clause() {/,/^}$/p' "$GATE"
    awk '/^declare -a propagation_targets=\(/{f=1}
         f{print; if(/^done$/){c++; if(c==2) exit}}' "$GATE"
    # Reached only if the block above did not exit: report what was examined.
    echo 'echo "EXAMINED=${#propagation_targets[@]}"'
    echo 'echo "GATE RESULT: PASS"'
  } > "$out"
  chmod +x "$out"
}

# Sanity: the extraction must actually contain the block, else every
# assertion below would be vacuous (a bluff by construction).
verify_extraction() {
  local h=$1
  if ! grep -q 'declare -a propagation_targets=(' "$h" \
     || ! grep -q 'for sub in submodules/\*/CLAUDE.md' "$h" \
     || ! grep -q 'doc_inherits_clause' "$h"; then
    echo "FAIL verify_extraction: harness did not capture the block under test."
    echo "  → anchors in $GATE changed; update the sed/awk ranges in this test."
    exit 1
  fi
}

# Fixture: 5 compliant root docs; submodules present or absent per $2.
# $3, when set, is a submodule whose CLAUDE.md is deliberately drifted.
mkfixture() {
  local d=$1 with_subs=$2 drifted=${3:-}
  rm -rf "$d"
  mkdir -p "$d/lava-api-go" "$d/submodules"
  local f
  for f in CLAUDE.md AGENTS.md \
           lava-api-go/CLAUDE.md lava-api-go/AGENTS.md lava-api-go/CONSTITUTION.md; do
    echo '## INHERITED FROM constitution/CLAUDE.md' > "$d/$f"
  done
  if [[ "$with_subs" == "yes" ]]; then
    local s
    for s in auth cache security; do
      mkdir -p "$d/submodules/$s"
      echo '## INHERITED FROM constitution/CLAUDE.md' > "$d/submodules/$s/CLAUDE.md"
    done
    if [[ -n "$drifted" ]]; then
      echo 'this doc has neither the clause nor the inheritance pointer-block' \
        > "$d/submodules/$drifted/CLAUDE.md"
    fi
  fi
}

# Declare $2 submodules under submodules/ in $1's .gitmodules, plus the three
# root-level ones the real repo carries. The floor derives its expectation from
# this file, so a fixture without it leaves the partial-corpus check inert --
# which is exactly why Tests 1-4 above are unaffected by it.
mkgitmodules() {
  local d=$1 n=$2 i
  {
    for i in $(seq 1 "$n"); do
      printf '[submodule "submodules/s%s"]\n\tpath = submodules/s%s\n' "$i" "$i"
    done
    printf '[submodule "constitution"]\n\tpath = constitution\n'
  } > "$d/.gitmodules"
}

run_in() { ( cd "$1" && bash "$2" 2>&1 ); }

# ---------------------------------------------------------------------------
# Test 1 [REGRESSION]: submodules uninitialised → the gate must NOT pass.
# ---------------------------------------------------------------------------
test_uninitialised_submodules_does_not_pass() {
  local d h out rc
  d=$(mktemp -d); h="$d.harness.sh"
  build_harness "$h"; verify_extraction "$h"
  mkfixture "$d" no
  out=$(run_in "$d" "$h"); rc=$?
  if [[ "$rc" -ne 0 ]]; then
    echo "PASS test_uninitialised_submodules_does_not_pass (rc=$rc)"
  else
    echo "FAIL test_uninitialised_submodules_does_not_pass:"
    echo "     the gate PASSED (rc=0) with zero submodules examined."
    echo "     output: $out"
    echo "     → A PASS here asserts nothing about submodule propagation:"
    echo "       identical drift is caught when submodules are initialised and"
    echo "       silently ignored when they are not."
    rm -rf "$d" "$h"; exit 1
  fi
  rm -rf "$d" "$h"
}

# ---------------------------------------------------------------------------
# Test 2 [REGRESSION]: the uninitialised-clone failure must be ACTIONABLE.
# A fresh clone is the common way to hit this; the operator must be told to
# initialise submodules, not left with an opaque failure.
# ---------------------------------------------------------------------------
test_uninitialised_failure_is_actionable() {
  local d h out rc
  d=$(mktemp -d); h="$d.harness.sh"
  build_harness "$h"; verify_extraction "$h"
  mkfixture "$d" no
  out=$(run_in "$d" "$h"); rc=$?
  if [[ "$rc" -ne 0 ]] && grep -q 'git submodule update --init' <<<"$out"; then
    echo "PASS test_uninitialised_failure_is_actionable"
  else
    echo "FAIL test_uninitialised_failure_is_actionable: rc=$rc"
    echo "     expected a non-zero exit whose message names"
    echo "     'git submodule update --init' as the remedy."
    echo "     output: $out"
    rm -rf "$d" "$h"; exit 1
  fi
  rm -rf "$d" "$h"
}

# ---------------------------------------------------------------------------
# Test 3 [CONTROL]: submodules initialised + compliant → gate passes.
# Proves the floor does not break the legitimate workflow.
# ---------------------------------------------------------------------------
test_initialised_compliant_submodules_passes() {
  local d h out rc
  d=$(mktemp -d); h="$d.harness.sh"
  build_harness "$h"; verify_extraction "$h"
  mkfixture "$d" yes
  out=$(run_in "$d" "$h"); rc=$?
  if [[ "$rc" -eq 0 ]] && grep -q 'GATE RESULT: PASS' <<<"$out"; then
    echo "PASS test_initialised_compliant_submodules_passes ($(grep -o 'EXAMINED=[0-9]*' <<<"$out"))"
  else
    echo "FAIL test_initialised_compliant_submodules_passes: rc=$rc out=$out"
    echo "     → the floor must not fire on a correctly initialised checkout."
    rm -rf "$d" "$h"; exit 1
  fi
  rm -rf "$d" "$h"
}

# ---------------------------------------------------------------------------
# Test 4 [CONTROL]: submodules initialised, one drifted → real drift detected.
# Proves the floor did not replace or mask the actual propagation check.
# ---------------------------------------------------------------------------
test_initialised_drifted_submodule_detected() {
  local d h out rc
  d=$(mktemp -d); h="$d.harness.sh"
  build_harness "$h"; verify_extraction "$h"
  mkfixture "$d" yes cache
  out=$(run_in "$d" "$h"); rc=$?
  if [[ "$rc" -ne 0 ]] && grep -q 'propagation REGRESSED: submodules/cache/CLAUDE.md' <<<"$out"; then
    echo "PASS test_initialised_drifted_submodule_detected"
  else
    echo "FAIL test_initialised_drifted_submodule_detected: rc=$rc out=$out"
    rm -rf "$d" "$h"; exit 1
  fi
  rm -rf "$d" "$h"
}

# ---------------------------------------------------------------------------
# Test 5 [REGRESSION, LVA-136 choice B]: PARTIAL initialisation must not pass.
#
# The zero-floor above catches a corpus of 0. It does not catch a corpus of 2
# when 3 are declared: the propagation blocks then examine 7 targets instead of
# 8 and still PASS, so the gate's verdict remains a function of checkout state
# rather than of the tree's compliance -- the same defect LVA-136 records,
# merely at a smaller scale. A floor that fires only at exactly zero is a floor
# with one stair.
# ---------------------------------------------------------------------------
test_partial_initialisation_does_not_pass() {
  local d h out rc
  d=$(mktemp -d); h="$d.harness.sh"
  build_harness "$h"; verify_extraction "$h"
  mkfixture "$d" no
  mkgitmodules "$d" 3
  # two of the three declared submodules are initialised and compliant
  local s
  for s in s1 s2; do
    mkdir -p "$d/submodules/$s"
    echo '## INHERITED FROM constitution/CLAUDE.md' > "$d/submodules/$s/CLAUDE.md"
  done
  mkdir -p "$d/submodules/s3"   # declared, present, but EMPTY -> uninitialised
  out=$(run_in "$d" "$h"); rc=$?
  if [[ "$rc" -ne 0 ]] && grep -q 'NOT INITIALISED' <<<"$out"; then
    echo "PASS test_partial_initialisation_does_not_pass (rc=$rc, 2 of 3 declared)"
  else
    echo "FAIL test_partial_initialisation_does_not_pass:"
    echo "     the gate returned rc=$rc on a corpus of 2 against 3 declared."
    echo "     A PASS here asserts nothing about the uninitialised submodule,"
    echo "     and identical drift inside it would be silently ignored."
    echo "     output: $out"
    rm -rf "$d" "$h"; exit 1
  fi
  rm -rf "$d" "$h"
}

# ---------------------------------------------------------------------------
# Test 6 [LVA-136 choice B]: the refusal must distinguish an UNINITIALISED
# submodule from REAL propagation drift.
#
# Both produce a short corpus, but they call for opposite responses: one is a
# checkout artifact fixed by `git submodule update --init`, the other is a
# genuine missing CLAUDE.md that no amount of initialising will produce. A
# refusal that reports the wrong one sends the reader to the wrong remedy, and
# a diagnosis that misstates its own cause is a small bluff.
# ---------------------------------------------------------------------------
test_partial_failure_distinguishes_drift_from_uninitialised() {
  local d h out rc
  d=$(mktemp -d); h="$d.harness.sh"
  build_harness "$h"; verify_extraction "$h"
  mkfixture "$d" no
  mkgitmodules "$d" 3
  local s
  for s in s1 s2; do
    mkdir -p "$d/submodules/$s"
    echo '## INHERITED FROM constitution/CLAUDE.md' > "$d/submodules/$s/CLAUDE.md"
  done
  # declared, present, POPULATED -- but carries no CLAUDE.md at all
  mkdir -p "$d/submodules/s3"
  echo 'package main' > "$d/submodules/s3/main.go"
  out=$(run_in "$d" "$h"); rc=$?
  if [[ "$rc" -ne 0 ]] \
     && grep -q 'real propagation drift' <<<"$out" \
     && ! grep -q 'NOT INITIALISED' <<<"$out"; then
    echo "PASS test_partial_failure_distinguishes_drift_from_uninitialised"
  else
    echo "FAIL test_partial_failure_distinguishes_drift_from_uninitialised:"
    echo "     a populated submodule missing its CLAUDE.md must be reported as"
    echo "     real drift, NOT as an uninitialised checkout (rc=$rc)."
    echo "     output: $out"
    rm -rf "$d" "$h"; exit 1
  fi
  rm -rf "$d" "$h"
}

test_uninitialised_submodules_does_not_pass
test_uninitialised_failure_is_actionable
test_initialised_compliant_submodules_passes
test_initialised_drifted_submodule_detected
test_partial_initialisation_does_not_pass
test_partial_failure_distinguishes_drift_from_uninitialised
echo "all propagation-target floor tests passed"

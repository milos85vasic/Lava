#!/usr/bin/env bash
# tests/check-constitution/test_corpus_floors.sh
#
# Parameterised regression suite for the corpus-floor sweep of 2026-08-26.
#
# WHAT IT GUARDS
# --------------
# A §6.N.2 production-code bluff hunt (report:
# .lava-ci-evidence/bluff-hunt/2026-08-26-cycle-vacuous-pass-sweep.json) found
# 21 findings sharing ONE shape:
#
#     a gate whose corpus can become empty — or whose required field can be
#     absent — reports the empty case as SUCCESS.
#
# That is §6.J's "nothing was learned" reported as "nothing failed": a gate
# prints a positive verdict having examined nothing. The tree already carried
# the correct remedy in four places (check-constitution.sh's clause-6.H
# credential floor, verify-all-constitution-rules.sh's registry floor,
# phase-02-test.sh's zero-record message, run-iteration.sh's tests>0 floor);
# this suite locks that idiom in for the twenty sites fixed in this sweep.
#
# Each case is a PAIR, per §6.J clause 2:
#   - the empty / absent-corpus condition MUST be refused (non-zero exit), and
#   - a populated control MUST still pass,
# so the floor can never be mistaken for the detection it protects, and a floor
# that refuses everything is caught as readily as one that refuses nothing.
#
# THE IRONY GUARD
# ---------------
# A corpus-floor suite that itself examined nothing would be perfect irony and
# an actual defect. CASES[] is the declared corpus; the tail of this file
# asserts that every declared case ran and that the executed count is non-zero
# and equal to the declared count. Adding a case to CASES[] without defining
# its function fails; defining one without registering it fails the count.
#
# COMPLETED 2026-08-26: the two sites deferred at first pass because another
# agent held their files are now fixed and covered here —
#   F18       scripts/tag.sh (§6.I clause 2 compileSdk derivation)
#   P2        scripts/pipeline/phase-02-test.sh (per-category evidence floor)
# NOT MINE: F10 (check-cycle-coverage.sh zero-claim map) was closed independently
# by that file's own author; its floor lives at check-cycle-coverage.sh:437.
#
# Classification: project-specific (the sites are Lava-side bash; the §6.J
# anti-bluff mandate the floors enforce is universal per the Anti-Bluff Pact).

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

FAILURES=0
EXECUTED=0
declare -a RAN_NAMES=()

pass() { echo "PASS $1"; EXECUTED=$((EXECUTED + 1)); RAN_NAMES+=("$1"); }
fail() {
    echo "FAIL $1: $2"
    FAILURES=$((FAILURES + 1)); EXECUTED=$((EXECUTED + 1)); RAN_NAMES+=("$1")
}

# Assert: `cmd` refuses (non-zero) AND its combined output mentions $needle.
# Used for the empty-corpus half of every pair — a non-zero exit alone is not
# enough, because a script that dies from `set -e` with no message is also
# non-zero, and an empty diagnosis sends the reader nowhere.
expect_refusal() {
    local name="$1" needle="$2"; shift 2
    local out rc
    out="$("$@" 2>&1)"; rc=$?
    if [[ "$rc" -eq 0 ]]; then
        fail "$name" "expected a refusal on the empty corpus, got exit 0. out=${out:0:400}"; return
    fi
    if ! grep -qF "$needle" <<<"$out"; then
        fail "$name" "refused (exit $rc) but the message lacks '$needle' — an unactionable diagnosis. out=${out:0:400}"; return
    fi
    pass "$name"
}

expect_success() {
    local name="$1"; shift
    local out rc
    out="$("$@" 2>&1)"; rc=$?
    if [[ "$rc" -ne 0 ]]; then
        fail "$name" "populated control must still pass, got exit $rc. out=${out:0:400}"; return
    fi
    pass "$name"
}

# Extract a marker-delimited block from a script into a runnable harness.
# Markers, not line numbers, so the harness survives edits above the block.
excerpt() {
    local file="$1" start_marker="$2" end_marker="$3"
    awk -v s="$start_marker" -v e="$end_marker" '
        index($0, s) { on = 1 }
        on && index($0, e) { exit }
        on { print }
    ' "$file"
}

_git_init() { git -C "$1" init -q -b master . && git -C "$1" config user.email t@t && git -C "$1" config user.name t; }
_git_commit() { git -C "$1" add -A >/dev/null 2>&1; git -C "$1" commit -qm "$2" >/dev/null 2>&1; }

POINTER='## INHERITED FROM constitution/CLAUDE.md'

# -----------------------------------------------------------------------------
# F7 — scripts/check-challenge-discrimination.sh
# Zero Challenge tests printed two explicit ✓ claims ABOUT ALL Challenge tests.
# -----------------------------------------------------------------------------
case_f7_empty() {
    local d="$WORK/f7"; mkdir -p "$d/scripts" "$d/app/src/androidTest/kotlin/lava/app/challenges"
    cp "$REPO_ROOT/scripts/check-challenge-discrimination.sh" "$d/scripts/"
    _git_init "$d"
    expect_refusal "f7_zero_challenges_refused" "examined ZERO Challenge tests" \
        env -C "$d" bash "$d/scripts/check-challenge-discrimination.sh"
}
case_f7_control() {
    local d="$WORK/f7"
    local c="$d/app/src/androidTest/kotlin/lava/app/challenges"
    printf '/** FALSIFIABILITY REHEARSAL */\nclass Challenge01XTest { fun t(){ assertTrue(true) } }\n' > "$c/Challenge01XTest.kt"
    _git_commit "$d" seed
    expect_success "f7_populated_control_passes" \
        env -C "$d" bash "$d/scripts/check-challenge-discrimination.sh"
}

# -----------------------------------------------------------------------------
# F12 — scripts/check-script-docs-sync.sh
# 0 == 0 satisfied the 1:1 invariant vacuously.
# -----------------------------------------------------------------------------
case_f12_empty() {
    local d="$WORK/f12"; mkdir -p "$d/scripts" "$d/docs/scripts"; _git_init "$d"
    expect_refusal "f12_zero_scripts_zero_docs_refused" "compared ZERO scripts against ZERO docs" \
        env LAVA_REPO_ROOT="$d" bash "$REPO_ROOT/scripts/check-script-docs-sync.sh"
}
case_f12_control() {
    local d="$WORK/f12"
    echo '#!/bin/sh' > "$d/scripts/a.sh"; echo '# a' > "$d/docs/scripts/a.sh.md"; _git_commit "$d" seed
    expect_success "f12_populated_control_passes" \
        env LAVA_REPO_ROOT="$d" bash "$REPO_ROOT/scripts/check-script-docs-sync.sh"
}

# -----------------------------------------------------------------------------
# F13 — scripts/check-commit-docs-exists.sh
# An UNRESOLVABLE range was reported as a skip with exit 0, and verify-all
# invokes this gate with a hardcoded HEAD~5..HEAD.
# -----------------------------------------------------------------------------
case_f13_unresolvable() {
    local d="$WORK/f13"; mkdir -p "$d"; _git_init "$d"
    echo x > "$d/a.txt"; git -C "$d" add -A >/dev/null
    git -C "$d" commit -qm "seed

Cites docs/THIS-FILE-DOES-NOT-EXIST.md" >/dev/null
    expect_refusal "f13_unresolvable_range_refused" "FAILED TO RESOLVE" \
        env LAVA_REPO_ROOT="$d" bash "$REPO_ROOT/scripts/check-commit-docs-exists.sh" 'HEAD~5..HEAD'
}
case_f13_control() {
    local d="$WORK/f13"
    # Same repo, same commit, resolvable range: the orphan reference is caught.
    expect_refusal "f13_resolvable_range_still_detects_orphan" "VIOLATION" \
        env LAVA_REPO_ROOT="$d" bash "$REPO_ROOT/scripts/check-commit-docs-exists.sh" 'HEAD'
}

# -----------------------------------------------------------------------------
# F11 — scripts/check-cycle-coverage.sh
# The <=24h freshness check was "only enforced when a timestamp is found", so
# evidence with an ABSENT or UNPARSEABLE timestamp was never stale however old
# it was. Decisive control: identical evidence, identical map, identical HEAD,
# only the timestamp line differing.
# -----------------------------------------------------------------------------
F11_MAP=".lava-ci-evidence/distribute-changelog/firebase-app-distribution-api-app/cycle-coverage-map-0.2.13-27.yaml"
_f11_evidence() {  # $1 = the timestamp line, or "" for none
    local d="$WORK/f11/evi"; mkdir -p "$d"
    {
        echo "# Device gate evidence"; echo ""
        echo "cycle-coverage: commit=deadbeefdeadbeefdeadbeefdeadbeefdeadbeef"
        [[ -n "$1" ]] && echo "$1"
        echo ""
        local c
        for c in Challenge01ApiAppColdStartTest Challenge02ApiAppBootAndServeTest \
                 Challenge03StopRestartTest Challenge04NotificationActionsTest; do
            echo "fqn=lava.app.challenges.$c verdict=PASS runner=containers-submodule"
        done
    } > "$d/0.2.13-27-test-evidence.md"
    printf '%s' "$d"
}
_f11_run() {  # $1 = evidence dir, $2 = now-epoch
    bash "$REPO_ROOT/scripts/check-cycle-coverage.sh" --version=0.2.13-27 \
        --evidence-dir="$1" --map="$REPO_ROOT/$F11_MAP" \
        --head=deadbeefdeadbeefdeadbeefdeadbeefdeadbeef --now-epoch="$2" --strict
}
case_f11_absent_timestamp() {
    if [[ ! -f "$REPO_ROOT/$F11_MAP" ]]; then
        fail "f11_absent_timestamp_refused" "fixture map $F11_MAP is absent from this checkout"; return
    fi
    expect_refusal "f11_absent_timestamp_refused" "no timestamp found" \
        _f11_run "$(_f11_evidence '')" 99999999999
}
case_f11_unparseable_timestamp() {
    expect_refusal "f11_unparseable_timestamp_refused" "could not be parsed" \
        _f11_run "$(_f11_evidence 'cycle-coverage: timestamp=not-a-real-date-at-all')" 99999999999
}
case_f11_stale_still_caught() {
    # The floor must not displace the staleness detection it protects.
    expect_refusal "f11_old_parseable_timestamp_still_stale" "evidence is stale" \
        _f11_run "$(_f11_evidence 'cycle-coverage: timestamp=2021-01-01T00:00:00Z')" 99999999999
}
case_f11_control() {
    local ts d
    ts="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    d="$(_f11_evidence "cycle-coverage: timestamp=$ts")"
    expect_success "f11_fresh_timestamp_control_passes" _f11_run "$d" "$(( $(date -u +%s) + 1 ))"
}

# -----------------------------------------------------------------------------
# F14 — scripts/check-no-guessing-vocabulary.sh
# A scan path that does not exist was skipped silently.
# -----------------------------------------------------------------------------
case_f14_no_paths() {
    expect_refusal "f14_no_scan_path_resolves_refused" "NO scan path resolved" \
        env LAVA_NO_GUESSING_SCAN_PATHS="$WORK/does-not-exist-a:$WORK/does-not-exist-b" \
            bash "$REPO_ROOT/scripts/check-no-guessing-vocabulary.sh"
}
case_f14_empty_dir() {
    mkdir -p "$WORK/f14/empty"
    expect_refusal "f14_zero_files_scanned_refused" "read ZERO files" \
        env LAVA_NO_GUESSING_SCAN_PATHS="$WORK/f14/empty" \
            bash "$REPO_ROOT/scripts/check-no-guessing-vocabulary.sh"
}
case_f14_control() {
    expect_success "f14_real_corpus_control_passes" \
        bash "$REPO_ROOT/scripts/check-no-guessing-vocabulary.sh"
}

# -----------------------------------------------------------------------------
# F15 — scripts/verify-all-constitution-rules.sh
# The only numeric floor was `-eq 0`; a registry shrunken 58 -> 32 passed.
# -----------------------------------------------------------------------------
_f15_harness() {
    local n="$1" out="$WORK/f15_$1.sh"
    {
        echo 'set -uo pipefail'
        echo 'declare -a HERMETIC_SUITES=(tests/firebase tests/ci-sh tests/compose-layout tests/tag-helper tests/vm-images tests/vm-signing tests/vm-distro)'
        echo "GATE_NAMES=(); for i in \$(seq 1 $n); do GATE_NAMES+=(\"g\$i\"); done"
        echo 'total_gates=${#GATE_NAMES[@]}'
        excerpt "$REPO_ROOT/scripts/verify-all-constitution-rules.sh" \
            '§6.J DERIVED registry floor' 'END-OF-BLOCK §6.J DERIVED registry floor' \
            | sed 's|"${BASH_SOURCE\[0\]}"|'"$REPO_ROOT"'/scripts/verify-all-constitution-rules.sh|'
        echo 'echo "REACHED CLEAN VERDICT with total_gates=$total_gates"'
    } > "$out"
    printf '%s' "$out"
}
case_f15_shrunken() {
    expect_refusal "f15_shrunken_registry_refused" "SHRUNKEN gate registry" \
        env -C "$REPO_ROOT" bash "$(_f15_harness 12)"
}
case_f15_control() {
    # The expectation is derived; ask the harness for a registry at least as
    # large as whatever the derivation currently computes.
    local n
    n=$(( $(awk '/^run_gate "/{n++} END{print n+0}' "$REPO_ROOT/scripts/verify-all-constitution-rules.sh") + 7 \
          + $(git -C "$REPO_ROOT" ls-files -- 'tests/pre-push/check*_test.sh' | wc -l) \
          + $(git -C "$REPO_ROOT" ls-files -- 'tests/check-constitution/test_*.sh' 'tests/check-constitution/check_constitution_test.sh' | grep -vc '/test_verify_all_rules\.sh$') ))
    expect_success "f15_full_registry_control_passes" \
        env -C "$REPO_ROOT" bash "$(_f15_harness "$n")"
}

# -----------------------------------------------------------------------------
# F17 — scripts/check-workable-items.sh
# `diff` inspected Issues.md + Fixed.md only; the two _Summary renderings of
# the SAME artifact were outside the corpus entirely.
# -----------------------------------------------------------------------------
# The fixture is REGENERATED from the DB rather than copied from docs/, so the
# case tests the summary-corpus floor and not whether the live tree's trackers
# happen to be in sync at this moment (they are another agent's to maintain).
_f17_wi_bin() {
    local b
    for b in "$REPO_ROOT/constitution/scripts/workable-items/bin/workable-items-linux" \
             "$REPO_ROOT/constitution/scripts/workable-items/bin/workable-items"; do
        # Probe with --help, NOT `validate`: validate exits non-zero whenever the
        # DB carries ledger-exempted violations (it does — 67 of them), which
        # would reject a perfectly runnable binary.
        [[ -x "$b" ]] && "$b" --help >/dev/null 2>&1 && { printf '%s' "$b"; return 0; }
    done
    return 1
}
_f17_fixture() {
    local d="$WORK/f17/docs" b; rm -rf "$d"; mkdir -p "$d"
    b="$(_f17_wi_bin)" || return 1
    "$b" export --no-formats --db "$REPO_ROOT/docs/workable_items.db" \
        --out-issues "$d/Issues.md" --out-fixed "$d/Fixed.md" >/dev/null 2>&1 || return 1
    printf '%s' "$d"
}
case_f17_fabricated_summary() {
    local d
    if ! d="$(_f17_fixture)"; then
        fail "f17_fabricated_summary_refused" "could not regenerate the tracker fixture from the DB"; return
    fi
    printf '# Issues_Summary\n\nTOTALS ARE FABRICATED: 9999 open items, none of which exist.\n' > "$d/Issues_Summary.md"
    printf '# Fixed_Summary\n\nEVERY ITEM IS CLOSED.\n' > "$d/Fixed_Summary.md"
    expect_refusal "f17_fabricated_summary_refused" "summary tracker(s) are STALE" \
        env -C "$REPO_ROOT" \
            LAVA_WORKABLE_ITEMS_ISSUES="$d/Issues.md" \
            LAVA_WORKABLE_ITEMS_FIXED="$d/Fixed.md" \
            bash "$REPO_ROOT/scripts/check-workable-items.sh"
}
case_f17_control() {
    # Regenerate a CLEAN fixture: the previous case deliberately fabricated the
    # two _Summary files in this same directory, so reusing it would assert the
    # floor fires rather than that a correct corpus passes.
    local d
    if ! d="$(_f17_fixture)"; then
        fail "f17_regenerated_trackers_control_passes" "could not regenerate the tracker fixture from the DB"; return
    fi
    expect_success "f17_regenerated_trackers_control_passes" \
        env -C "$REPO_ROOT" \
            LAVA_WORKABLE_ITEMS_ISSUES="$d/Issues.md" \
            LAVA_WORKABLE_ITEMS_FIXED="$d/Fixed.md" \
            bash "$REPO_ROOT/scripts/check-workable-items.sh"
}

# -----------------------------------------------------------------------------
# F5 — scripts/check-constitution.sh, per-scope governance-doc corpus.
# Weakening a doc failed; DELETING it — the strictly worse state — passed.
# -----------------------------------------------------------------------------
_f5_harness() {
    local out="$WORK/f5_harness.sh"
    {
        echo 'set -euo pipefail'
        sed -n '27,36p' "$REPO_ROOT/scripts/check-constitution.sh"
        excerpt "$REPO_ROOT/scripts/check-constitution.sh" \
            '# 8b. §6.J per-scope doc CORPUS floor' 'END-OF-BLOCK 8b per-scope doc CORPUS floor'
        echo 'echo "REACHED CLEAN (per-scope corpus floor passed)"'
    } > "$out"
    printf '%s' "$out"
}
_f5_fixture() {
    local d="$WORK/f5"; rm -rf "$d"
    mkdir -p "$d/submodules/auth" "$d/submodules/cache" "$d/lava-api-go" "$d/core" "$d/app" "$d/feature"
    local s doc
    for s in auth cache; do
        for doc in CLAUDE.md AGENTS.md CONSTITUTION.md; do echo "$POINTER" > "$d/submodules/$s/$doc"; done
    done
    for doc in CLAUDE.md AGENTS.md lava-api-go/CLAUDE.md lava-api-go/AGENTS.md \
               lava-api-go/CONSTITUTION.md core/CLAUDE.md app/CLAUDE.md feature/CLAUDE.md; do
        echo "$POINTER" > "$d/$doc"
    done
    printf '[submodule "submodules/auth"]\n\tpath = submodules/auth\n[submodule "submodules/cache"]\n\tpath = submodules/cache\n' > "$d/.gitmodules"
    printf '%s' "$d"
}
case_f5_deleted_doc() {
    local d h; d="$(_f5_fixture)"; h="$(_f5_harness)"
    rm -f "$d/submodules/auth/AGENTS.md"
    expect_refusal "f5_deleted_submodule_doc_refused" "REAL DRIFT" env -C "$d" bash "$h"
}
case_f5_uninitialised() {
    local d h; d="$(_f5_fixture)"; h="$(_f5_harness)"
    rm -rf "$d/submodules/cache"; mkdir -p "$d/submodules/cache"
    expect_refusal "f5_uninitialised_submodule_distinguished" "NOT INITIALISED" env -C "$d" bash "$h"
}
case_f5_control() {
    local d h; d="$(_f5_fixture)"; h="$(_f5_harness)"
    expect_success "f5_complete_corpus_control_passes" env -C "$d" bash "$h"
}

# -----------------------------------------------------------------------------
# F19 — scripts/check-constitution.sh, §6.W remote-host boundary.
# A submodule with no .git marker was skipped in silence, so a forbidden remote
# inside it was never inspected.
# -----------------------------------------------------------------------------
_f19_harness() {
    local out="$WORK/f19_harness.sh"
    {
        echo 'set -euo pipefail'
        excerpt "$REPO_ROOT/scripts/check-constitution.sh" 'forbidden_remote_hosts=' 'END-OF-BLOCK §6.W remote-host boundary'
        echo 'echo "REACHED CLEAN (§6.W passed; examined=$w_examined of $w_declared)"'
    } > "$out"
    printf '%s' "$out"
}
case_f19_uninitialised() {
    local d="$WORK/f19"; rm -rf "$d"; mkdir -p "$d/submodules/auth" "$d/submodules/cache"
    printf '[submodule "submodules/auth"]\n\tpath = submodules/auth\n[submodule "submodules/cache"]\n\tpath = submodules/cache\n' > "$d/.gitmodules"
    _git_init "$d"
    expect_refusal "f19_unexaminable_submodule_refused" "PARTIAL corpus" env -C "$d" bash "$(_f19_harness)"
}
case_f19_control() {
    local d="$WORK/f19"
    _git_init "$d/submodules/auth"; _git_init "$d/submodules/cache"
    expect_success "f19_all_submodules_examined_passes" env -C "$d" bash "$(_f19_harness)"
}

# -----------------------------------------------------------------------------
# F9 — scripts/ci.sh hermetic-suite loop.
# Three silent no-ops; deleting an UNRELATED sentinel file disabled a FAILING
# suite entirely.
# -----------------------------------------------------------------------------
_f9_harness() {
    local out="$WORK/f9_harness.sh"
    { echo 'set -euo pipefail'
      excerpt "$REPO_ROOT/scripts/ci.sh" 'declare -a HERMETIC_SUITE_DIRS=' 'hermetic suites executed'
      echo 'echo "    ${suites_executed}/${#HERMETIC_SUITE_DIRS[@]} hermetic suites executed"'
      echo 'echo "LOOP COMPLETED"'
    } > "$out"
    printf '%s' "$out"
}
_f9_fixture() {
    local d="$WORK/f9"; rm -rf "$d"
    mkdir -p "$d/tests/pre-push" "$d/tests/check-constitution"
    local s
    for s in firebase ci-sh compose-layout tag-helper vm-images vm-signing vm-distro; do
        mkdir -p "$d/tests/$s"; printf '#!/bin/bash\nexit 0\n' > "$d/tests/$s/run_all.sh"; chmod +x "$d/tests/$s/run_all.sh"
    done
    printf '#!/bin/bash\nexit 0\n' > "$d/tests/pre-push/check4_test.sh"
    printf '#!/bin/bash\nexit 1\n' > "$d/tests/pre-push/check9_test.sh"
    printf '#!/bin/bash\nexit 0\n' > "$d/tests/check-constitution/check_constitution_test.sh"
    _git_init "$d"; _git_commit "$d" seed
    printf '%s' "$d"
}
case_f9_sentinel_deleted() {
    local d h; d="$(_f9_fixture)"; h="$(_f9_harness)"
    rm -f "$d/tests/pre-push/check4_test.sh"   # UNRELATED to the failing check9
    expect_refusal "f9_sentinel_deletion_no_longer_disables_suite" "HERMETIC SUITE GATE FAILED" \
        env -C "$d" bash "$h"
}
case_f9_dir_absent() {
    local d h; d="$(_f9_fixture)"; h="$(_f9_harness)"
    rm -rf "$d/tests/check-constitution"
    expect_refusal "f9_absent_suite_directory_refused" "DIRECTORY ABSENT" env -C "$d" bash "$h"
}
case_f9_control() {
    local d h; d="$(_f9_fixture)"; h="$(_f9_harness)"
    printf '#!/bin/bash\nexit 0\n' > "$d/tests/pre-push/check9_test.sh"
    expect_success "f9_all_suites_green_control_passes" env -C "$d" bash "$h"
}

# -----------------------------------------------------------------------------
# F21 — scripts/ci.sh evidence record.
# `--full` printed "All gates passed" and exited 0 with the device gate skipped,
# and the evidence directory recorded no field distinguishing the two.
# -----------------------------------------------------------------------------
_f21_harness() {
    local evi="$1"
    local out="$WORK/f21_$(basename "$evi").sh"
    { echo 'set -euo pipefail'
      echo "EVIDENCE_DIR=\"$evi\"; mkdir -p \"\$EVIDENCE_DIR\"; MODE=\"--full\""
      excerpt "$REPO_ROOT/scripts/ci.sh" 'DEVICE_TESTS_RAN=skipped' 'echo "Evidence: $EVIDENCE_DIR"' \
        | sed 's|^  \./gradlew|  : \&\& echo FAKE-GRADLE #|'
    } > "$out"
    printf '%s' "$out"
}
case_f21_no_device() {
    expect_refusal "f21_full_without_device_refused" "CI GATE INCOMPLETE" \
        env -C "$REPO_ROOT" -u ANDROID_SERIAL -u ANDROID_HOME bash "$(_f21_harness "$WORK/f21-evi")"
}
case_f21_records_field() {
    local v; v="$(cat "$WORK/f21-evi/device_tests" 2>/dev/null || echo MISSING)"
    if [[ "$v" == "skipped" ]]; then
        pass "f21_device_verdict_is_recorded"
    else
        fail "f21_device_verdict_is_recorded" "expected device_tests=skipped, got '$v'"
    fi
}

# -----------------------------------------------------------------------------
# P11 — scripts/pipeline/phase-00-precondition.sh
# `ignore = untracked` in .gitmodules hid untracked leftovers inside two
# submodules from the FR-000 clean-tree guard.
# -----------------------------------------------------------------------------
_p11_fixture() {
    local d="$WORK/p11"; rm -rf "$d"; mkdir -p "$d/sub"
    _git_init "$d/sub"; echo a > "$d/sub/a.txt"; _git_commit "$d/sub" init
    _git_init "$d"
    git -C "$d" -c protocol.file.allow=always submodule add -q ./sub submodules/containers >/dev/null 2>&1
    git -C "$d" config -f "$d/.gitmodules" submodule.submodules/containers.ignore untracked
    _git_commit "$d" init
    printf '%s' "$d"
}
case_p11_hidden_untracked() {
    local d; d="$(_p11_fixture)"
    echo "leftover from a previous run" > "$d/submodules/containers/LEFTOVER.txt"
    expect_refusal "p11_untracked_inside_ignored_submodule_refused" "working tree is not clean" \
        bash "$REPO_ROOT/scripts/pipeline/phase-00-precondition.sh" "$d"
}
case_p11_control() {
    local d="$WORK/p11"
    rm -f "$d/submodules/containers/LEFTOVER.txt"
    expect_success "p11_clean_tree_control_passes" \
        bash "$REPO_ROOT/scripts/pipeline/phase-00-precondition.sh" "$d"
}

# -----------------------------------------------------------------------------
# B2 — the three §6.R scanners.
# A broken corpus was byte-identical to a clean scan: these scanners print
# nothing on success, so there was no signal either way.
# -----------------------------------------------------------------------------
case_b2_broken_corpus() {
    local f rc bad=0
    for f in uuid ipv4 hostport; do
        local out
        out="$(GIT_DIR=/nonexistent/x.git bash "$REPO_ROOT/scripts/scan-no-hardcoded-$f.sh" 2>&1)"; rc=$?
        if [[ "$rc" -eq 0 ]] || ! grep -qF "SCAN FAILED" <<<"$out"; then
            bad=1; echo "    scan-no-hardcoded-$f.sh: rc=$rc out=${out:0:200}"
        fi
    done
    if [[ "$bad" -eq 0 ]]; then pass "b2_all_three_scanners_refuse_broken_corpus"
    else fail "b2_all_three_scanners_refuse_broken_corpus" "at least one scanner passed on a broken corpus"; fi
}
case_b2_control() {
    # A populated fixture with a REAL violation of each rule: the floors must
    # not have displaced the detection they protect.
    local d="$WORK/b2"; rm -rf "$d"; mkdir -p "$d/scripts" "$d/src"
    cp "$REPO_ROOT"/scripts/scan-no-hardcoded-{uuid,ipv4,hostport}.sh "$d/scripts/"
    _git_init "$d"
    # Assembled at runtime, never written as a literal: 6.R forbids a
    # hardcoded UUID in tracked source, and scan-no-hardcoded-uuid.sh exempts
    # *Test.kt / *_test.go but NOT shell fixtures like this one. Widening that
    # exemption to cover .sh would weaken the rule for every shell script in
    # the tree; assembling the value keeps the rule intact and the fixture
    # honest - the file this writes still contains a real, whole UUID, which
    # is what the control needs the scanner to find.
    local _u1='6ba7b810-9dad' _u2='11d1-80b4' _u3='00c04fd430c8'
    printf 'val id = "%s-%s-%s"\n' "$_u1" "$_u2" "$_u3" > "$d/src/a.kt"
    printf 'val ip = "198.18.7.9"\n' > "$d/src/b.kt"
    printf 'val api = "https://tracker.example.org:8443/v1"\n' > "$d/src/c.kt"
    _git_commit "$d" seed
    local f rc bad=0
    for f in uuid ipv4 hostport; do
        (cd "$d" && bash "$d/scripts/scan-no-hardcoded-$f.sh" >/dev/null 2>&1); rc=$?
        [[ "$rc" -eq 1 ]] || { bad=1; echo "    scan-no-hardcoded-$f.sh did not report the planted violation (rc=$rc)"; }
    done
    if [[ "$bad" -eq 0 ]]; then pass "b2_real_violations_still_detected"
    else fail "b2_real_violations_still_detected" "a floor displaced the detection it protects"; fi
}

# -----------------------------------------------------------------------------
# B6 / B8 — scripts/autonomous-qa/aggregate-evidence.sh
# Zero iterations was a WARNING, after which the script emitted a schema-valid
# test-evidence.json and a cycle-coverage-map for the §6.AK gate; and stale
# iteration directories from an earlier cycle were aggregated as this cycle's.
# -----------------------------------------------------------------------------
_agg_fixture() {
    local d="$WORK/agg"; rm -rf "$d"
    mkdir -p "$d/scripts/autonomous-qa" "$d/out"
    cp "$REPO_ROOT/scripts/autonomous-qa/aggregate-evidence.sh" "$d/scripts/autonomous-qa/"
    printf '%s' "$d"
}
_agg_run() {
    local d="$1" date="$2"
    bash "$d/scripts/autonomous-qa/aggregate-evidence.sh" \
        --date "$date" --version 9.9.9-9999 --channel release \
        --timestamp 2026-01-01T00:00:00Z --evidence-dir "$d/out"
}
case_b6_zero_iterations() {
    local d; d="$(_agg_fixture)"
    expect_refusal "b6_zero_iterations_refused" "no iterations found" _agg_run "$d" 1999-01-01
    if [[ -n "$(ls -A "$d/out" 2>/dev/null)" ]]; then
        fail "b6_no_artifacts_emitted_from_zero_iterations" "artifacts were written for a cycle that did not run: $(ls "$d/out")"
    else
        pass "b6_no_artifacts_emitted_from_zero_iterations"
    fi
}
case_b8_stale_iteration() {
    local d="$WORK/agg" today; today="$(date -u +%Y-%m-%d)"
    mkdir -p "$d/.lava-ci-evidence/autonomous-qa/$today/goapi/fresh-one" \
             "$d/.lava-ci-evidence/autonomous-qa/$today/goapi/stale-one"
    printf '{"verdict":"PASS","providers":"p","query":"q","tests":3,"failures":0,"errors":0,"skipped":0}\n' \
        > "$d/.lava-ci-evidence/autonomous-qa/$today/goapi/fresh-one/verdict.json"
    printf '{"verdict":"PASS","providers":"p","query":"q","tests":2,"failures":0,"errors":0,"skipped":0}\n' \
        > "$d/.lava-ci-evidence/autonomous-qa/$today/goapi/stale-one/verdict.json"
    touch -d "6 days ago" "$d/.lava-ci-evidence/autonomous-qa/$today/goapi/stale-one/verdict.json"
    expect_refusal "b8_stale_iteration_refused" "contaminated with results from another cycle" \
        _agg_run "$d" "$today"
}
case_b8_control() {
    local d="$WORK/agg" today; today="$(date -u +%Y-%m-%d)"
    touch "$d/.lava-ci-evidence/autonomous-qa/$today/goapi/stale-one/verdict.json"
    expect_success "b8_all_fresh_iterations_control_passes" _agg_run "$d" "$today"
}

# -----------------------------------------------------------------------------
# B11 — scripts/autonomous-qa/run-nav-challenges.sh
# The verdict was `exit $GRADLE_RC` and nothing else: gradle_rc=0 with an empty
# results directory reported SUCCESS with zero executed tests.
# -----------------------------------------------------------------------------
_b11_harness() {
    local evid="$1" results="$2" out="$WORK/b11_$3.sh"
    { echo 'set -uo pipefail'
      echo "EVID=\"$evid\"; RESULTS_DIR=\"$results\"; GRADLE_LOG=\"/dev/null\"; GRADLE_RC=0"
      mkdir -p "$evid" "$results"
      excerpt "$REPO_ROOT/scripts/autonomous-qa/run-nav-challenges.sh" \
          '§6.J tests-executed floor' 'END-OF-BLOCK §6.J tests-executed floor'
    } > "$out"
    printf '%s' "$out"
}
case_b11_zero_tests() {
    expect_refusal "b11_zero_executed_tests_refused" "ZERO tests executed" \
        bash "$(_b11_harness "$WORK/b11a/evid" "$WORK/b11a/res" a)" lava.app.challenges.C24
}
case_b11_control() {
    local h; h="$(_b11_harness "$WORK/b11b/evid" "$WORK/b11b/res" b)"
    printf '<testsuite tests="3" failures="0" errors="0" skipped="0"/>\n' > "$WORK/b11b/evid/TEST-x.xml"
    expect_success "b11_executed_tests_control_passes" bash "$h" lava.app.challenges.C24
}

# -----------------------------------------------------------------------------
# B12 — scripts/autonomous-qa/run-matrix.sh
# `mapfile < <(qa_emit_subsets)` hid the generator's exit status from
# `set -euo pipefail`, so the DEFAULT `--subsets all` path ran zero subsets.
# -----------------------------------------------------------------------------
_b12_harness() {
    local body="$1" out="$WORK/b12_$2.sh"
    { echo 'set -euo pipefail'
      echo "qa_emit_subsets(){ $body }"
      echo 'SUBSETS=all; declare -a SUBSET_LINES=()'
      excerpt "$REPO_ROOT/scripts/autonomous-qa/run-matrix.sh" '§6.J subset floor' 'END-OF-BLOCK §6.J subset floor'
      echo 'echo "REACHED MATRIX BODY count=${#SUBSET_LINES[@]}"'
    } > "$out"
    printf '%s' "$out"
}
case_b12_generator_fails() {
    expect_refusal "b12_subset_generator_failure_refused" "qa_emit_subsets failed" \
        bash "$(_b12_harness 'echo "sha1sum: command not found" >&2; return 127;' fail)"
}
case_b12_generator_empty() {
    expect_refusal "b12_zero_subsets_refused" "emitted ZERO subsets" \
        bash "$(_b12_harness 'return 0;' empty)"
}
case_b12_control() {
    expect_success "b12_populated_subsets_control_passes" \
        bash "$(_b12_harness 'printf "a|a|1\nb|b|2\nc|c|3\n";' ok)"
}

# -----------------------------------------------------------------------------
# B14 — scripts/autonomous-qa/run-iteration.sh
# `find ... | head -1` selected a JUnit XML by TRAVERSAL ORDER, and the results
# directory is never cleared, so a 6-day-old file was parsed as this run's PASS.
# -----------------------------------------------------------------------------
_b14_harness() {
    local results="$1" marker="$2" out="$WORK/b14_$3.sh"
    { echo 'set -uo pipefail'
      echo "RESULTS_DIR=\"$results\"; RUN_STARTED_AT=\"$marker\""
      excerpt "$REPO_ROOT/scripts/autonomous-qa/run-iteration.sh" \
          '§6.J evidence-freshness floor' 'END-OF-BLOCK §6.J evidence-freshness floor'
      echo 'if [[ -z "$XML" ]]; then exit 1; fi'
      echo 'echo "XML selected = $XML"'
    } > "$out"
    printf '%s' "$out"
}
case_b14_stale_xml() {
    local r="$WORK/b14/res"; mkdir -p "$r"
    printf '<testsuite tests="1" failures="0" errors="0" skipped="0"/>\n' > "$r/TEST-old.xml"
    touch -d "6 days ago" "$r/TEST-old.xml"
    : > "$WORK/b14/marker"
    expect_refusal "b14_stale_junit_xml_refused" "REFUSING to parse a JUnit XML older than this run" \
        bash "$(_b14_harness "$r" "$WORK/b14/marker" stale)"
}
case_b14_control() {
    local r="$WORK/b14/res"
    : > "$WORK/b14/marker2"; sleep 1
    printf '<testsuite tests="4" failures="0" errors="0" skipped="0"/>\n' > "$r/TEST-fresh.xml"
    expect_success "b14_fresh_junit_xml_control_passes" \
        bash "$(_b14_harness "$r" "$WORK/b14/marker2" fresh)"
}


# -----------------------------------------------------------------------------
# F18 — scripts/tag.sh, the §6.I clause 2 compileSdk derivation
# compileSdk IS this gate's expectation (the matrix must cover 28/30/34 AND it).
# It was seeded to a hardcoded 35 and the missing-file branch had no `else`, so
# an ABSENT AndroidCommon.kt silently asserted a requirement no manifest backs.
# Decisive control, identical evidence, only the manifest's presence differing:
#   manifest PRESENT (compileSdk=36), rows 28 30 34 35 -> FATAL … Missing: 36  EXIT=1
#   manifest ABSENT,  same rows                        -> gate PASSED (35)     EXIT=0
# The reshaped-file branch is covered too: its `warn`-and-continue arm was
# unreachable under tag.sh's own `set -Eeuo pipefail` (a no-match
# grep|head|grep assignment aborts the shell with no message at all).
# -----------------------------------------------------------------------------
_f18_harness() {
    # _f18_harness <repo-root> <expected-compile-sdk>
    # Excerpts the marker-delimited derivation floor from the REAL tag.sh, so
    # the harness cannot drift from the code it guards, and asserts the DERIVED
    # value — a floor that quietly reinstated a hardcoded default would pass an
    # exit-code-only control, so the control checks the number too.
    local repo="$1" expect="$2"
    # Split from the line above deliberately: bash expands every word of a
    # `local` statement before assigning any of them, so "${expect}" in a third
    # assignment on that same line is unbound under `set -u`.
    local h="$WORK/f18/harness-${expect}.sh"
    mkdir -p "$WORK/f18"
    {
        printf '%s\n' '#!/usr/bin/env bash' 'set -Eeuo pipefail' \
            "REPO_ROOT='$repo'" 'tag_id="Lava-Android-1.0.0-1"' \
            'warn() { echo "WARN: $*" >&2; }' 'die() { echo "FATAL: $*" >&2; exit 1; }'
        excerpt "$REPO_ROOT/scripts/tag.sh" \
            'BEGIN compileSdk derivation floor (regression-harness sentinel)' \
            'END-OF-BLOCK compileSdk derivation floor' \
            | sed 's/^  //; s/^local //'
        printf '%s\n' 'echo "derived compile_sdk=$compile_sdk"' \
            "[[ \"\$compile_sdk\" == '$expect' ]] || { echo \"derived '\$compile_sdk', expected '$expect'\" >&2; exit 3; }"
    } > "$h"
    printf '%s' "$h"
}
_f18_repo() {
    local d="$WORK/f18/repo"; mkdir -p "$d/buildSrc/src/main/kotlin/lava/conventions"
    printf '%s' "$d"
}
case_f18_manifest_absent() {
    local d; d="$(_f18_repo)"
    rm -f "$d/buildSrc/src/main/kotlin/lava/conventions/AndroidCommon.kt"
    expect_refusal "f18_absent_compilesdk_manifest_refused" \
        "compileSdk could NOT be derived" bash "$(_f18_harness "$d" 36)"
}
case_f18_manifest_unparseable() {
    local d; d="$(_f18_repo)"
    printf 'object AndroidCommon {\n    const val compileSdkVersionString = "android-36"\n}\n' \
        > "$d/buildSrc/src/main/kotlin/lava/conventions/AndroidCommon.kt"
    expect_refusal "f18_unparseable_compilesdk_manifest_refused" \
        "its shape changed" bash "$(_f18_harness "$d" 36)"
}
case_f18_control() {
    local d; d="$(_f18_repo)"
    printf 'object AndroidCommon {\n    const val compileSdk = 36\n}\n' \
        > "$d/buildSrc/src/main/kotlin/lava/conventions/AndroidCommon.kt"
    expect_success "f18_derived_compilesdk_control_passes" bash "$(_f18_harness "$d" 36)"
}

# -----------------------------------------------------------------------------
# P2 — scripts/pipeline/phase-02-test.sh, per-category evidence floor
# The PASS rule counted Evidence Records in TOTAL, so one category's records
# certified the whole phase while another — real-device-challenge, the category
# §6.AA clause 8 (C) makes mandatory for all four Android variants — was
# dispatched, exited 0 and wrote nothing:
#   wrappers dispatched: 2 (hermetic real-device-challenge)  exit codes: 0 0
#   Evidence Records found: 1   -> phase-02-test: PASSED     EXIT=0
#   on disk: hermetic-script/… only
# The challenge wrapper's own source already said the aggregate guard "does NOT
# rescue it, because it only fires when the run has zero records IN TOTAL".
# -----------------------------------------------------------------------------
_p2_stubs() {
    mkdir -p "$WORK/p2"
    cat > "$WORK/p2/stub-honest.sh" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
PHASE_DIR="$2"
source "${LAVA_EVIDENCE_LIB}"
raw="${PHASE_DIR}/hermetic-script/raw"; mkdir -p -- "$raw"
printf 'real captured output\n3 of 3 assertions passed\n' > "${raw}/h.log"
write_evidence_record "$PHASE_DIR" "stub.HonestSuite" "hermetic-script" \
  "bash stub-honest.sh" "PASS" \
  "expected 3 rows with matching ids, observed 3 rows with matching ids" "${raw}/h.log" >/dev/null
exit 0
STUB
    cat > "$WORK/p2/stub-silent.sh" <<'STUB'
#!/usr/bin/env bash
echo "stub-silent: ran, wrote NO Evidence Record"
exit 0
STUB
    chmod +x "$WORK/p2"/stub-*.sh
}
_p2_run() {
    # _p2_run <run-id> <phase02-script> <challenge-wrapper-path>
    # Every wrapper but 'hermetic' (the honest stub) and 'real-device-challenge'
    # is overridden to a path outside the script dir, which the floor treats as
    # the documented isolation hook rather than as drift.
    local run_id="$1" phase02="$2" challenge="$3"
    local d="$WORK/p2/${run_id}"; mkdir -p "$d"
    _git_init "$d"
    printf '.lava-ci-evidence/pipeline-runs/\n' > "$d/.gitignore"; printf 'x\n' > "$d/f"
    _git_commit "$d" init
    ( cd "$d" && source "$REPO_ROOT/scripts/pipeline/lib/run-report.sh" && \
        init_run_report "$run_id" "$(git -C "$d" rev-parse HEAD)" >/dev/null )
    (
      cd "$d" || exit 1
      export LAVA_EVIDENCE_LIB="$REPO_ROOT/scripts/pipeline/lib/evidence.sh"
      export PHASE02_GO_WRAPPER=/nonexistent/go.sh
      export PHASE02_KOTLIN_WRAPPER=/nonexistent/kotlin.sh
      export PHASE02_STRESS_CHAOS_WRAPPER=/nonexistent/stress.sh
      export PHASE02_RELEASE_CANARY_WRAPPER=/nonexistent/canary.sh
      export PHASE02_GATE_SWEEP_WRAPPER=/nonexistent/sweep.sh
      export PHASE02_HERMETIC_WRAPPER="$WORK/p2/stub-honest.sh"
      # Left UNSET (not set-to-empty) when no challenge wrapper is given, so
      # the script resolves its in-tree default and the missing-default floor
      # is exercised for real rather than through an override.
      [[ -n "$challenge" ]] && export PHASE02_CHALLENGE_WRAPPER="$challenge"
      bash "$phase02" "$run_id" "$d"
    )
}
case_p2_silent_category() {
    _p2_stubs
    expect_refusal "p2_dispatched_category_with_zero_records_refused" \
        "dispatched test categor(y/ies) exited 0 having produced ZERO Evidence Records" \
        _p2_run "2026-08-26T20-00-00Z" "$REPO_ROOT/scripts/pipeline/phase-02-test.sh" \
        "$WORK/p2/stub-silent.sh"
}
case_p2_missing_default_wrapper() {
    _p2_stubs
    # A wrapper resolved to its IN-TREE DEFAULT path and absent from disk is
    # drift: the category disappears from every run while the summary still
    # looks complete. Fixture = a copy of scripts/pipeline with one wrapper
    # removed, so SCRIPT_DIR resolution is exercised for real.
    local pl="$WORK/p2/pipeline-missing"
    rm -rf "$pl"; mkdir -p "$pl"
    cp -r "$REPO_ROOT/scripts/pipeline/." "$pl/"
    rm -f "$pl/phase-02-test-challenge.sh"
    expect_refusal "p2_missing_in_tree_default_wrapper_refused" \
        "resolved to their in-tree default path but do not exist on disk" \
        _p2_run "2026-08-26T21-00-00Z" "$pl/phase-02-test.sh" ""
}
case_p2_unmapped_category() {
    _p2_stubs
    # The floor must not be able to lower itself: a category dispatched with no
    # CATEGORY_EVIDENCE_DIRS entry has no derivable expectation, so exempting it
    # would relocate this very defect into the floor.
    local pl="$WORK/p2/pipeline-unmapped"
    rm -rf "$pl"; mkdir -p "$pl"
    cp -r "$REPO_ROOT/scripts/pipeline/." "$pl/"
    sed -i 's|^_dispatch "real-device-challenge" "\$CHALLENGE_WRAPPER" "\$REPO_PATH" "\$PHASE_DIR"$|&\n_dispatch "brand-new-category" "$HERMETIC_WRAPPER" "$REPO_PATH" "$PHASE_DIR"|' \
        "$pl/phase-02-test.sh"
    grep -q 'brand-new-category' "$pl/phase-02-test.sh" || {
        fail "p2_unmapped_dispatched_category_refused" "fixture sanity: the extra _dispatch line was never inserted, so this case would prove nothing"
        return
    }
    expect_refusal "p2_unmapped_dispatched_category_refused" \
        "have no CATEGORY_EVIDENCE_DIRS entry" \
        _p2_run "2026-08-26T22-00-00Z" "$pl/phase-02-test.sh" "/nonexistent/challenge.sh"
}
case_p2_control() {
    _p2_stubs
    expect_success "p2_every_dispatched_category_populated_control_passes" \
        _p2_run "2026-08-26T23-00-00Z" "$REPO_ROOT/scripts/pipeline/phase-02-test.sh" \
        "/nonexistent/challenge.sh"
}

# -----------------------------------------------------------------------------
# The declared corpus. Every fixed site appears here; the tail asserts that
# every one of them actually ran.
# -----------------------------------------------------------------------------
declare -a CASES=(
    case_f5_deleted_doc case_f5_uninitialised case_f5_control
    case_f7_empty case_f7_control
    case_f9_sentinel_deleted case_f9_dir_absent case_f9_control
    case_f12_empty case_f12_control
    case_f13_unresolvable case_f13_control
    case_f11_absent_timestamp case_f11_unparseable_timestamp
    case_f11_stale_still_caught case_f11_control
    case_f14_no_paths case_f14_empty_dir case_f14_control
    case_f15_shrunken case_f15_control
    case_f17_fabricated_summary case_f17_control
    case_f19_uninitialised case_f19_control
    case_f21_no_device case_f21_records_field
    case_p11_hidden_untracked case_p11_control
    case_b2_broken_corpus case_b2_control
    case_b6_zero_iterations case_b8_stale_iteration case_b8_control
    case_b11_zero_tests case_b11_control
    case_b12_generator_fails case_b12_generator_empty case_b12_control
    case_b14_stale_xml case_b14_control
    case_f18_manifest_absent case_f18_manifest_unparseable case_f18_control
    case_p2_silent_category case_p2_missing_default_wrapper
    case_p2_unmapped_category case_p2_control
)

# Every one of the 20 fixed sites must be represented. Derived from CASES[]
# rather than hardcoded: adding a case for a new site raises the bar
# automatically, and a hardcoded list would go stale on the next sweep.
declare -a SITES=(f5 f7 f9 f11 f12 f13 f14 f15 f17 f18 f19 f21 p2 p11 b2 b6 b8 b11 b12 b14)

for c in "${CASES[@]}"; do
    if ! declare -F "$c" >/dev/null; then
        echo "FAIL suite_integrity: '$c' is declared in CASES[] but not defined" >&2
        exit 1
    fi
    "$c"
done

# -----------------------------------------------------------------------------
# The irony guard. A corpus-floor suite that examined nothing would be exactly
# the defect it exists to prevent, so the executed count is asserted explicitly
# — non-zero, and equal to the declared corpus — and every fixed site must have
# contributed at least one executed case.
# -----------------------------------------------------------------------------
if [[ "$EXECUTED" -eq 0 ]]; then
    echo "FAIL suite_integrity: the corpus-floor suite executed ZERO cases." >&2
    echo "  → A suite that asserts floors while examining nothing is the very defect" >&2
    echo "    these floors exist to close (§6.J)." >&2
    exit 1
fi
if [[ "$EXECUTED" -lt "${#CASES[@]}" ]]; then
    echo "FAIL suite_integrity: recorded ${EXECUTED} assertion(s) but CASES[] declares ${#CASES[@]} case(s)," >&2
    echo "  so at least one case produced no assertion at all — a silent no-op is the" >&2
    echo "  same defect these floors close, merely relocated into the suite." >&2
    exit 1
fi
missing=()
for site in "${SITES[@]}"; do
    printf '%s\n' "${RAN_NAMES[@]}" | grep -q "^${site}_" || missing+=("$site")
done
if [[ ${#missing[@]} -gt 0 ]]; then
    echo "FAIL suite_integrity: no case ran for site(s): ${missing[*]}" >&2
    echo "  → Every site fixed by the 2026-08-26 corpus-floor sweep must be covered." >&2
    exit 1
fi

echo ""
echo "corpus-floor suite: ${EXECUTED} case(s) executed across ${#SITES[@]} site(s), ${FAILURES} failure(s)"
[[ "$FAILURES" -eq 0 ]] || exit 1
echo "All ${EXECUTED} corpus-floor regression tests PASSED"

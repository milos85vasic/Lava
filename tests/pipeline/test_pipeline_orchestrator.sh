#!/usr/bin/env bash
# Hermetic test suite for scripts/pipeline-build-test-distribute.sh (T038's
# phase wiring, and the FR-000 safety boundary as seen THROUGH the
# orchestrator rather than through phase-00-precondition.sh directly).
#
# WHY A SEPARATE SUITE FROM test_phase_00_precondition.sh:
# that suite proves the precondition SCRIPT behaves correctly in isolation.
# This suite proves the ORCHESTRATOR honours it — that the guard is actually
# reachable, actually halts the run, actually gets recorded in report.json,
# and actually cannot be switched off via --skip. A correct guard that the
# orchestrator forgets to consult would pass the other suite and still ship
# the exact hole FR-000 exists to close.
#
# SCOPE — deliberately bounded, and stated plainly rather than implied:
# every case here runs with `--until precondition`, against DISPOSABLE
# FIXTURE git repositories created by mktemp -d. Nothing here invokes
# Gradle, systemd, podman or an Android emulator, and nothing here touches
# this repository's real working tree or its real .lava-ci-evidence/ tree.
# The full end-to-end run across all eight wired phases is task T062, a human
# review gate that must first execute on a disposable branch — this suite
# does NOT cover it and must not be read as if it did.
#
# Exit 0 if every case passes; non-zero otherwise.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ORCH="${REPO_ROOT}/scripts/pipeline-build-test-distribute.sh"

if [[ ! -f "$ORCH" ]]; then
  echo "FAIL: script under test not found: $ORCH"
  exit 1
fi
for tool in jq git python3; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "FAIL: '$tool' is required to run this test suite but was not found on PATH"
    exit 1
  fi
done

FAILURES=0
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAILURES=$((FAILURES + 1)); }

WORKDIR="$(mktemp -d)"
trap 'rm -rf -- "$WORKDIR"' EXIT

# _new_fixture <name> [--no-ignore] — create a disposable git repo on master
# with one commit. Unless --no-ignore is given it carries the same
# ".lava-ci-evidence/pipeline-runs/" ignore rule the real repository has
# (task T003), because without it the pipeline dirties the tree it is about
# to test and can never satisfy FR-000 — a condition case 8 exercises on
# purpose.
_new_fixture() {
  local name="$1" no_ignore="${2:-}"
  local dir="${WORKDIR}/${name}"
  git init -q -b master "$dir"
  git -C "$dir" config user.email "fixture@example.invalid"
  git -C "$dir" config user.name "Pipeline Fixture"
  if [[ "$no_ignore" != "--no-ignore" ]]; then
    printf '.lava-ci-evidence/pipeline-runs/\n' > "${dir}/.gitignore"
  fi
  printf 'fixture\n' > "${dir}/README.md"
  git -C "$dir" add -A
  git -C "$dir" commit -qm "fixture init"
  printf '%s' "$dir"
}

# _run_orch <fixture-dir> [args...] — invoke the orchestrator from INSIDE the
# fixture (its CWD is what decides where report.json is written). Sets two
# globals: ORCH_RC (the real exit code) and ORCH_OUT (combined output).
#
# NOTE: callers MUST invoke this directly and then read the globals. They
# must NOT wrap it in a command substitution to capture its output, because
# command substitution runs the function in a SUBSHELL: any exit code it
# assigned to a global would be discarded the moment that subshell ended,
# and the caller would then read a stale or unbound ORCH_RC and could
# silently assert against the wrong run. Setting globals directly in the
# caller's own shell is the only way to get output and exit code back
# intact. (This bit once, for real, during this suite's own development.)
_run_orch() {
  local dir="$1"; shift
  local out_file="${WORKDIR}/orch-output.log"
  set +e
  ( cd "$dir" && bash "$ORCH" "$@" "$dir" ) >"$out_file" 2>&1
  ORCH_RC=$?
  set -e
  ORCH_OUT="$(cat "$out_file")"
}

# _latest_report <fixture-dir> — path of the most recent run's report.json.
_latest_report() {
  find "$1/.lava-ci-evidence/pipeline-runs" -name report.json 2>/dev/null | sort | tail -1
}

echo "==============================================================="
echo "GROUP A: usage guards (no run started, nothing touched)"
echo "==============================================================="

# SAFETY: every Group A invocation is aimed at a throwaway fixture repo and
# executed from inside it, even though each case is expected to be refused
# during argument parsing before any phase runs.
#
# This is not belt-and-braces pedantry — it is a response to a real incident
# during this suite's development. While rehearsing a deliberate mutation of
# the `--skip precondition` refusal, that guard stopped refusing, so the run
# fell through to the DEFAULT `--until live_verify` and began a real Gradle
# build against the real repository, which had to be killed. A guard-
# regression test that can start a real build on the developer's actual
# checkout when the guard regresses is itself a hazard. Aimed at a fixture,
# the same regression fails fast and harmlessly instead (the fixture has no
# gradlew, no systemd unit and no emulator).
#
# This matters MORE since T046: the default `--until` is now `docs_refresh`,
# so a fallen-through run would additionally reach the two phases that WRITE
# to the repository (phase-05a-changelog-entry.sh authors a CHANGELOG entry,
# phase-06-docs.sh edits documentation and regenerates exports). Aimed at a
# fixture, those never touch the real checkout either.
GUARDS_FIXTURE="$(_new_fixture guards)"

set +e
( cd "$GUARDS_FIXTURE" && bash "$ORCH" --help "$GUARDS_FIXTURE" ) >/dev/null 2>&1; rc=$?
set -e
if [[ "$rc" -eq 0 ]]; then pass "--help exits 0"; else fail "--help exited $rc, expected 0"; fi

# `closure` is the ONLY remaining not-wired phase name (T046 wired
# changelog_entry / distribute / docs_refresh; phase-07-closure.sh still does
# not exist, blocked behind T048/T049 + T054's review gate). It is listed here
# BY NAME rather than as "some unwired phase", because the property under test
# is that a real, spec'd, schema-enumerated phase which is not yet implemented
# is refused rather than silently skipped — which a mere typo like
# "bogus-phase" does not exercise.
for bad_until in "bogus-phase" "closure"; do
  set +e
  out="$( cd "$GUARDS_FIXTURE" && bash "$ORCH" --until "$bad_until" "$GUARDS_FIXTURE" 2>&1 )"; rc=$?
  set -e
  if [[ "$rc" -eq 2 ]]; then
    pass "--until '${bad_until}' exits 2"
  else
    fail "--until '${bad_until}' exited $rc, expected 2"
  fi
  if grep -q "is not a wired phase" <<< "$out"; then
    pass "--until '${bad_until}' explains that the phase is not wired"
  else
    fail "--until '${bad_until}' did not explain why it refused; output: ${out}"
  fi
done

# The mirror of the above: the three phases T046 wired must NOT be refused.
# Without this, deleting them from the registry again would leave this suite
# green — the not-wired guard would simply have more to refuse. `--skip` is
# passed for every phase past `precondition` so an accepted phase name is
# proved accepted WITHOUT running any real phase script.
for good_until in "changelog_entry" "distribute" "docs_refresh"; do
  # A FRESH fixture per iteration. run_ids are second-resolution and
  # init_run_report refuses to reuse one (R-010), so three invocations inside
  # the same second would collide in a shared fixture and exit 1 for a reason
  # that has nothing to do with what this check is about.
  ACCEPT_FIXTURE="$(_new_fixture "accept-${good_until}")"
  set +e
  out="$( cd "$ACCEPT_FIXTURE" && bash "$ORCH" --until "$good_until" \
            --skip build,test,install_boot,live_verify,changelog_entry,distribute,docs_refresh \
            "$ACCEPT_FIXTURE" 2>&1 )"; rc=$?
  set -e
  if grep -q "is not a wired phase" <<< "$out"; then
    fail "--until '${good_until}' was refused as not-wired, but T046 wired it"
  elif [[ "$rc" -ne 0 ]]; then
    fail "--until '${good_until}' was accepted but the run exited ${rc} with every phase past precondition skipped; output: ${out}"
  else
    pass "--until '${good_until}' is an accepted phase name and the skipped-tail run exits 0"
  fi
done

set +e
out="$( cd "$GUARDS_FIXTURE" && bash "$ORCH" --skip precondition "$GUARDS_FIXTURE" 2>&1 )"; rc=$?
set -e
if [[ "$rc" -eq 2 ]] && grep -q "refusing to skip 'precondition'" <<< "$out"; then
  pass "--skip precondition is REFUSED (FR-000 safety boundary cannot be switched off)"
else
  fail "--skip precondition exited $rc without an explicit refusal; output: ${out}"
fi

set +e
( cd "$GUARDS_FIXTURE" && bash "$ORCH" --bogus-flag "$GUARDS_FIXTURE" ) >/dev/null 2>&1; rc=$?
set -e
if [[ "$rc" -eq 2 ]]; then pass "unknown option exits 2"; else fail "unknown option exited $rc, expected 2"; fi

set +e
( cd "$GUARDS_FIXTURE" && bash "$ORCH" --until ) >/dev/null 2>&1; rc=$?
set -e
if [[ "$rc" -eq 2 ]]; then pass "--until with no value exits 2"; else fail "--until with no value exited $rc, expected 2"; fi

echo ""
echo "==============================================================="
echo "GROUP B: real runs against disposable fixture repositories"
echo "==============================================================="

# --- Case B1: clean master -> the run proceeds and PASSES ------------------
CLEAN="$(_new_fixture clean)"
_run_orch "$CLEAN" --until precondition; out="$ORCH_OUT"
if [[ "$ORCH_RC" -eq 0 ]]; then
  pass "clean master fixture: exits 0"
else
  fail "clean master fixture: exited ${ORCH_RC}, expected 0; output: ${out}"
fi

REPORT="$(_latest_report "$CLEAN")"
if [[ -n "$REPORT" && -f "$REPORT" ]]; then
  pass "clean master fixture: a report.json was written"
  if [[ "$(jq -r '.outcome' "$REPORT")" == "PASS" ]]; then
    pass "clean master fixture: outcome == PASS"
  else
    fail "clean master fixture: outcome is '$(jq -r '.outcome' "$REPORT")', expected PASS"
  fi
  n_phases="$(jq '.phases | length' "$REPORT")"
  if [[ "$n_phases" == "1" ]]; then
    pass "clean master fixture: exactly one phases[] entry (no double-append)"
  else
    fail "clean master fixture: phases[] has ${n_phases} entries, expected exactly 1 — a phase that self-appends must not also be appended by the orchestrator"
  fi
  if [[ "$(jq -r '.phases[0].name' "$REPORT")" == "precondition" && \
        "$(jq -r '.phases[0].result' "$REPORT")" == "PASS" ]]; then
    pass "clean master fixture: the recorded phase is precondition/PASS"
  else
    fail "clean master fixture: phases[0] is $(jq -c '.phases[0]' "$REPORT"), expected precondition/PASS"
  fi
  if [[ "$(jq -r '.completed_at' "$REPORT")" != "null" && -n "$(jq -r '.completed_at' "$REPORT")" ]]; then
    pass "clean master fixture: report was finalized (completed_at set)"
  else
    fail "clean master fixture: completed_at is unset — the report was never finalized"
  fi
else
  fail "clean master fixture: no report.json was written at all"
fi

# --- Case B2: dirty tracked file -> refuse, and DON'T misdiagnose ----------
DIRTY="$(_new_fixture dirty)"
printf 'a real uncommitted change\n' >> "${DIRTY}/README.md"
_run_orch "$DIRTY" --until precondition; out="$ORCH_OUT"
if [[ "$ORCH_RC" -eq 2 ]]; then
  pass "dirty tree: exits 2 (precondition's own code, not a remapped 1)"
else
  fail "dirty tree: exited ${ORCH_RC}, expected 2; output: ${out}"
fi
REPORT="$(_latest_report "$DIRTY")"
if [[ -n "$REPORT" ]] && [[ "$(jq -r '.outcome' "$REPORT")" == "FAIL" ]]; then
  pass "dirty tree: outcome == FAIL and the refusal is recorded in report.json"
else
  fail "dirty tree: outcome is '$(jq -r '.outcome' "$REPORT" 2>/dev/null)', expected FAIL"
fi
if grep -q "DIAGNOSIS" <<< "$out"; then
  fail "dirty tree: printed the self-diagnosis for a genuinely dirty TRACKED file — that diagnosis must only fire when the pipeline's own output is the dirt"
else
  pass "dirty tree: no false-positive self-diagnosis (the dirt is a real tracked change)"
fi

# --- Case B3: non-master branch -> refuse ---------------------------------
BRANCHED="$(_new_fixture branched)"
git -C "$BRANCHED" checkout -q -b some-feature-branch
_run_orch "$BRANCHED" --until precondition; out="$ORCH_OUT"
if [[ "$ORCH_RC" -eq 2 ]]; then
  pass "non-master branch: exits 2"
else
  fail "non-master branch: exited ${ORCH_RC}, expected 2; output: ${out}"
fi

# --- Case B4: the "pipeline dirtied its own tree" footgun -----------------
# A fixture with NO ignore rule for the run directory. The pipeline's own
# report.json makes the tree dirty, so FR-000 can never be satisfied. The
# orchestrator must say so explicitly instead of leaving the operator to
# hunt for it. Regression guard: git status --porcelain COLLAPSES a fully
# untracked directory to one entry, so a naive grep for the full path finds
# nothing — this case exists because that bug was real.
FOOTGUN="$(_new_fixture footgun --no-ignore)"
_run_orch "$FOOTGUN" --until precondition; out="$ORCH_OUT"
if [[ "$ORCH_RC" -eq 2 ]]; then
  pass "missing gitignore rule: exits 2"
else
  fail "missing gitignore rule: exited ${ORCH_RC}, expected 2; output: ${out}"
fi
# UPDATED 2026-08-26: a PREVENTIVE `git check-ignore` guard now runs before
# init_run_report creates anything, so this scenario is caught EARLIER than the
# post-hoc DIAGNOSIS this case originally asserted. The requirement is
# unchanged and the guarantee is strictly stronger: the orchestrator must still
# say plainly that its own output is the problem, and must now additionally
# leave the tree untouched. Asserting the old DIAGNOSIS string here would be
# asserting the WEAKER behaviour, so the assertion moved up, it did not relax.
#
# The post-hoc diagnosis is retained in the orchestrator as a second line of
# defence for the narrower case the guard cannot see: the ignore rule EXISTS,
# yet something under pipeline-runs still reports dirty (a tracked file there,
# or a negating pattern). It is no longer reachable via this fixture.
if grep -q "REFUSING TO START" <<< "$out" && grep -q "pipeline-runs" <<< "$out"; then
  pass "missing gitignore rule: refuses up front and names the offending run directory"
else
  fail "missing gitignore rule: the orchestrator did not refuse up front while naming its own output. Output was: ${out}"
fi

# The load-bearing half: refusing is only an improvement if it also created
# nothing. A refusal that still deposits a run directory leaves the operator
# with the same mess plus a better error message.
if [[ -e "${FOOTGUN}/.lava-ci-evidence" ]]; then
  fail "missing gitignore rule: REFUSED but still created ${FOOTGUN}/.lava-ci-evidence — the whole point of refusing before init_run_report is that nothing is left behind. Contents: $(find "${FOOTGUN}/.lava-ci-evidence" | head -5 | tr '\n' ' ')"
else
  pass "missing gitignore rule: nothing was created — the fixture tree is untouched"
fi

# And the tree it refused to dirty must genuinely still be clean, measured
# rather than inferred from the absence of that one directory.
if [[ -z "$(git -C "$FOOTGUN" status --porcelain --untracked-files=all)" ]]; then
  pass "missing gitignore rule: fixture working tree is still clean"
else
  fail "missing gitignore rule: fixture tree was dirtied despite the refusal: $(git -C "$FOOTGUN" status --porcelain --untracked-files=all | tr '\n' ' ')"
fi

echo ""
echo "==============================================================="
echo "GROUP C: FR-018 — every run restarts from scratch"
echo "==============================================================="

# _wait_next_second — spin until the UTC second ticks over, so two runs get
# distinct run_ids (the run_id is second-resolution). A spin rather than
# sleep keeps this suite free of timing calls.
_wait_next_second() {
  local start; start="$(date -u +%S)"
  while [[ "$(date -u +%S)" == "$start" ]]; do :; done
}

FR018="$(_new_fixture fr018)"
_run_orch "$FR018" --until precondition
_wait_next_second
_run_orch "$FR018" --until precondition

mapfile -t run_dirs < <(find "$FR018/.lava-ci-evidence/pipeline-runs" -mindepth 1 -maxdepth 1 -type d | sort)
if [[ "${#run_dirs[@]}" -eq 2 ]]; then
  pass "two invocations produced two distinct run directories"
  first_id="$(basename "${run_dirs[0]}")"
  second_id="$(basename "${run_dirs[1]}")"
  second_report="${run_dirs[1]}/report.json"
  if grep -qF "$first_id" "$second_report"; then
    fail "the second run's report.json references the first run's id (${first_id}) — FR-018 requires a run to never read a prior run's directory as input"
  else
    pass "the second run's report.json never references the first run's id (FR-018)"
  fi
  if [[ "$(jq -r '.run_id' "$second_report")" == "$second_id" ]]; then
    pass "the second run's report records its own run_id"
  else
    fail "the second run's report.json run_id does not match its own directory name"
  fi
else
  fail "expected 2 run directories after 2 invocations, found ${#run_dirs[@]}"
fi

echo ""
echo "==============================================================="
if [[ "$FAILURES" -eq 0 ]]; then
  echo "ALL CHECKS PASSED"
  exit 0
else
  echo "$FAILURES CHECK(S) FAILED"
  exit 1
fi

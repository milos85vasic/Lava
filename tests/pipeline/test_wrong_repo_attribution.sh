#!/usr/bin/env bash
# Hermetic regression test: a Pipeline Run Report must never claim a commit the
# run did not test, and a run against repository X must never deposit evidence
# in repository Y.
#
# FORENSIC ANCHOR (2026-08-26, measured — not hypothesised):
#
#     $ cd /path/to/lava
#     $ bash scripts/pipeline-build-test-distribute.sh /tmp/fixture --until precondition
#
# The fixture was a DIFFERENT repository, on master, clean. phase-00 inspected
# the fixture and passed. The report was then written into **Lava's own**
# .lava-ci-evidence/pipeline-runs/, and said:
#
#     "commit_sha": "545920a160cc8e5591bb4c9c7a87793f371219de",   <- LAVA's HEAD
#     "outcome":    "PASS",
#     "phases":     [{"name": "precondition", "result": "PASS"}]
#
# The fixture's HEAD was an entirely different sha. So: a PASS about repository
# X, attributed to a commit of repository Y that nothing had tested, left at
# rest in Y's evidence tree. §6.Z (same-SHA test evidence), §6.AK (coverage
# intersection) and §6.AA clause 8 condition (A) all match evidence to artifacts
# BY COMMIT SHA — which places that artifact exactly where a gate reads it as
# proof. Three of them were produced before the cause was found.
#
# THREE INDEPENDENT DEFECTS COMPOSED INTO ONE FALSE CLAIM:
#   (a) `commit_sha="$(git rev-parse HEAD)"` had no `-C "$REPO_ROOT"`, so it
#       described the current working directory, not the repository under test.
#   (b) RUN_DIR, the phase log paths, and lib/run-report.sh's _run_report_path
#       are all RELATIVE, so they resolved against the working directory too.
#   (c) the orchestrator never chdir'd, so nothing ever made those two agree.
#
# WHY NO EXISTING SUITE CAUGHT IT — the part worth remembering:
# tests/pipeline/test_pipeline_orchestrator.sh's _run_orch helper does
#
#     ( cd "$dir" && bash "$ORCH" "$@" "$dir" )
#
# i.e. it cd's into the fixture AND passes it as the override, so CWD and the
# repository under test are always the same directory. The defect lives entirely
# in the gap between them, and that harness closes the gap by construction.
# EVERY case in this suite therefore drives the orchestrator with its working
# directory and its `[repo-path]` argument DELIBERATELY DISAGREEING. That is the
# whole point of the file; a future edit that "tidies" it by cd-ing into the
# target first would silently restore the blind spot and this suite would pass
# forever without testing anything.
#
# SAFETY / SCOPE: every case runs `--until precondition`, so the only phase
# script that executes is phase-00-precondition.sh — two `git` reads. Nothing
# here invokes Gradle, podman, systemd or an emulator. The repository that plays
# "the operator's checkout" is a disposable fixture, NOT this repository, so
# even a fully regressed orchestrator writes its stray evidence into a temp dir.
# This repository's real .lava-ci-evidence/pipeline-runs/ is additionally
# snapshotted before and after and asserted unchanged (CASE 5), because the
# whole subject of this suite is evidence turning up where it should not.
#
# Exit 0 if every case passes; non-zero otherwise.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ORCH="${REPO_ROOT}/scripts/pipeline-build-test-distribute.sh"

[[ -f "$ORCH" ]] || { echo "FAIL: script under test not found: $ORCH"; exit 1; }
for tool in git python3; do
  command -v "$tool" >/dev/null 2>&1 || { echo "FAIL: '$tool' is required by this suite but was not found on PATH"; exit 1; }
done

FAILURES=0
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAILURES=$((FAILURES + 1)); }

# Counts orchestrator runs this suite actually completed. A suite that reports
# success having examined nothing is the vacuous pass this project has recorded
# ~50 instances of; the tail of this file fails on it explicitly.
EXAMINED=0

WORKDIR="$(mktemp -d)"
trap 'rm -rf -- "$WORKDIR"' EXIT

# Snapshot THIS repository's evidence tree up front (CASE 5 compares it at the
# end). Taken before any orchestrator run so it covers every case in the file.
LAVA_RUNS_DIR="${REPO_ROOT}/.lava-ci-evidence/pipeline-runs"
LAVA_BEFORE="${WORKDIR}/lava-runs-before.txt"
ls -1 "$LAVA_RUNS_DIR" 2>/dev/null | sort > "$LAVA_BEFORE" || true

# _new_repo <name> <content> — a disposable git repo on master with one commit
# and the ".lava-ci-evidence/pipeline-runs/" ignore rule (task T003; without it
# the pipeline dirties the tree it is about to test and cannot satisfy FR-000).
# <content> differs per repo so the HEAD shas differ — which is the entire
# discriminator this suite rests on.
_new_repo() {
  # Two statements, not one: bash expands every word of a `local` command
  # before executing it, so a `dir="${WORKDIR}/${name}"` sharing the line with
  # `name="$1"` reads an unset `name` and aborts under `set -u`.
  local name="$1" content="$2"
  local dir="${WORKDIR}/${name}"
  git init -q -b master "$dir"
  git -C "$dir" config user.email "fixture@example.invalid"
  git -C "$dir" config user.name "Pipeline Fixture"
  printf '.lava-ci-evidence/pipeline-runs/\n' > "${dir}/.gitignore"
  printf '%s\n' "$content" > "${dir}/README.md"
  git -C "$dir" add -A
  git -C "$dir" commit -qm "init ${name}"
  printf '%s' "$dir"
}

# _run_from <cwd> <args...> — invoke the orchestrator with the working directory
# set to <cwd>. Sets ORCH_RC and ORCH_OUT in the CALLER's shell; per the note in
# test_pipeline_orchestrator.sh, callers must not wrap this in a command
# substitution or the globals are lost with the subshell.
_run_from() {
  local cwd="$1"; shift
  local out_file="${WORKDIR}/orch-out.$$.log"
  set +e
  ( cd "$cwd" && bash "$ORCH" "$@" ) >"$out_file" 2>&1
  ORCH_RC=$?
  set -e
  ORCH_OUT="$(cat "$out_file")"
  EXAMINED=$((EXAMINED + 1))
}

# _reports_in <repo> — every report.json under a repo's pipeline-runs tree.
_reports_in() {
  # `|| true` because the tree legitimately does not exist for a repository
  # that (correctly) received no evidence — and under `set -o pipefail` a
  # failing `find` would abort the suite instead of reporting "found none",
  # turning the expected-good case into a crash.
  { find "$1/.lava-ci-evidence/pipeline-runs" -name report.json 2>/dev/null || true; } | sort
}

# _field <report.json> <key> — one top-level field, or the empty string.
_field() {
  python3 -c 'import json,sys
try:
    print(json.load(open(sys.argv[1])).get(sys.argv[2], ""))
except Exception:
    print("")' "$1" "$2"
}

# _assert_attribution <label> <target-repo> <decoy-repo>
#
# The shared body of cases 1-3. Asserts, for a run that has just completed:
#   * exactly one report exists, and it is inside the TARGET repository;
#   * its commit_sha is the TARGET's HEAD;
#   * its commit_sha is NOT the decoy's HEAD (stated separately so the failure
#     message can name the wrong-repo attribution rather than just a mismatch);
#   * the decoy repository gained no pipeline-runs directory at all.
_assert_attribution() {
  local label="$1" target="$2" decoy="$3"
  local target_head decoy_head
  target_head="$(git -C "$target" rev-parse HEAD)"
  decoy_head="$(git -C "$decoy" rev-parse HEAD)"

  if [[ "$target_head" == "$decoy_head" ]]; then
    fail "${label}: the two fixture repositories share a HEAD (${target_head}) — this suite cannot discriminate wrong-repo attribution and proves nothing"
    return
  fi

  local -a target_reports=()
  while IFS= read -r r; do [[ -n "$r" ]] && target_reports+=("$r"); done < <(_reports_in "$target")

  # Where the stray report ended up, if it is not where it belongs. Read BEFORE
  # returning, because the wrong-repo DEPOSIT and the wrong-repo ATTRIBUTION are
  # two distinct defects and the report only exists in one place to be asked.
  # Reporting only "found 0 here" would name the symptom that is easiest to see
  # and stay silent about the one that actually reaches a release gate.
  local -a decoy_reports=()
  while IFS= read -r r; do [[ -n "$r" ]] && decoy_reports+=("$r"); done < <(_reports_in "$decoy")

  if [[ "${#target_reports[@]}" -ne 1 ]]; then
    local stray_note=""
    if [[ "${#decoy_reports[@]}" -ge 1 ]]; then
      local stray_sha
      stray_sha="$(_field "${decoy_reports[0]}" commit_sha)"
      local stray_outcome
      stray_outcome="$(_field "${decoy_reports[0]}" outcome)"
      if [[ "$stray_sha" == "$decoy_head" ]]; then
        stray_note="
       WRONG-REPO ATTRIBUTION: the report landed in ${decoy} instead, carrying
       commit_sha ${stray_sha} (the HEAD of the repository the run was merely
       INVOKED FROM) and outcome ${stray_outcome:-<none>}. Nothing tested that
       commit. §6.Z / §6.AK / §6.AA clause 8 match evidence to artifacts by
       commit SHA, so that file is a false claim sitting where a gate reads it."
      else
        stray_note="
       The report landed in ${decoy} instead, carrying commit_sha ${stray_sha:-<empty>}
       and outcome ${stray_outcome:-<none>}."
      fi
    fi
    fail "${label}: WRONG-REPO DEPOSIT — expected exactly 1 report.json inside the repository under test (${target}, HEAD ${target_head}), found ${#target_reports[@]}.${stray_note}"
    return
  fi
  pass "${label}: the run's report was created inside the repository under test"

  local got
  got="$(_field "${target_reports[0]}" commit_sha)"
  if [[ "$got" == "$target_head" ]]; then
    pass "${label}: report commit_sha is the tested repository's HEAD (${target_head})"
  elif [[ "$got" == "$decoy_head" ]]; then
    fail "${label}: WRONG-REPO ATTRIBUTION — report.json claims commit_sha ${got}, which is the HEAD of the repository the run was merely INVOKED FROM (${decoy}), not of the repository it tested (${target}, HEAD ${target_head}). A PASS filed under a commit that was never tested is exactly what §6.Z / §6.AK / §6.AA clause 8 consume as same-SHA proof."
  else
    fail "${label}: report commit_sha is ${got:-<empty>}, which is neither the tested repository's HEAD (${target_head}) nor the invoking repository's (${decoy_head})"
  fi

  if [[ "${#decoy_reports[@]}" -eq 0 ]]; then
    pass "${label}: the repository the run was invoked from gained no evidence"
  else
    fail "${label}: WRONG-REPO DEPOSIT — the run verified ${target} but left ${#decoy_reports[@]} report(s) in ${decoy}: ${decoy_reports[*]}"
  fi
}

echo "==============================================================="
echo "CASE 1: absolute [repo-path] override, invoked from a DIFFERENT repo"
echo "==============================================================="
echo "The measured defect, reproduced exactly: CWD is one git repository, the"
echo "positional override names another. This is the shape no existing suite"
echo "exercises, because they all cd into the fixture they pass."
echo ""

C1_TARGET="$(_new_repo c1-target "target under test")"
C1_DECOY="$(_new_repo c1-decoy "decoy: stands in for the operator's own checkout")"

_run_from "$C1_DECOY" "$C1_TARGET" --until precondition

if [[ "$ORCH_RC" -eq 0 ]]; then
  pass "case1: the run completed (rc=0) against the clean master fixture"
else
  fail "case1: orchestrator exited ${ORCH_RC}, expected 0 for a clean fixture on master; output follows:
${ORCH_OUT}"
fi
_assert_attribution "case1" "$C1_TARGET" "$C1_DECOY"

echo ""
echo "==============================================================="
echo "CASE 2: RELATIVE [repo-path] override"
echo "==============================================================="
echo "A fix that prefixes paths with the override without canonicalising it"
echo "first would pass CASE 1 and corrupt this one: a relative path means"
echo "something different the moment the working directory moves."
echo ""

C2_TARGET="$(_new_repo c2-target "relative-override target")"
C2_DECOY="$(_new_repo c2-decoy "relative-override decoy")"

# Invoked from the decoy, naming the target by a path relative TO THE DECOY.
_run_from "$C2_DECOY" "../c2-target" --until precondition

if [[ "$ORCH_RC" -eq 0 ]]; then
  pass "case2: the run completed (rc=0) with a relative override"
else
  fail "case2: orchestrator exited ${ORCH_RC} with a relative override, expected 0; output follows:
${ORCH_OUT}"
fi
_assert_attribution "case2" "$C2_TARGET" "$C2_DECOY"

echo ""
echo "==============================================================="
echo "CASE 3: NO override, invoked from inside the repository under test"
echo "==============================================================="
echo "The ordinary invocation. It must keep working: a fix that relocated"
echo "evidence only when an override is present would leave the common path"
echo "untested and is not a fix, it is a special case."
echo ""

C3_TARGET="$(_new_repo c3-target "no-override target")"
C3_DECOY="$(_new_repo c3-decoy "no-override decoy, never named and never entered")"

_run_from "$C3_TARGET" --until precondition

if [[ "$ORCH_RC" -eq 0 ]]; then
  pass "case3: the run completed (rc=0) with no override"
else
  fail "case3: orchestrator exited ${ORCH_RC} with no override, expected 0; output follows:
${ORCH_OUT}"
fi
_assert_attribution "case3" "$C3_TARGET" "$C3_DECOY"

echo ""
echo "==============================================================="
echo "CASE 4: the PHASE LOGS must land in the same repository as the report"
echo "==============================================================="
echo "Guards the half-fix. Moving report.json while leaving the orchestrator's"
echo "per-phase logs behind splits one run across two repositories, which is"
echo "worse than the single wrong repository it replaced: the evidence a reader"
echo "is sent to and the evidence backing it would then live in different trees."
echo ""

C4_TARGET="$(_new_repo c4-target "phase-log target")"
C4_DECOY="$(_new_repo c4-decoy "phase-log decoy")"

_run_from "$C4_DECOY" "$C4_TARGET" --until precondition

# Same `|| true` reasoning as _reports_in: the decoy having no evidence tree at
# all is the PASSING outcome here, and must not read as a suite crash.
_count_logs() {
  { find "$1/.lava-ci-evidence/pipeline-runs" -name '*-orchestrator.log' 2>/dev/null || true; } | wc -l | tr -d ' '
}
C4_TARGET_LOGS="$(_count_logs "$C4_TARGET")"
C4_DECOY_LOGS="$(_count_logs "$C4_DECOY")"

if [[ "$C4_TARGET_LOGS" -ge 1 ]]; then
  pass "case4: ${C4_TARGET_LOGS} phase log(s) written inside the repository under test"
else
  fail "case4: no phase log was written inside the repository under test (${C4_TARGET}) — the run's report and its supporting logs are not in the same tree"
fi
if [[ "$C4_DECOY_LOGS" -eq 0 ]]; then
  pass "case4: no phase log leaked into the repository the run was invoked from"
else
  fail "case4: SPLIT-BASE — ${C4_DECOY_LOGS} phase log(s) were written into ${C4_DECOY}, the repository the run was merely invoked from, while the report belongs to ${C4_TARGET}"
fi

# The evidence_dir recorded in report.json must address the same tree, not a
# second spelling of a directory that only exists elsewhere.
C4_REPORT="$(_reports_in "$C4_TARGET" | head -1)"
if [[ -n "$C4_REPORT" ]]; then
  C4_EVDIR="$(python3 -c 'import json,sys
d=json.load(open(sys.argv[1]))
ps=d.get("phases",[])
print(ps[0]["evidence_dir"] if ps else "")' "$C4_REPORT")"
  if [[ -n "$C4_EVDIR" && -d "${C4_TARGET}/${C4_EVDIR}" ]]; then
    pass "case4: report.json's recorded evidence_dir ('${C4_EVDIR}') resolves inside the repository under test"
  else
    fail "case4: report.json records evidence_dir '${C4_EVDIR:-<none>}', which does not exist under the repository under test (${C4_TARGET}) — the recorded path and the written path disagree"
  fi
else
  fail "case4: no report.json was produced inside the repository under test, so its evidence_dir could not be checked"
fi

echo ""
echo "==============================================================="
echo "CASE 5: THIS repository's evidence tree gained nothing"
echo "==============================================================="
echo "The suite's own containment check. Every case above aims a fixture at a"
echo "fixture; if any run has instead written into the real checkout, the thing"
echo "under test has recurred inside its own regression test."
echo ""

LAVA_AFTER="${WORKDIR}/lava-runs-after.txt"
ls -1 "$LAVA_RUNS_DIR" 2>/dev/null | sort > "$LAVA_AFTER" || true
if diff -q "$LAVA_BEFORE" "$LAVA_AFTER" >/dev/null 2>&1; then
  pass "case5: $(wc -l < "$LAVA_BEFORE" | tr -d ' ') run director(ies) before and after — this repository's .lava-ci-evidence/pipeline-runs/ is unchanged"
else
  fail "case5: this repository's .lava-ci-evidence/pipeline-runs/ CHANGED during a suite that only ever aimed at temp fixtures:
$(diff "$LAVA_BEFORE" "$LAVA_AFTER" || true)"
fi

echo ""
echo "==============================================================="
# A pass reached without running the orchestrator at all would assert nothing
# while reading green — the defect class this repository has recorded roughly
# fifty times. The count is asserted, not printed and forgotten.
echo "EXAMINED ${EXAMINED} orchestrator run(s)."
if [[ "$EXAMINED" -ge 4 ]]; then
  pass "the suite examined ${EXAMINED} real orchestrator runs (>0, so no case is vacuous)"
else
  fail "the suite examined ${EXAMINED} orchestrator run(s), expected at least 4 — cases did not execute, so their assertions never ran and this result means nothing"
fi

echo "==============================================================="
if [[ "$FAILURES" -eq 0 ]]; then
  echo "ALL CHECKS PASSED"
  exit 0
else
  echo "$FAILURES CHECK(S) FAILED"
  exit 1
fi

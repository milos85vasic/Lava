#!/usr/bin/env bash
# tests/pipeline/test_phase_07_closure_hardening.sh — adversarial / edge-case
# hardening suite for scripts/pipeline/phase-07-closure.sh (T055).
#
# tests/pipeline/test_phase_07_closure.sh (written in parallel by a sibling
# agent) covers the core happy-path/governance behavior (submodule advance,
# governance-deny, constitution-exclusion, uninitialized-submodule-init,
# recursive nesting). THIS suite covers what that one does not:
#
#   I1  a surprise undeclared path causes REFUSAL, never a silent commit
#   I2  scripts/commit_all.sh's own push failure propagates as a phase FAIL
#   I3  the FR-017 post-closure verification is REAL and load-bearing — a
#       genuine leftover uninitialized recursive submodule that every earlier
#       step honestly reports as fine is still caught, only by step 4
#   I4  report.json's submodule_advances[] merge is correct, complete, and
#       draws from every level's own record directory, not just the top one
#   I5  --help and the two usage/precondition-error exits (missing run_id;
#       run_id naming no existing report.json) attempt nothing on disk
#
# SAFETY — READ BEFORE EDITING THIS FILE.
#
# scripts/commit_all.sh does NOT accept a repository-path override of any
# kind: it derives its own REPO_ROOT from `dirname "${BASH_SOURCE[0]}"/..`,
# i.e. from WHERE THE SCRIPT FILE ITSELF LIVES ON DISK, and unconditionally
# `cd`s there before doing anything else. Passing phase-07-closure.sh a
# [repo-path] override (its own documented mechanism for pointing Step 1/2 at
# a disposable fixture) does NOT necessarily redirect Step 3's invocation of
# commit_all.sh to the fixture — depending on how COMMIT_ALL_SCRIPT is
# resolved, invoking the REAL scripts/commit_all.sh from a fixture-pointed
# phase-07-closure.sh run could `git add`/`git commit`/`git push` in THIS
# repository, against ITS real "github"/"gitlab" remotes — exactly the kind
# of surprise this whole project's Anti-Bluff Pact and §6.T.3 exist to make
# impossible.
#
# This suite therefore NEVER relies on a [repo-path] override reaching
# commit_all.sh correctly. Every fixture is a FULLY SELF-CONTAINED disposable
# git repository that carries ITS OWN COPY of
# scripts/pipeline/phase-07-closure.sh, scripts/pipeline/lib/{run-report,
# evidence,anti-bluff-validate}.sh, scripts/advance-all-submodules.sh AND
# scripts/commit_all.sh, at the SAME relative layout this repository uses.
# phase-07-closure.sh is then invoked BY ITS COPY'S OWN PATH, with NO
# [repo-path] override — so its self-derived REPO_ROOT_OF_SCRIPT is the
# fixture root, commit_all.sh's copy self-derives the SAME fixture root
# (whether it resolves relative to its own BASH_SOURCE or relative to a
# passed-through REPO_ROOT — both agree here by construction, since no
# override is ever given), and every git operation either script performs —
# including every `git push` — targets ONLY the fixture's own disposable bare
# "github"/"gitlab" mirrors, never this repository or any real remote.
# mk_closure_fixture-style helpers below (copy_pipeline_scripts,
# mk_mirror_remote, ...) are what assembles this; every case in this file
# builds its fixture through them.
#
# Every fixture directory is removed on EXIT, whatever failed. Nothing here
# ever touches this repository's real submodules/, real .git, or real
# upstream remotes — that is the property this copy-based construction
# exists to guarantee, not merely intend.
#
# Exit 0 if every case passes; non-zero otherwise.

set -uo pipefail

# SOURCE_REPO_ROOT names the checkout that carries the REAL scripts under
# test (scripts/pipeline/phase-07-closure.sh and its dependencies). It is a
# fixed absolute path rather than this test file's own on-disk location
# because this suite is authored and run from an isolated git-worktree copy
# of this repository that does not itself carry a real
# scripts/pipeline/phase-07-closure.sh on disk (the file under test is a
# same-repository, not-yet-committed addition living in the primary
# checkout). SOURCE_REPO_ROOT is used ONLY to READ FROM (via
# copy_pipeline_scripts, below) — every fixture this suite builds is an
# independent, disposable, self-contained copy; nothing here ever writes to,
# commits in, or pushes from SOURCE_REPO_ROOT itself. If this suite is moved
# into a checkout that DOES carry the real scripts at the conventional
# `../..` relative location, override via
# LAVA_TEST_SOURCE_REPO_ROOT=/path/to/repo before invoking this file.
SOURCE_REPO_ROOT="${LAVA_TEST_SOURCE_REPO_ROOT:-/home/milosvasic/Projects/lava}"
if [[ ! -f "${SOURCE_REPO_ROOT}/scripts/pipeline/phase-07-closure.sh" ]]; then
  echo "FAIL: SOURCE_REPO_ROOT ('${SOURCE_REPO_ROOT}') does not carry scripts/pipeline/phase-07-closure.sh — set LAVA_TEST_SOURCE_REPO_ROOT to the checkout that does"
  exit 1
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "FAIL: jq is required to run this test suite but was not found on PATH"
  exit 1
fi
if ! command -v python3 >/dev/null 2>&1; then
  echo "FAIL: python3 is required to run this test suite but was not found on PATH"
  exit 1
fi

FIXTURE_DIRS=()
cleanup() {
  for d in "${FIXTURE_DIRS[@]:-}"; do
    if [[ -n "$d" && -d "$d" ]]; then
      chmod -R u+w "$d" 2>/dev/null || true
      rm -rf -- "$d"
    fi
  done
}
trap cleanup EXIT

FAILURES=0

expect_eq() {
  local label="$1" what="$2" expected="$3" actual="$4"
  if [[ "$expected" == "$actual" ]]; then
    echo "PASS: ${label}: ${what} == '${expected}'"
  else
    echo "FAIL: ${label}: ${what} expected '${expected}', got '${actual}'"
    FAILURES=$((FAILURES + 1))
  fi
}

expect_ne() {
  local label="$1" what="$2" not_expected="$3" actual="$4"
  if [[ "$not_expected" != "$actual" ]]; then
    echo "PASS: ${label}: ${what} ('${actual}') differs from '${not_expected}' as required"
  else
    echo "FAIL: ${label}: ${what} expected to differ from '${not_expected}' but was identical"
    FAILURES=$((FAILURES + 1))
  fi
}

expect_contains() {
  local label="$1" what="$2" needle="$3" haystack="$4"
  if [[ "$haystack" == *"$needle"* ]]; then
    echo "PASS: ${label}: ${what} mentions '${needle}'"
  else
    echo "FAIL: ${label}: ${what} never mentions '${needle}'; got: ${haystack}"
    FAILURES=$((FAILURES + 1))
  fi
}

expect_true() {
  local label="$1" what="$2" cond="$3"
  if [[ "$cond" == "true" ]]; then
    echo "PASS: ${label}: ${what}"
  else
    echo "FAIL: ${label}: ${what} — condition was false"
    FAILURES=$((FAILURES + 1))
  fi
}

gcfg() {
  git -C "$1" config user.email "fixture@example.invalid"
  git -C "$1" config user.name "Fixture"
  git -C "$1" config commit.gpgsign false
}

# mk_bare_from_scratch <bare-path> <content-line>
# Builds a bare repo on `master` with one commit whose seed.txt reads
# <content-line>. Used for every "own upstream" bare repo in this suite.
mk_bare_from_scratch() {
  local bare="$1" content="$2" scratch
  scratch="$(mktemp -d "${TMPDIR:-/tmp}/c07-seed-XXXXXX")"
  git init --quiet --initial-branch=master "$scratch"
  gcfg "$scratch"
  printf '%s\n' "$content" > "${scratch}/seed.txt"
  git -C "$scratch" add -A
  git -C "$scratch" commit --quiet -m "seed: ${content}"
  git init --quiet --bare --initial-branch=master "$bare"
  git -C "$scratch" remote add origin "$bare"
  git -C "$scratch" push --quiet origin HEAD:master
  rm -rf -- "$scratch"
}

# up_commit <bare> <message> <filename> <content> — pushes one more commit to
# an existing bare repo's master, from a throwaway clone.
up_commit() {
  local bare="$1" msg="$2" file="$3" content="$4" scratch
  scratch="$(mktemp -d "${TMPDIR:-/tmp}/c07-up-XXXXXX")"
  git clone --quiet "$bare" "$scratch"
  gcfg "$scratch"
  printf '%s\n' "$content" > "${scratch}/${file}"
  git -C "$scratch" add "$file"
  git -C "$scratch" commit --quiet -m "$msg"
  git -C "$scratch" push --quiet origin HEAD:master
  rm -rf -- "$scratch"
}

# copy_pipeline_scripts <fixture-root> — installs a full, self-contained copy
# of every script phase-07-closure.sh depends on (itself included) at the
# real repo's relative layout. See the file-header SAFETY note for why this
# is the load-bearing isolation mechanism of this whole suite.
copy_pipeline_scripts() {
  local root="$1"
  mkdir -p "${root}/scripts/pipeline/lib"
  cp "${SOURCE_REPO_ROOT}/scripts/pipeline/phase-07-closure.sh" "${root}/scripts/pipeline/phase-07-closure.sh"
  cp "${SOURCE_REPO_ROOT}/scripts/pipeline/lib/run-report.sh" "${root}/scripts/pipeline/lib/run-report.sh"
  cp "${SOURCE_REPO_ROOT}/scripts/pipeline/lib/evidence.sh" "${root}/scripts/pipeline/lib/evidence.sh"
  cp "${SOURCE_REPO_ROOT}/scripts/pipeline/lib/anti-bluff-validate.sh" "${root}/scripts/pipeline/lib/anti-bluff-validate.sh"
  cp "${SOURCE_REPO_ROOT}/scripts/advance-all-submodules.sh" "${root}/scripts/advance-all-submodules.sh"
  cp "${SOURCE_REPO_ROOT}/scripts/commit_all.sh" "${root}/scripts/commit_all.sh"
  chmod +x \
    "${root}/scripts/pipeline/phase-07-closure.sh" \
    "${root}/scripts/advance-all-submodules.sh" \
    "${root}/scripts/commit_all.sh"
  # LOAD-BEARING: the real repository's own .gitignore excludes
  # ".lava-ci-evidence/pipeline-runs/" entirely (confirmed:
  # `grep -n lava-ci-evidence .gitignore` at SOURCE_REPO_ROOT names this
  # exact path). Without the identical rule here, EVERY fixture's very first
  # `.lava-ci-evidence/pipeline-runs/<run_id>/...` file (created by
  # init_run_report_in, and by phase-07-closure.sh's own early
  # `mkdir -p "$RAW_DIR"; : > "$COMBINED_LOG"`) would show as genuinely
  # untracked content in a repo that has NEVER committed that directory
  # before — a state production never reaches (a real Lava checkout already
  # has `.lava-ci-evidence/` populated from prior runs, so only the specific
  # NEW files under it are ever untracked, never the whole directory as one
  # collapsed `git status --porcelain` line). Confirmed empirically while
  # developing this suite: without this .gitignore entry, Step 0's baseline
  # capture read the WHOLE ".lava-ci-evidence/" tree as one untracked
  # directory entry, Step 3 committed it wholesale (including this run's own
  # not-yet-finished evidence log), and Step 4's FR-017 check then correctly
  # — but confusingly, for a reason unrelated to whatever the fixture meant
  # to test — failed on that same log file being appended to again by Steps
  # 4/5 AFTER the commit. That is a fixture-fidelity bug in THIS test suite,
  # not a defect in phase-07-closure.sh: the gitignore rule below is what
  # makes every fixture's git-visible surface match the real repository's.
  printf '.lava-ci-evidence/pipeline-runs/\n' > "${root}/.gitignore"
}

# mk_mirror_remote <fixture-root> <remote-name> <bare-path>
# Creates a bare repo and adds it as a remote of <fixture-root> under the
# EXACT name commit_all.sh's own remote filter matches (only "github" and
# "gitlab" — see scripts/commit_all.sh's `case "${remote}" in github|gitlab)`).
mk_mirror_remote() {
  local root="$1" name="$2" bare="$3"
  git init --quiet --bare --initial-branch=master "$bare"
  git -C "$root" remote add "$name" "$bare"
}

# init_run_report_in <fixture-root> <run_id> — sources the FIXTURE's OWN copy
# of run-report.sh (never the real repo's) and calls init_run_report from
# inside the fixture root, matching that library's own "callers MUST run from
# the root of the repository under test" contract.
init_run_report_in() {
  local root="$1" run_id="$2" sha
  sha="$(git -C "$root" rev-parse HEAD)"
  (
    cd "$root" || exit 99
    # shellcheck source=/dev/null
    source "${root}/scripts/pipeline/lib/run-report.sh"
    init_run_report "$run_id" "$sha" >/dev/null
  )
}

# find_record <record-dir> <submodule_name> — same content-based lookup the
# advance-all-submodules hardening suite uses, so this file is not coupled to
# the sanitize scheme either script uses for filenames.
find_record() {
  local dir="$1" name="$2" f
  [[ -d "$dir" ]] || return 1
  while IFS= read -r f; do
    if [[ "$(jq -r '.submodule_name' "$f" 2>/dev/null)" == "$name" ]]; then
      echo "$f"; return 0
    fi
  done < <(find "$dir" -type f -name '*.json' 2>/dev/null)
  return 1
}

LAST_OUTPUT=""
RUN_EXIT=0

# run_phase <fixture-root> <run_id> [env assignments...] — invokes the
# FIXTURE's OWN COPY of phase-07-closure.sh, from within the fixture root,
# with NO [repo-path] override (so REPO_ROOT_OF_SCRIPT self-derives to the
# fixture root — see the file-header SAFETY note). Sets LAST_OUTPUT/RUN_EXIT
# in THIS shell (never via a `$(...)` call site — a subshell would discard
# them, exactly per the sibling suite's own documented reason).
run_phase() {
  local root="$1" run_id="$2"; shift 2
  local out_file
  out_file="$(mktemp "${TMPDIR:-/tmp}/c07-runout-XXXXXX")"
  RUN_EXIT=0
  (
    cd "$root" || exit 99
    env "$@" bash "scripts/pipeline/phase-07-closure.sh" "$run_id"
  ) > "$out_file" 2>&1 || RUN_EXIT=$?
  LAST_OUTPUT="$(cat "$out_file")"
  rm -f -- "$out_file"
  return 0
}

# run_phase_usage <cwd> [args...] — for the I5 usage-error cases, which must
# NOT be pointed at any fixture repo at all (they exit before ever resolving
# one). Invokes SOURCE_REPO_ROOT's own script directly, since a usage error
# attempts nothing on disk regardless of which copy answers it.
run_phase_usage() {
  local cwd="$1"; shift
  local out_file
  out_file="$(mktemp "${TMPDIR:-/tmp}/c07-usage-XXXXXX")"
  RUN_EXIT=0
  (
    cd "$cwd" || exit 99
    bash "${SOURCE_REPO_ROOT}/scripts/pipeline/phase-07-closure.sh" "$@"
  ) > "$out_file" 2>&1 || RUN_EXIT=$?
  LAST_OUTPUT="$(cat "$out_file")"
  rm -f -- "$out_file"
  return 0
}

# =========================================================================
# I1: a SURPRISE undeclared path causes REFUSAL, never a silent commit.
#
# Fixture: one governance-approved top-level submodule ("helixqa") one real
# upstream commit ahead of its pin, so it genuinely ADVANCES. Its
# LAVA_ADVANCE_VERIFY_CMD — the R-005 step-5 rebuild-and-test stub — is not a
# no-op "true": it writes an untracked file directly into the PARENT
# fixture's working tree ($2, per advance-all-submodules.sh's own
# documented VERIFY_CMD argument convention) before returning success. This
# stands in for the real failure mode this case exists to catch: a rebuild
# step that drops a stray build artifact, log file, or generated cache
# outside anything the pipeline's own earlier phases wrote — exactly the
# provenance phase-07-closure.sh's Step 2 (DECLARED PATH SET) exists to
# distinguish from "everything outstanding".
# =========================================================================
i1_root="$(mktemp -d "${TMPDIR:-/tmp}/c07-i1-XXXXXX")"; FIXTURE_DIRS+=("$i1_root")
mk_bare_from_scratch "${i1_root}/upstream_helixqa.git" "helixqa v1"
git init --quiet --initial-branch=master "${i1_root}/parent"
gcfg "${i1_root}/parent"
copy_pipeline_scripts "${i1_root}/parent"
git -C "${i1_root}/parent" add -A
git -C "${i1_root}/parent" commit --quiet -m "parent init + pipeline scripts"
git -c protocol.file.allow=always -C "${i1_root}/parent" \
  submodule add --quiet "${i1_root}/upstream_helixqa.git" submodules/helixqa
git -C "${i1_root}/parent" add -A
git -C "${i1_root}/parent" commit --quiet -m "pin helixqa"
# `git submodule add` checks out the remote's default-branch TIP at the
# moment it is run -- pushing v2 BEFORE this point would make the pin land
# on v2 directly, leaving nothing to advance to. v2 is pushed AFTER pinning
# so the pin genuinely trails the remote.
up_commit "${i1_root}/upstream_helixqa.git" "helixqa v2" "v2.txt" "helixqa v2"
mk_mirror_remote "${i1_root}/parent" github "${i1_root}/mirror-github.git"
mk_mirror_remote "${i1_root}/parent" gitlab "${i1_root}/mirror-gitlab.git"
git -C "${i1_root}/parent" push --quiet github master
git -C "${i1_root}/parent" push --quiet gitlab master

init_run_report_in "${i1_root}/parent" "2026-01-01T00-00-00Z"
i1_head_before="$(git -C "${i1_root}/parent" rev-parse HEAD)"
i1_verify_cmd='printf "stray build artifact\n" > "$2/UNEXPECTED_STRAY_FILE.txt"'
run_phase "${i1_root}/parent" "2026-01-01T00-00-00Z" \
  "LAVA_ADVANCE_VERIFY_CMD=${i1_verify_cmd}" \
  "LAVA_CLOSURE_ALLOW_LOCAL_PATH_REMOTES=true"
i1_exit="$RUN_EXIT"

expect_eq "I1(surprise-path-refused)" "exit code" "1" "$i1_exit"
expect_eq "I1(surprise-path-refused)" "parent HEAD unchanged (nothing committed)" "$i1_head_before" "$(git -C "${i1_root}/parent" rev-parse HEAD)"
expect_eq "I1(surprise-path-refused)" "the stray file is still present, uncommitted" "present" \
  "$([[ -e "${i1_root}/parent/UNEXPECTED_STRAY_FILE.txt" ]] && echo present || echo absent)"
expect_eq "I1(surprise-path-refused)" "the stray file is still untracked (?? in status)" "true" \
  "$(git -C "${i1_root}/parent" status --porcelain -- UNEXPECTED_STRAY_FILE.txt | grep -q '^?? UNEXPECTED_STRAY_FILE.txt$' && echo true || echo false)"
expect_contains "I1(surprise-path-refused)" "run output" "UNEXPECTED_STRAY_FILE.txt" "$LAST_OUTPUT"
expect_contains "I1(surprise-path-refused)" "run output names the refusal reason" "Refusing to commit them" "$LAST_OUTPUT"
expect_eq "I1(surprise-path-refused)" "neither mirror received the advance (nothing was ever pushed)" "true" \
  "$([[ "$(git -C "${i1_root}/mirror-github.git" rev-parse master)" == "$(git -C "${i1_root}/parent" rev-parse master)" ]] && echo true || echo false)"

# I1-positive: the SAME fixture shape with a no-op verify command (no stray
# file) must still advance + commit + push cleanly — otherwise a fix that
# refused everything unconditionally would pass the negative case above too.
i1b_root="$(mktemp -d "${TMPDIR:-/tmp}/c07-i1b-XXXXXX")"; FIXTURE_DIRS+=("$i1b_root")
mk_bare_from_scratch "${i1b_root}/upstream_helixqa.git" "helixqa v1"
git init --quiet --initial-branch=master "${i1b_root}/parent"
gcfg "${i1b_root}/parent"
copy_pipeline_scripts "${i1b_root}/parent"
git -C "${i1b_root}/parent" add -A
git -C "${i1b_root}/parent" commit --quiet -m "parent init + pipeline scripts"
git -c protocol.file.allow=always -C "${i1b_root}/parent" \
  submodule add --quiet "${i1b_root}/upstream_helixqa.git" submodules/helixqa
up_commit "${i1b_root}/upstream_helixqa.git" "helixqa v2" "v2.txt" "helixqa v2"
git -C "${i1b_root}/parent" add -A
git -C "${i1b_root}/parent" commit --quiet -m "pin helixqa"
mk_mirror_remote "${i1b_root}/parent" github "${i1b_root}/mirror-github.git"
mk_mirror_remote "${i1b_root}/parent" gitlab "${i1b_root}/mirror-gitlab.git"
git -C "${i1b_root}/parent" push --quiet github master
git -C "${i1b_root}/parent" push --quiet gitlab master
init_run_report_in "${i1b_root}/parent" "2026-01-01T00-00-00Z"
run_phase "${i1b_root}/parent" "2026-01-01T00-00-00Z" \
  "LAVA_ADVANCE_VERIFY_CMD=true" \
  "LAVA_CLOSURE_ALLOW_LOCAL_PATH_REMOTES=true"
expect_eq "I1b(clean-advance-still-commits-and-pushes)" "exit code" "0" "$RUN_EXIT"
expect_eq "I1b(clean-advance-still-commits-and-pushes)" "working tree ends clean" "" \
  "$(git -C "${i1b_root}/parent" status --porcelain --ignore-submodules=none)"
expect_eq "I1b(clean-advance-still-commits-and-pushes)" "github mirror received the commit" "$(git -C "${i1b_root}/parent" rev-parse master)" \
  "$(git -C "${i1b_root}/mirror-github.git" rev-parse master)"
expect_eq "I1b(clean-advance-still-commits-and-pushes)" "gitlab mirror received the commit" "$(git -C "${i1b_root}/parent" rev-parse master)" \
  "$(git -C "${i1b_root}/mirror-gitlab.git" rev-parse master)"

# =========================================================================
# I2: scripts/commit_all.sh's OWN push failure must propagate as a phase
# FAILURE, not a silently-swallowed non-zero somewhere.
#
# No submodule is needed for this case: the "declared path" that needs
# committing is ordinary BASELINE output an earlier pipeline phase already
# wrote before phase-07-closure.sh ran (a tracked file, modified). The
# "gitlab" mirror is made to diverge from local master BEFORE the run, so
# commit_all.sh's push to it is a genuine non-fast-forward rejection at the
# real git-protocol level (a receiving bare repo refusing the ref update),
# not a stubbed failure.
# =========================================================================
i2_root="$(mktemp -d "${TMPDIR:-/tmp}/c07-i2-XXXXXX")"; FIXTURE_DIRS+=("$i2_root")
git init --quiet --initial-branch=master "${i2_root}/parent"
gcfg "${i2_root}/parent"
copy_pipeline_scripts "${i2_root}/parent"
echo "line 1" > "${i2_root}/parent/OUTPUT.md"
git -C "${i2_root}/parent" add -A
git -C "${i2_root}/parent" commit --quiet -m "parent init + pipeline scripts"
mk_mirror_remote "${i2_root}/parent" github "${i2_root}/mirror-github.git"
mk_mirror_remote "${i2_root}/parent" gitlab "${i2_root}/mirror-gitlab.git"
git -C "${i2_root}/parent" push --quiet github master
git -C "${i2_root}/parent" push --quiet gitlab master
# diverge gitlab's master directly, bypassing the local clone entirely
i2_scratch="$(mktemp -d "${TMPDIR:-/tmp}/c07-i2-scratch-XXXXXX")"
git clone --quiet "${i2_root}/mirror-gitlab.git" "$i2_scratch"
gcfg "$i2_scratch"
echo "gitlab-only divergent commit" > "${i2_scratch}/DIVERGED.md"
git -C "$i2_scratch" add -A
git -C "$i2_scratch" commit --quiet -m "gitlab-only divergence"
git -C "$i2_scratch" push --quiet origin HEAD:master
rm -rf -- "$i2_scratch"

# simulate an earlier pipeline phase's output: OUTPUT.md modified, dirty
# BEFORE this phase even starts (this is the declared-path baseline).
echo "line 2 (written by an earlier phase)" >> "${i2_root}/parent/OUTPUT.md"

i2_local_master_before="$(git -C "${i2_root}/parent" rev-parse master)"
i2_gitlab_before="$(git -C "${i2_root}/parent" ls-remote gitlab master | cut -f1)"
init_run_report_in "${i2_root}/parent" "2026-01-01T00-00-00Z"
run_phase "${i2_root}/parent" "2026-01-01T00-00-00Z"
i2_exit="$RUN_EXIT"

expect_eq "I2(commit_all-push-failure-propagates)" "exit code" "1" "$i2_exit"
expect_contains "I2(commit_all-push-failure-propagates)" "run output names commit_all.sh's exit code" "commit_all.sh exited 4" "$LAST_OUTPUT"
expect_contains "I2(commit_all-push-failure-propagates)" "run output's SUMMARY reports FAIL, not PASS" "phase result:             FAIL" "$LAST_OUTPUT"
if grep -q "^phase-07-closure: SUMMARY" <<<"$LAST_OUTPUT" && grep -q "phase result:             PASS" <<<"$LAST_OUTPUT"; then
  echo "FAIL: I2(commit_all-push-failure-propagates): the SUMMARY falsely reports PASS"
  FAILURES=$((FAILURES + 1))
fi
expect_ne "I2(commit_all-push-failure-propagates)" "github DID receive the local commit (the mirror that accepted the push)" \
  "$i2_local_master_before" "$(git -C "${i2_root}/mirror-github.git" rev-parse master)"
expect_eq "I2(commit_all-push-failure-propagates)" "gitlab did NOT receive anything (its divergent tip is untouched)" \
  "$i2_gitlab_before" "$(git -C "${i2_root}/mirror-gitlab.git" rev-parse master)"

# =========================================================================
# I3: the FR-017 post-closure verification is a REAL, load-bearing check —
# not merely a decorative echo. Every earlier step in this run genuinely
# finds nothing wrong (no _fail anywhere, nothing to commit even); ONLY
# step 4's own two direct assertions (`git status --porcelain
# --ignore-submodules=none` empty; `git submodule status --recursive |
# grep -c '^[+-]'` == 0) can catch the residual problem this fixture leaves
# behind, and this case proves both that they DO, and that they are the
# reason the run fails (falsifiability rehearsal below).
#
# Mechanism (measured empirically before writing this fixture, not assumed):
# a nested submodule declared, pinned and committed inside an ALREADY-
# initialized submodule's own history, but never itself `submodule update
# --init`'d, shows as a genuinely dirty entry in
# `git submodule status --recursive` (a leading '-') while
# `git status --porcelain --ignore-submodules=none` at the PARENT stays
# completely empty — git's own top-level status does not recurse two levels
# to notice an uninitialized grand-child submodule. The name "constitution"
# is used deliberately for the nested submodule in this fixture: per
# phase-07-closure.sh's own documented design (decision (1) in its header),
# ANY submodule literally named "constitution", at ANY depth, is excluded
# from the walk entirely — root CLAUDE.md condition (B)'s default-deny on the
# real governance submodule is enforced by a BASENAME match, not a path
# match, so a same-named submodule anywhere else in the recursive tree is
# excluded by the identical rule and is therefore NEVER initialized by this
# script, by design. If it starts uninitialized (as any freshly-cloned
# nested submodule does), it stays that way forever, and steps 1-3 of this
# phase genuinely have nothing to say about it (no _fail is ever reached).
# This is disclosed here as a real, load-bearing consequence of a
# deliberate, correct design choice — not a bug — precisely BECAUSE step 4
# is what closes the gap it would otherwise leave open.
# =========================================================================
i3_root="$(mktemp -d "${TMPDIR:-/tmp}/c07-i3-XXXXXX")"; FIXTURE_DIRS+=("$i3_root")
mk_bare_from_scratch "${i3_root}/upstream_nested_constitution.git" "nested constitution seed"
# Build helixqa's own upstream WITH a nested submodule literally named
# "constitution" already declared in its single commit.
git init --quiet --initial-branch=master "${i3_root}/seed_helixqa"
gcfg "${i3_root}/seed_helixqa"
echo helixqa > "${i3_root}/seed_helixqa/f.txt"
git -C "${i3_root}/seed_helixqa" add -A
git -C "${i3_root}/seed_helixqa" commit --quiet -m "helixqa seed"
git -c protocol.file.allow=always -C "${i3_root}/seed_helixqa" \
  submodule add --quiet "${i3_root}/upstream_nested_constitution.git" constitution
git -C "${i3_root}/seed_helixqa" add -A
git -C "${i3_root}/seed_helixqa" commit --quiet -m "helixqa: declares a nested 'constitution' submodule"
git init --quiet --bare --initial-branch=master "${i3_root}/upstream_helixqa.git"
git -C "${i3_root}/seed_helixqa" remote add origin "${i3_root}/upstream_helixqa.git"
git -C "${i3_root}/seed_helixqa" push --quiet origin HEAD:master
rm -rf -- "${i3_root}/seed_helixqa"

git init --quiet --initial-branch=master "${i3_root}/parent"
gcfg "${i3_root}/parent"
copy_pipeline_scripts "${i3_root}/parent"
git -C "${i3_root}/parent" add -A
git -C "${i3_root}/parent" commit --quiet -m "parent init + pipeline scripts"
# `submodule add` (without --recursive) pins helixqa at its own HEAD but does
# NOT initialize helixqa's OWN nested submodules -- exactly the starting
# state a plain `git clone` (without --recurse-submodules) would leave.
git -c protocol.file.allow=always -C "${i3_root}/parent" \
  submodule add --quiet "${i3_root}/upstream_helixqa.git" submodules/helixqa
git -C "${i3_root}/parent" add -A
git -C "${i3_root}/parent" commit --quiet -m "pin helixqa (nested constitution left uninitialized)"
mk_mirror_remote "${i3_root}/parent" github "${i3_root}/mirror-github.git"
mk_mirror_remote "${i3_root}/parent" gitlab "${i3_root}/mirror-gitlab.git"
git -C "${i3_root}/parent" push --quiet github master
git -C "${i3_root}/parent" push --quiet gitlab master

# Measured precondition, asserted rather than assumed: the nested submodule
# really does start dirty-by-recursive-status and clean-by-plain-status.
i3_pre_recursive_dirty="$(git -C "${i3_root}/parent" submodule status --recursive 2>/dev/null | grep -c '^[+-]' || true)"
i3_pre_status="$(git -C "${i3_root}/parent" status --porcelain --ignore-submodules=none)"
expect_eq "I3(precondition)" "recursive submodule status shows the nested 'constitution' as dirty BEFORE the run" "1" "${i3_pre_recursive_dirty:-0}"
expect_eq "I3(precondition)" "plain status is already empty BEFORE the run (git does not surface this on its own)" "" "$i3_pre_status"

init_run_report_in "${i3_root}/parent" "2026-01-01T00-00-00Z"
i3_head_before="$(git -C "${i3_root}/parent" rev-parse HEAD)"
run_phase "${i3_root}/parent" "2026-01-01T00-00-00Z" "LAVA_ADVANCE_VERIFY_CMD=true" "LAVA_CLOSURE_ALLOW_LOCAL_PATH_REMOTES=true"
i3_exit="$RUN_EXIT"

expect_eq "I3(fr017-catches-real-leftover)" "exit code" "1" "$i3_exit"
expect_contains "I3(fr017-catches-real-leftover)" "run output cites FR-017 by name" "FR-017 violated" "$LAST_OUTPUT"
expect_contains "I3(fr017-catches-real-leftover)" "run output cites the uninitialized/diverged submodule count" "uninitialized or diverged" "$LAST_OUTPUT"
expect_eq "I3(fr017-catches-real-leftover)" "no commit was made (nothing to commit anyway, but confirm HEAD really didn't move)" \
  "$i3_head_before" "$(git -C "${i3_root}/parent" rev-parse HEAD)"
# The other steps genuinely found nothing wrong: confirm the failure this run
# reports names ONLY the FR-017 violation, not some other exclusion-related
# init failure. (If step 1's constitution-exclusion, or a governance refusal
# for the nested submodule, were ALSO surfacing as a distinct FAILED reason,
# this would not be the clean "only step 4 caught it" case this test claims.)
# NOTE: "^phase-07-closure: FAILED —" itself is NOT the right thing to count
# here — the script legitimately prints that exact header line TWICE per
# failing run BY DESIGN (once inline from _fail() at the point of failure,
# once more in the final re-statement block at the very end), even when
# there is only ONE underlying reason. The absence of any OTHER known
# failure-class phrase is what actually proves this.
for _other_reason_phrase in \
  "advance-all-submodules.sh reported" \
  "advance-all-submodules.sh refused outright" \
  "recursion exceeded" \
  "could not initialize" \
  "are dirty but were neither written" \
  "scripts/commit_all.sh exited" \
  "working tree is not empty after closure"; do
  if grep -qF "$_other_reason_phrase" <<<"$LAST_OUTPUT"; then
    echo "FAIL: I3(fr017-catches-real-leftover): an UNRELATED failure phrase ('${_other_reason_phrase}') also appears — this is not the clean 'only step 4 caught it' case this test claims"
    FAILURES=$((FAILURES + 1))
  fi
done
echo "PASS: I3(fr017-catches-real-leftover): no unrelated failure phrase appears alongside the FR-017 violation"

# --- Falsifiability rehearsal (recorded in this suite's final report, not
# only here): temporarily neuter the FR-017 dirty_recursive guard in the
# FIXTURE's OWN COPY of phase-07-closure.sh (never SOURCE_REPO_ROOT's script)
# and confirm this exact same fixture, re-run identically, now WRONGLY
# reports PASS despite the leftover uninitialized submodule being still
# there, unchanged. This proves the guard removed is the one this test
# depends on, not some unrelated check. ---
i3_copy="${i3_root}/parent/scripts/pipeline/phase-07-closure.sh"
i3_copy_backup="$(mktemp "${TMPDIR:-/tmp}/c07-i3-orig-XXXXXX")"
cp "$i3_copy" "$i3_copy_backup"
# A literal Python string replace, deliberately NOT sed: the guard line
# contains `[[`, `]]` and `$`, every one a live regex metacharacter, so an
# unescaped `sed 's/<line>/.../'` with the line interpolated verbatim
# SILENTLY NO-OPS (confirmed empirically while developing this rehearsal —
# `grep -qF` below still finds the line because it does a literal-string
# search, giving no signal that the sed itself never touched the file). A
# literal, non-regex, exactly-once-asserted replace is the only form that
# can't quietly do nothing.
if ! python3 - "$i3_copy" <<'PYEOF'
import sys
path = sys.argv[1]
old = '  if [[ "$dirty_recursive" -gt 0 ]]; then'
new = '  if false; then  # MUTATED-BY-TEST: dirty_recursive guard disabled'
with open(path) as f:
    content = f.read()
count = content.count(old)
if count != 1:
    print(f"expected exactly 1 occurrence of the guard line, found {count}", file=sys.stderr)
    sys.exit(1)
with open(path, "w") as f:
    f.write(content.replace(old, new))
PYEOF
then
  echo "FAIL: I3(falsifiability-rehearsal): could not locate the dirty_recursive guard line to mutate — has phase-07-closure.sh's Step 4 been restructured?"
  FAILURES=$((FAILURES + 1))
else
  run_phase "${i3_root}/parent" "2026-01-01T00-00-00Z" "LAVA_ADVANCE_VERIFY_CMD=true" "LAVA_CLOSURE_ALLOW_LOCAL_PATH_REMOTES=true"
  i3_mutated_exit="$RUN_EXIT"
  cp "$i3_copy_backup" "$i3_copy"
  rm -f -- "$i3_copy_backup"
  expect_eq "I3(falsifiability-rehearsal)" "with the FR-017 guard disabled, the SAME unfixed fixture wrongly reports success" "0" "$i3_mutated_exit"
  expect_true "I3(falsifiability-rehearsal)" "the leftover uninitialized submodule is STILL there, unchanged, proving nothing else caught it" \
    "$([[ "$(git -C "${i3_root}/parent" submodule status --recursive 2>/dev/null | grep -c '^[+-]' || true)" -gt 0 ]] && echo true || echo false)"
fi

# =========================================================================
# I4: report.json's submodule_advances[] merge is CORRECT and COMPLETE
# across every level's own record directory, not just the top-level one.
#
# Fixture: two top-level submodules --
#   vendor/helixqa  (basename "helixqa", governance-approved, one real
#                    upstream commit ahead)                -> ADVANCED,
#                    recorded under the TOP-level record dir
#   libs/misc       (basename "misc", governance-DENIED)   -> REFUSED_
#                    GOVERNANCE_DENY, ALSO recorded under the TOP-level
#                    record dir
# libs/misc itself declares its OWN nested submodule, ALSO named "helixqa"
# (path "libs/misc/helixqa"), already at its own upstream's latest --
#                                                            -> NO_NEWER_COMMIT,
#                    recorded under libs/misc's OWN (non-top-level) record
#                    dir. libs/misc is reached and recursed into despite
#                    being itself governance-denied, per this phase's own
#                    documented design: recursion depends on being
#                    initialized + declaring nested submodules, never on the
#                    level's own governance status.
# =========================================================================
i4_root="$(mktemp -d "${TMPDIR:-/tmp}/c07-i4-XXXXXX")"; FIXTURE_DIRS+=("$i4_root")
mk_bare_from_scratch "${i4_root}/upstream_vendor_helixqa.git" "vendor helixqa v1"
mk_bare_from_scratch "${i4_root}/upstream_nested_helixqa.git" "nested helixqa (already latest)"

git init --quiet --initial-branch=master "${i4_root}/seed_misc"
gcfg "${i4_root}/seed_misc"
echo misc > "${i4_root}/seed_misc/f.txt"
git -C "${i4_root}/seed_misc" add -A
git -C "${i4_root}/seed_misc" commit --quiet -m "misc seed"
git -c protocol.file.allow=always -C "${i4_root}/seed_misc" \
  submodule add --quiet "${i4_root}/upstream_nested_helixqa.git" helixqa
git -C "${i4_root}/seed_misc" add -A
git -C "${i4_root}/seed_misc" commit --quiet -m "misc: declares its own nested 'helixqa' submodule"
git init --quiet --bare --initial-branch=master "${i4_root}/upstream_misc.git"
git -C "${i4_root}/seed_misc" remote add origin "${i4_root}/upstream_misc.git"
git -C "${i4_root}/seed_misc" push --quiet origin HEAD:master
rm -rf -- "${i4_root}/seed_misc"

git init --quiet --initial-branch=master "${i4_root}/parent"
gcfg "${i4_root}/parent"
copy_pipeline_scripts "${i4_root}/parent"
git -C "${i4_root}/parent" add -A
git -C "${i4_root}/parent" commit --quiet -m "parent init + pipeline scripts"
git -c protocol.file.allow=always -C "${i4_root}/parent" \
  submodule add --quiet "${i4_root}/upstream_vendor_helixqa.git" vendor/helixqa
git -c protocol.file.allow=always -C "${i4_root}/parent" \
  submodule add --quiet "${i4_root}/upstream_misc.git" libs/misc
git -C "${i4_root}/parent" add -A
git -C "${i4_root}/parent" commit --quiet -m "pin vendor/helixqa + libs/misc"
# Pushed AFTER pinning so vendor/helixqa's pin genuinely trails its remote
# (see I1's identical note — `git submodule add` checks out the remote's
# CURRENT tip, so pushing a newer commit any earlier would be pinned
# directly, leaving nothing to advance to).
up_commit "${i4_root}/upstream_vendor_helixqa.git" "vendor helixqa v2" "v2.txt" "v2"
# libs/misc's OWN nested 'helixqa' must be initialized for this phase's
# recursion to be reachable to examine it at all -- a plain `submodule add`
# of libs/misc does not recurse into it (matching I3's measured precondition
# for exactly the same git behavior). Initialize it explicitly here so this
# case is testing the REPORT MERGE, not repeating I3's uninitialized-nested
# scenario. MUST be run with `-C` pointed at libs/misc itself (a path two
# levels down from the parent, e.g. `libs/misc/helixqa`, is not a pathspec
# `git submodule update --init` from the PARENT resolves — confirmed
# empirically: it fails with "pathspec ... did not match any file(s) known
# to git" — because status/pathspec resolution for `submodule update` is
# scoped to the invoked repository's OWN immediate submodules only, exactly
# like `git submodule status` itself is one level at a time everywhere else
# in this suite and in phase-07-closure.sh's own recursive design).
# Initializing a nested submodule's working tree is local checkout state,
# not a pin change, so there is nothing new for the PARENT to commit
# afterward.
git -c protocol.file.allow=always -C "${i4_root}/parent/libs/misc" \
  submodule update --init --quiet -- helixqa
mk_mirror_remote "${i4_root}/parent" github "${i4_root}/mirror-github.git"
mk_mirror_remote "${i4_root}/parent" gitlab "${i4_root}/mirror-gitlab.git"
git -C "${i4_root}/parent" push --quiet github master
git -C "${i4_root}/parent" push --quiet gitlab master

init_run_report_in "${i4_root}/parent" "2026-01-01T00-00-00Z"
run_phase "${i4_root}/parent" "2026-01-01T00-00-00Z" \
  "LAVA_ADVANCE_VERIFY_CMD=true" \
  "LAVA_CLOSURE_ALLOW_LOCAL_PATH_REMOTES=true"
i4_exit="$RUN_EXIT"
expect_eq "I4(precondition)" "exit code (this fixture must genuinely succeed for the merge to be checked meaningfully)" "0" "$i4_exit"

i4_report="${i4_root}/parent/.lava-ci-evidence/pipeline-runs/2026-01-01T00-00-00Z/report.json"
if [[ ! -f "$i4_report" ]]; then
  echo "FAIL: I4(report-merge): report.json does not exist at '${i4_report}'"
  FAILURES=$((FAILURES + 3))
else
  i4_vendor_entry="$(jq -c '.submodule_advances[] | select(.submodule_name == "vendor/helixqa")' "$i4_report")"
  i4_misc_entry="$(jq -c '.submodule_advances[] | select(.submodule_name == "libs/misc")' "$i4_report")"
  i4_nested_entry="$(jq -c '.submodule_advances[] | select(.submodule_name == "helixqa")' "$i4_report")"

  expect_ne "I4(report-merge)" "vendor/helixqa entry exists" "" "$i4_vendor_entry"
  expect_ne "I4(report-merge)" "libs/misc entry exists" "" "$i4_misc_entry"
  expect_ne "I4(report-merge)" "the NESTED level's own helixqa entry exists" "" "$i4_nested_entry"

  [[ -n "$i4_vendor_entry" ]] && expect_eq "I4(report-merge)" "vendor/helixqa outcome" "ADVANCED" "$(jq -r '.outcome' <<<"$i4_vendor_entry")"
  [[ -n "$i4_misc_entry" ]] && expect_eq "I4(report-merge)" "libs/misc outcome" "REFUSED_GOVERNANCE_DENY" "$(jq -r '.outcome' <<<"$i4_misc_entry")"
  [[ -n "$i4_nested_entry" ]] && expect_eq "I4(report-merge)" "nested-level helixqa outcome" "NO_NEWER_COMMIT" "$(jq -r '.outcome' <<<"$i4_nested_entry")"

  # Prove at least 2 distinct per-level record directories exist on disk
  # (i.e. the nested "helixqa" entry really came from a DIFFERENT level's
  # record directory than the top-level one, not a coincidental duplicate).
  i4_top_dir="${i4_root}/parent/.lava-ci-evidence/pipeline-runs/2026-01-01T00-00-00Z/submodule-advances"
  i4_level_dirs="$(find "${i4_top_dir}" -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' ')"
  expect_eq "I4(report-merge)" "at least 2 distinct per-level record directories exist on disk" "true" \
    "$([[ "${i4_level_dirs:-0}" -ge 2 ]] && echo true || echo false)"
  i4_sanitized_top="$(basename "${i4_root}/parent" | tr -c 'A-Za-z0-9._-' '_')"
  i4_nested_rec_in_top_dir="$(find_record "${i4_top_dir}/${i4_sanitized_top}" "helixqa" 2>/dev/null || true)"
  expect_eq "I4(report-merge)" "the top-level record dir alone never recorded a bare 'helixqa' entry (it only ever saw vendor/helixqa and libs/misc)" "" "$i4_nested_rec_in_top_dir"

  i4_advances_count_first="$(jq '.submodule_advances | length' "$i4_report")"

  # --- idempotency: re-run the SAME closure a second time on the same
  # already-clean, already-advanced fixture (everything now resolves
  # NO_NEWER_COMMIT / REFUSED_GOVERNANCE_DENY -- nothing left to advance) and
  # confirm submodule_advances[] is neither duplicated nor stale-appended.
  # The merge step replaces the array wholesale from a fresh glob every call
  # (see phase-07-closure.sh Step 5's `report["submodule_advances"] =
  # records`), so this also verifies that replacement is genuinely a full
  # re-glob and not an accidental accumulation across runs sharing one run_id.
  run_phase "${i4_root}/parent" "2026-01-01T00-00-00Z" \
    "LAVA_ADVANCE_VERIFY_CMD=true" \
    "LAVA_CLOSURE_ALLOW_LOCAL_PATH_REMOTES=true"
  i4_second_exit="$RUN_EXIT"
  expect_eq "I4(idempotent-second-run)" "exit code" "0" "$i4_second_exit"
  i4_advances_count_second="$(jq '.submodule_advances | length' "$i4_report")"
  expect_eq "I4(idempotent-second-run)" "submodule_advances[] length unchanged (no duplication) across the two runs" \
    "$i4_advances_count_first" "$i4_advances_count_second"
  i4_dup_names="$(jq -r '.submodule_advances | group_by(.submodule_name) | map(select(length > 1)) | length' "$i4_report")"
  expect_eq "I4(idempotent-second-run)" "no submodule_name appears more than once in submodule_advances[]" "0" "$i4_dup_names"
fi

# =========================================================================
# I5: --help and the two usage/precondition-error exits attempt NOTHING on
# disk.
# =========================================================================
i5_help_cwd="$(mktemp -d "${TMPDIR:-/tmp}/c07-i5-help-XXXXXX")"; FIXTURE_DIRS+=("$i5_help_cwd")
run_phase_usage "$i5_help_cwd" --help
expect_eq "I5(--help)" "exit code" "0" "$RUN_EXIT"
expect_contains "I5(--help)" "help output" "phase-07-closure.sh" "$LAST_OUTPUT"
expect_eq "I5(--help)" "no .lava-ci-evidence directory was created" "absent" \
  "$([[ -e "${i5_help_cwd}/.lava-ci-evidence" ]] && echo present || echo absent)"

i5_missing_id_cwd="$(mktemp -d "${TMPDIR:-/tmp}/c07-i5-missing-id-XXXXXX")"; FIXTURE_DIRS+=("$i5_missing_id_cwd")
run_phase_usage "$i5_missing_id_cwd"
expect_eq "I5(missing-run_id)" "exit code" "2" "$RUN_EXIT"
expect_contains "I5(missing-run_id)" "run output" "usage:" "$LAST_OUTPUT"
expect_eq "I5(missing-run_id)" "no .lava-ci-evidence directory was created" "absent" \
  "$([[ -e "${i5_missing_id_cwd}/.lava-ci-evidence" ]] && echo present || echo absent)"

i5_no_report_cwd="$(mktemp -d "${TMPDIR:-/tmp}/c07-i5-no-report-XXXXXX")"; FIXTURE_DIRS+=("$i5_no_report_cwd")
run_phase_usage "$i5_no_report_cwd" "2099-01-01T00-00-00Z"
expect_eq "I5(no-existing-report-json)" "exit code" "2" "$RUN_EXIT"
expect_contains "I5(no-existing-report-json)" "run output" "does not exist" "$LAST_OUTPUT"
expect_contains "I5(no-existing-report-json)" "run output" "init_run_report" "$LAST_OUTPUT"
expect_eq "I5(no-existing-report-json)" "no phase-07 evidence directory was created" "absent" \
  "$([[ -e "${i5_no_report_cwd}/.lava-ci-evidence/pipeline-runs/2099-01-01T00-00-00Z/phase-07" ]] && echo present || echo absent)"

echo "---"
if [[ "$FAILURES" -eq 0 ]]; then
  echo "PASS: all phase-07-closure hardening cases passed"
  exit 0
else
  echo "FAIL: ${FAILURES} phase-07-closure hardening assertion(s) failed"
  exit 1
fi

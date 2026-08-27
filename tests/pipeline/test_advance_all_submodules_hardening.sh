#!/usr/bin/env bash
# Hermetic hardening suite for scripts/advance-all-submodules.sh.
#
# tests/pipeline/test_advance_all_submodules.sh covers the four R-005 happy /
# rejection branches plus the governance deny. THIS suite covers the failure
# modes found by the 2026-08-22 pre-T054 audit of the same script -- each case
# below was demonstrated against a disposable fixture BEFORE the fix, and each
# assertion here failed at that point.
#
# SAFETY: identical to its sibling. Every "upstream" is a bare repo inside a
# mktemp -d; nothing reaches a network host; nothing touches this repository's
# real submodules/ tree or constitution/; every fixture is removed on EXIT.
#
# Cases
#   H1  an UNINITIALIZED submodule directory must not make the script operate
#       on the PARENT repository (git's upward .git discovery)
#   H2  a FAILING `git submodule status` must not read as "no submodules --
#       nothing to do, exit 0"
#   H3  LAVA_ADVANCE_SUBMODULES must match submodule paths exactly, never as a
#       word-boundary regex (+ positive: the named submodule still advances)
#   H4  a pin move that is NOT a fast-forward must be refused (+ positive: a
#       genuine fast-forward still advances)
#   H5  RETARGETED 2026-08-26 -- an untracked file the NEW upstream commit
#       gitignores is still local work when it is MEASURED, so it is refused,
#       and the upstream really receives nothing
#   H6  RETARGETED 2026-08-26 -- two §6.W mirrors, the second of which would
#       reject any push: NEITHER receives anything, whether the submodule is
#       clean or unclean, because no push is ever issued
#   H7  RETARGETED 2026-08-26 -- a mirror whose default branch is named
#       differently gains no stray branch AND no commit on its own default,
#       whether the submodule is clean or unclean
#   H8  RETARGETED 2026-08-26 -- parent-pin staging failing is still
#       REJECTED_PARENT_STAGING_FAILED, and NO rescue ref is created, because
#       this script creates no commit for one to rescue
#
#   H5-H8 all used to exercise R-005 step 6 (committing and pushing a
#   submodule's own local work). Step 6 and --publish-local-modifications were
#   REMOVED on 2026-08-26 after five review rounds found twelve fixture-proven
#   ways for unaudited content to reach another repository's default branch
#   through them. Each case is RETARGETED rather than deleted: the fixture that
#   used to prove the publish behaved correctly now proves the publish cannot
#   happen at all.
#   H9  a record-write failure on the governance-deny path must be counted
#   H10 a failed restore-to-prior-pin must be surfaced in the run summary
#   H11 a KILLED verify command must report its actual exit status
#
# Exit 0 if every case passes; non-zero otherwise.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT_UNDER_TEST="${REPO_ROOT}/scripts/advance-all-submodules.sh"

if ! command -v jq >/dev/null 2>&1; then
  echo "FAIL: jq is required to run this test suite but was not found on PATH"
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

# expect_contains <label> <what> <needle> <haystack>
expect_contains() {
  local label="$1" what="$2" needle="$3" haystack="$4"
  if [[ "$haystack" == *"$needle"* ]]; then
    echo "PASS: ${label}: ${what} mentions '${needle}'"
  else
    echo "FAIL: ${label}: ${what} never mentions '${needle}'; got: ${haystack}"
    FAILURES=$((FAILURES + 1))
  fi
}

gcfg() {
  git -C "$1" config user.email "fixture@example.invalid"
  git -C "$1" config user.name "Fixture"
  git -C "$1" config commit.gpgsign false
}

# mk_fixture <root> <sub-path> <extra-upstream-commits>
#   <root>/upstream.git   bare repo on master -- the submodule's own upstream
#   <root>/parent         parent repo on master with the submodule pinned
mk_fixture() {
  local root="$1" sub="$2" extra="${3:-0}" i
  mkdir -p "$root"
  git init --quiet --initial-branch=master "${root}/seed"; gcfg "${root}/seed"
  echo seed > "${root}/seed/seed.txt"
  git -C "${root}/seed" add -A
  git -C "${root}/seed" commit --quiet -m "upstream initial commit"
  git clone --quiet --bare "${root}/seed" "${root}/upstream.git"
  rm -rf -- "${root}/seed"

  git init --quiet --initial-branch=master "${root}/parent"; gcfg "${root}/parent"
  echo parent > "${root}/parent/parent.txt"
  git -C "${root}/parent" add -A
  git -C "${root}/parent" commit --quiet -m "parent initial commit"
  # protocol.file.allow is required since git 2.38 (CVE-2022-39253) to add a
  # submodule over a local path. Scoped to this one invocation via -c.
  git -c protocol.file.allow=always -C "${root}/parent" \
    submodule add --quiet "${root}/upstream.git" "$sub" >/dev/null 2>&1
  gcfg "${root}/parent/${sub}"
  git -C "${root}/parent" add -A
  git -C "${root}/parent" commit --quiet -m "pin submodule"

  for (( i = 1; i <= extra; i++ )); do
    up_commit "${root}/upstream.git" "${root}/pusher-${i}" "upstream commit ${i}" "feature-${i}.txt" "upstream commit ${i}"
  done
}

# up_commit <bare> <scratch> <message> <filename> <content>
up_commit() {
  local bare="$1" scratch="$2" msg="$3" file="$4" content="$5"
  git clone --quiet "$bare" "$scratch"; gcfg "$scratch"
  printf '%s\n' "$content" > "${scratch}/${file}"
  git -C "$scratch" add "$file"
  git -C "$scratch" commit --quiet -m "$msg"
  git -C "$scratch" push --quiet origin HEAD:master
  rm -rf -- "$scratch"
}

parent_pin() { git -C "$1" ls-files -s -- "$2" | awk '{print $2}'; }

# find_record <record-dir> <submodule_name> -- located by CONTENT so the test
# is not coupled to the script's filename-sanitizing scheme.
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
# Both flags below are REQUIRED by every fixture in this file, and each says
# something true about the fixture rather than about the production default:
#
#   --allow-local-path-remotes      every "upstream" here is a bare repo under
#       mktemp -d, i.e. a filesystem path. Production refuses that shape
#       (a local path can be another real repository, or a network mount that
#       reaches another machine while naming no host); a hermetic fixture can
#       have no other shape, and a suite that cannot reach the fetch path
#       cannot prove the fetch path is safe.
#
# `--publish-local-modifications` was REMOVED on 2026-08-26 and is no longer
# passed by anything in this suite.
#
# It is a command-line flag rather than an environment variable precisely so
# that this suite's need for it cannot leak into an unattended pipeline run
# through an inherited environment.
ADV_FLAGS=(--allow-local-path-remotes)

# run_script <parent-repo> <record-dir> <verify-cmd> [env assignments...]
#
# Deliberately NOT invoked via command substitution: a $(...) call site runs
# the function in a SUBSHELL, so anything it assigns to LAST_OUTPUT is
# discarded and every message-content assertion silently sees an empty string
# (i.e. passes for the wrong reason, or fails with no diagnostic). The output
# goes through a temp file and both globals are set in THIS shell; callers read
# $RUN_EXIT and $LAST_OUTPUT afterwards.
run_script() {
  local parent="$1" record_dir="$2" verify_cmd="$3"; shift 3
  local out_file
  out_file="$(mktemp "${TMPDIR:-/tmp}/adv-runout-XXXXXX")"
  RUN_EXIT=0
  env "$@" \
    LAVA_ADVANCE_RECORD_DIR="$record_dir" \
    LAVA_ADVANCE_VERIFY_CMD="$verify_cmd" \
    "$SCRIPT_UNDER_TEST" "${ADV_FLAGS[@]}" "$parent" > "$out_file" 2>&1 || RUN_EXIT=$?
  LAST_OUTPUT="$(cat "$out_file")"
  rm -f -- "$out_file"
  return 0
}

# =========================================================================
# H1: an UNINITIALIZED submodule directory.
#
# `git -C <empty-subdir-of-a-repo> rev-parse --git-dir` SUCCEEDS: git walks UP
# and finds the PARENT repository's .git. A guard built on that call therefore
# does not fire, and every subsequent `git -C "$sub_dir" ...` in the script --
# fetch, ls-remote, `checkout --detach`, `add -A`, `commit`, `push` -- lands on
# the PARENT repository instead. Demonstrated 2026-08-22 against a fixture:
# the parent repo was detached from its branch and the run reported
# outcome=ADVANCED / parent_pin_updated=true / exit 0.
#
# The submodule directory is reduced to the post-`git clone` (non-recursive)
# state -- present but empty, no .git/modules entry -- without using any
# destructive git flag.
# =========================================================================
h1_root="$(mktemp -d "${TMPDIR:-/tmp}/adv-h1-XXXXXX")"; FIXTURE_DIRS+=("$h1_root")
mk_fixture "$h1_root" "subs/helixqa" 0
# give the PARENT its own upstream that is AHEAD, so a script that mistakes the
# parent for the submodule has something to "advance" it to
git clone --quiet --bare "${h1_root}/parent" "${h1_root}/parent-upstream.git"
git -C "${h1_root}/parent" remote add origin "${h1_root}/parent-upstream.git"
up_commit "${h1_root}/parent-upstream.git" "${h1_root}/ppush" \
  "parent upstream moved ahead" "PARENT_ONLY_FILE.txt" "danger"
rm -rf -- "${h1_root}/parent/subs/helixqa" "${h1_root}/parent/.git/modules"
mkdir -p "${h1_root}/parent/subs/helixqa"

h1_parent_head_before="$(git -C "${h1_root}/parent" rev-parse HEAD)"
h1_branch_before="$(git -C "${h1_root}/parent" symbolic-ref --short -q HEAD || echo DETACHED)"
run_script "${h1_root}/parent" "${h1_root}/records" "true"
h1_exit="$RUN_EXIT"

expect_eq "H1(uninitialized-submodule)" "exit code (must be a failure, not 0)" "1" "$h1_exit"
expect_eq "H1(uninitialized-submodule)" "PARENT repo HEAD untouched" "$h1_parent_head_before" "$(git -C "${h1_root}/parent" rev-parse HEAD)"
expect_eq "H1(uninitialized-submodule)" "PARENT repo still on its branch" "$h1_branch_before" "$(git -C "${h1_root}/parent" symbolic-ref --short -q HEAD || echo DETACHED)"
expect_eq "H1(uninitialized-submodule)" "the parent's own upstream file was NOT checked out" "absent" \
  "$([[ -e "${h1_root}/parent/PARENT_ONLY_FILE.txt" ]] && echo present || echo absent)"
if h1_rec="$(find_record "${h1_root}/records" "subs/helixqa")"; then
  expect_ne "H1(uninitialized-submodule)" "outcome must not claim an advance" "ADVANCED" "$(jq -r '.outcome' "$h1_rec")"
else
  echo "PASS: H1(uninitialized-submodule): no record claiming an advance was written"
fi
expect_contains "H1(uninitialized-submodule)" "run output" "uninitialized" "$LAST_OUTPUT"

# =========================================================================
# H2: `git submodule status` FAILS (corrupt .gitmodules, unreadable index,
# any git error). Its stderr is discarded, the parse yields zero rows, and
# "no submodules found -- nothing to do" + exit 0 is indistinguishable from a
# repository that genuinely has none. A no-match must read as "nothing was
# LEARNED", never as "nothing FAILED".
# =========================================================================
h2_root="$(mktemp -d "${TMPDIR:-/tmp}/adv-h2-XXXXXX")"; FIXTURE_DIRS+=("$h2_root")
mk_fixture "$h2_root" "subs/helixqa" 1
cat > "${h2_root}/parent/.gitmodules" <<'GMEOF'
[submodule "subs/helixqa"]
	path =
	url =
GMEOF
run_script "${h2_root}/parent" "${h2_root}/records" "true"
h2_exit="$RUN_EXIT"
expect_ne "H2(enumeration-failure)" "exit code (0 would mean 'nothing to do')" "0" "$h2_exit"
expect_contains "H2(enumeration-failure)" "run output" "could not enumerate submodules" "$LAST_OUTPUT"

# H2-positive: a repository that genuinely has NO submodules must still be a
# clean exit 0, so a blanket "always fail" fix would not pass.
h2b_root="$(mktemp -d "${TMPDIR:-/tmp}/adv-h2b-XXXXXX")"; FIXTURE_DIRS+=("$h2b_root")
git init --quiet --initial-branch=master "${h2b_root}/parent"; gcfg "${h2b_root}/parent"
echo x > "${h2b_root}/parent/x.txt"
git -C "${h2b_root}/parent" add -A
git -C "${h2b_root}/parent" commit --quiet -m "no submodules here"
run_script "${h2b_root}/parent" "${h2b_root}/records" "true"
h2b_exit="$RUN_EXIT"
expect_eq "H2b(genuinely-no-submodules)" "exit code" "0" "$h2b_exit"

# =========================================================================
# H3: LAVA_ADVANCE_SUBMODULES is an allow-list -- a control that NARROWS a
# run. Matching it with `grep -qw` makes it a word-boundary REGEX over the
# whole list, so naming "subs/helixqa-extra" also selects "subs/helixqa" ('-' is not a
# word character). A narrowing control must never widen.
# =========================================================================
h3_root="$(mktemp -d "${TMPDIR:-/tmp}/adv-h3-XXXXXX")"; FIXTURE_DIRS+=("$h3_root")
mk_fixture "$h3_root" "subs/helixqa" 1
h3_pin_before="$(parent_pin "${h3_root}/parent" "subs/helixqa")"
run_script "${h3_root}/parent" "${h3_root}/records" "true" \
  "LAVA_ADVANCE_SUBMODULES=subs/helixqa-extra"
h3_exit="$RUN_EXIT"
expect_eq "H3(allow-list-over-match)" "pin of the UNNAMED submodule is untouched" "$h3_pin_before" "$(parent_pin "${h3_root}/parent" "subs/helixqa")"
# Exit 2, not 0, since T054 SHOULD-FIX-2. `subs/helixqa-extra` names no
# submodule this repository has, so it selects NOTHING -- and a run that
# examined nothing used to report "0 advanced, 0 already current, 0
# rejected/failed" with zero records and exit 0, which is indistinguishable
# from a clean run over a healthy repository. The property H3 exists to
# protect (a narrowing control must never widen) is still asserted by the
# pin-untouched check above; what changed is that selecting nothing is now a
# configuration error rather than a silent success.
expect_eq "H3(allow-list-over-match)" "exit code (a token matching nothing is a config error, not a clean run)" "2" "$h3_exit"
expect_contains "H3(allow-list-over-match)" "run output" "no submodule for" "$LAST_OUTPUT"

# H3-positive: naming the submodule exactly MUST still advance it -- otherwise
# a fix that simply ignored the allow-list would pass the negative case.
h3b_root="$(mktemp -d "${TMPDIR:-/tmp}/adv-h3b-XXXXXX")"; FIXTURE_DIRS+=("$h3b_root")
mk_fixture "$h3b_root" "subs/helixqa" 1
h3b_pin_before="$(parent_pin "${h3b_root}/parent" "subs/helixqa")"
run_script "${h3b_root}/parent" "${h3b_root}/records" "true" \
  "LAVA_ADVANCE_SUBMODULES=subs/helixqa"
h3b_exit="$RUN_EXIT"
expect_eq "H3b(allow-list-exact-match)" "exit code" "0" "$h3b_exit"
expect_ne "H3b(allow-list-exact-match)" "pin of the NAMED submodule advanced" "$h3b_pin_before" "$(parent_pin "${h3b_root}/parent" "subs/helixqa")"

# =========================================================================
# H4: the remote's default-branch HEAD differing from the pin does NOT imply
# it is NEWER. Lava pins sit on side branches by design (root CLAUDE.md
# records Containers pinned on `lava-pin/2026-05-07-pkg-vm`). Moving such a
# pin to origin/HEAD DROPS the pinned work while recording "ADVANCED".
# =========================================================================
h4_root="$(mktemp -d "${TMPDIR:-/tmp}/adv-h4-XXXXXX")"; FIXTURE_DIRS+=("$h4_root")
mk_fixture "$h4_root" "subs/helixqa" 0
h4_sub="${h4_root}/parent/subs/helixqa"
git -C "$h4_sub" checkout --quiet -b lava-pin/local-work
printf 'critical pinned capability\n' > "${h4_sub}/pinned-feature.txt"
git -C "$h4_sub" add -A
git -C "$h4_sub" commit --quiet -m "the pinned work"
git -C "$h4_sub" push --quiet origin HEAD:refs/heads/lava-pin/local-work
git -C "$h4_sub" checkout --quiet --detach HEAD
git -C "${h4_root}/parent" add -A
git -C "${h4_root}/parent" commit --quiet -m "pin to the side-branch commit"
h4_pin_before="$(parent_pin "${h4_root}/parent" "subs/helixqa")"
h4_sub_head_before="$(git -C "$h4_sub" rev-parse HEAD)"
run_script "${h4_root}/parent" "${h4_root}/records" "true"
h4_exit="$RUN_EXIT"

expect_ne "H4(not-a-fast-forward)" "exit code (0 would certify a pin rewind)" "0" "$h4_exit"
expect_eq "H4(not-a-fast-forward)" "parent pin untouched" "$h4_pin_before" "$(parent_pin "${h4_root}/parent" "subs/helixqa")"
expect_eq "H4(not-a-fast-forward)" "submodule HEAD untouched" "$h4_sub_head_before" "$(git -C "$h4_sub" rev-parse HEAD)"
expect_eq "H4(not-a-fast-forward)" "the pinned work is still checked out" "present" \
  "$([[ -e "${h4_sub}/pinned-feature.txt" ]] && echo present || echo absent)"
expect_contains "H4(not-a-fast-forward)" "run output" "not a fast-forward" "$LAST_OUTPUT"
if h4_rec="$(find_record "${h4_root}/records" "subs/helixqa")"; then
  expect_ne "H4(not-a-fast-forward)" "outcome must not claim an advance" "ADVANCED" "$(jq -r '.outcome' "$h4_rec")"
else
  echo "PASS: H4(not-a-fast-forward): no record claiming an advance was written"
fi

# H4-positive: a genuine fast-forward MUST still advance, so a fix that
# refused every move would not pass.
h4b_root="$(mktemp -d "${TMPDIR:-/tmp}/adv-h4b-XXXXXX")"; FIXTURE_DIRS+=("$h4b_root")
mk_fixture "$h4b_root" "subs/helixqa" 1
h4b_pin_before="$(parent_pin "${h4b_root}/parent" "subs/helixqa")"
run_script "${h4b_root}/parent" "${h4b_root}/records" "true"
h4b_exit="$RUN_EXIT"
expect_eq "H4b(genuine-fast-forward)" "exit code" "0" "$h4b_exit"
expect_ne "H4b(genuine-fast-forward)" "parent pin advanced" "$h4b_pin_before" "$(parent_pin "${h4b_root}/parent" "subs/helixqa")"
if h4b_rec="$(find_record "${h4b_root}/records" "subs/helixqa")"; then
  expect_eq "H4b(genuine-fast-forward)" "outcome" "ADVANCED" "$(jq -r '.outcome' "$h4b_rec")"
fi

# =========================================================================
# H5 (RETARGETED). `git status --porcelain -uall` runs BEFORE the step-4
# checkout, so the operator's untracked scratch file is local work at the
# moment it is measured, even though the NEW upstream commit adds a .gitignore
# rule covering it.
#
# It used to test that step 6 then created no commit and correctly reported
# local_modifications_pushed: false. Step 6 is gone: the correct behaviour is
# a refusal before the fetch, and the fixture's value is that it still proves
# the upstream received nothing.
# =========================================================================
h5_root="$(mktemp -d "${TMPDIR:-/tmp}/adv-h5-XXXXXX")"; FIXTURE_DIRS+=("$h5_root")
mk_fixture "$h5_root" "subs/helixqa" 0
up_commit "${h5_root}/upstream.git" "${h5_root}/p1" "upstream adds a .gitignore" ".gitignore" "scratch.txt"
printf 'operator scratch\n' > "${h5_root}/parent/subs/helixqa/scratch.txt"
h5_upstream_before="$(git -C "${h5_root}/upstream.git" rev-parse master)"
h5_pin_before="$(parent_pin "${h5_root}/parent" "subs/helixqa")"
run_script "${h5_root}/parent" "${h5_root}/records" "true"
h5_exit="$RUN_EXIT"
expect_eq "H5(soon-to-be-ignored-file-is-still-local-work)" "exit code" "1" "$h5_exit"
expect_eq "H5(soon-to-be-ignored-file-is-still-local-work)" "fixture upstream really received nothing" "$h5_upstream_before" "$(git -C "${h5_root}/upstream.git" rev-parse master)"
expect_eq "H5(soon-to-be-ignored-file-is-still-local-work)" "parent pin NOT updated" "$h5_pin_before" "$(parent_pin "${h5_root}/parent" "subs/helixqa")"
expect_contains "H5(soon-to-be-ignored-file-is-still-local-work)" "the refusal names the path" "scratch.txt" "$LAST_OUTPUT"
if h5_rec="$(find_record "${h5_root}/records" "subs/helixqa")"; then
  expect_eq "H5(soon-to-be-ignored-file-is-still-local-work)" "outcome" "FAILED_PRECONDITION" "$(jq -r '.outcome' "$h5_rec")"
  expect_eq "H5(soon-to-be-ignored-file-is-still-local-work)" "local_modifications_pushed" "false" "$(jq -r '.local_modifications_pushed' "$h5_rec")"
else
  echo "FAIL: H5(soon-to-be-ignored-file-is-still-local-work): no record found"; FAILURES=$((FAILURES + 2))
fi

# =========================================================================
# H6 (RETARGETED). Two mirrors (SS 6.W GitHub + GitLab), the second refusing
# every push server-side.
#
# It used to test that when the FIRST mirror accepted the local-work push and
# the second refused it, the record said local_modifications_pushed: true --
# because the work really WAS published and no history-overwriting push may
# take it back. The question that mattered was "which mirrors received it".
#
# The answer is now NONE, on either path, and this case measures exactly that.
# Both halves are run against the SAME fixture shape: once with the submodule
# unclean (the shape that used to arm the publish) and once clean (the shape
# that advances). The rejecting hook is the witness -- if any push were
# attempted, its stderr would appear in the run's output.
# =========================================================================
h6_root="$(mktemp -d "${TMPDIR:-/tmp}/adv-h6-XXXXXX")"; FIXTURE_DIRS+=("$h6_root")
mk_fixture "$h6_root" "subs/helixqa" 1
h6_sub="${h6_root}/parent/subs/helixqa"
git clone --quiet --bare "${h6_root}/upstream.git" "${h6_root}/mirror2.git"
cat > "${h6_root}/mirror2.git/hooks/pre-receive" <<'HOOKEOF'
#!/usr/bin/env bash
echo "remote: refusing: this branch is protected" >&2
exit 1
HOOKEOF
chmod +x "${h6_root}/mirror2.git/hooks/pre-receive"
# named so it sorts AFTER origin: `git remote` output is alphabetical, so a
# push loop (if one existed) would reach the accepting mirror first.
git -C "$h6_sub" remote add zz-mirror2 "${h6_root}/mirror2.git"
printf 'local uncommitted work\n' >> "${h6_sub}/seed.txt"
h6_pin_before="$(parent_pin "${h6_root}/parent" "subs/helixqa")"
h6_m1_before="$(git -C "${h6_root}/upstream.git" rev-parse master)"
h6_m2_before="$(git -C "${h6_root}/mirror2.git" rev-parse master)"
run_script "${h6_root}/parent" "${h6_root}/records" "true"
h6_exit="$RUN_EXIT"
expect_eq "H6(no-mirror-receives-anything/unclean)" "exit code" "1" "$h6_exit"
expect_eq "H6(no-mirror-receives-anything/unclean)" "mirror 1 received nothing" "$h6_m1_before" "$(git -C "${h6_root}/upstream.git" rev-parse master)"
expect_eq "H6(no-mirror-receives-anything/unclean)" "mirror 2 received nothing" "$h6_m2_before" "$(git -C "${h6_root}/mirror2.git" rev-parse master)"
expect_eq "H6(no-mirror-receives-anything/unclean)" "parent pin NOT updated" "$h6_pin_before" "$(parent_pin "${h6_root}/parent" "subs/helixqa")"
if grep -qF -- "this branch is protected" <<<"$LAST_OUTPUT"; then
  echo "FAIL: H6(no-mirror-receives-anything/unclean): the rejecting mirror's hook fired, so a push WAS attempted"
  FAILURES=$((FAILURES + 1))
else
  echo "PASS: H6(no-mirror-receives-anything/unclean): the rejecting mirror's pre-receive hook never fired -- no push was attempted"
fi
if h6_rec="$(find_record "${h6_root}/records" "subs/helixqa")"; then
  expect_eq "H6(no-mirror-receives-anything/unclean)" "outcome" "FAILED_PRECONDITION" "$(jq -r '.outcome' "$h6_rec")"
  expect_eq "H6(no-mirror-receives-anything/unclean)" "local_modifications_pushed" "false" "$(jq -r '.local_modifications_pushed' "$h6_rec")"
else
  echo "FAIL: H6(no-mirror-receives-anything/unclean): no record found"; FAILURES=$((FAILURES + 2))
fi

# ...and the same mirror pair on the CLEAN path, which DOES advance. A run
# that succeeds must still leave both mirrors untouched: this is the half the
# unclean case cannot prove, because a refusal reaches no push code either way.
h6b_root="$(mktemp -d "${TMPDIR:-/tmp}/adv-h6b-XXXXXX")"; FIXTURE_DIRS+=("$h6b_root")
mk_fixture "$h6b_root" "subs/helixqa" 1
h6b_sub="${h6b_root}/parent/subs/helixqa"
git clone --quiet --bare "${h6b_root}/upstream.git" "${h6b_root}/mirror2.git"
cp "${h6_root}/mirror2.git/hooks/pre-receive" "${h6b_root}/mirror2.git/hooks/pre-receive"
chmod +x "${h6b_root}/mirror2.git/hooks/pre-receive"
git -C "$h6b_sub" remote add zz-mirror2 "${h6b_root}/mirror2.git"
h6b_pin_before="$(parent_pin "${h6b_root}/parent" "subs/helixqa")"
h6b_m1_before="$(git -C "${h6b_root}/upstream.git" rev-parse master)"
h6b_m2_before="$(git -C "${h6b_root}/mirror2.git" rev-parse master)"
run_script "${h6b_root}/parent" "${h6b_root}/records" "true"
expect_eq "H6b(clean-advance-still-touches-no-mirror)" "exit code" "0" "$RUN_EXIT"
expect_ne "H6b(clean-advance-still-touches-no-mirror)" "parent pin DID advance" "$h6b_pin_before" "$(parent_pin "${h6b_root}/parent" "subs/helixqa")"
expect_eq "H6b(clean-advance-still-touches-no-mirror)" "mirror 1 received nothing" "$h6b_m1_before" "$(git -C "${h6b_root}/upstream.git" rev-parse master)"
expect_eq "H6b(clean-advance-still-touches-no-mirror)" "mirror 2 received nothing" "$h6b_m2_before" "$(git -C "${h6b_root}/mirror2.git" rev-parse master)"

# =========================================================================
# H7 (RETARGETED). A mirror whose own default branch is named differently.
#
# It used to test that step 6 resolved the target branch PER REMOTE, because
# resolving it once from the preferred remote pushed a brand-new `master` onto
# a mirror whose real default was `main`, leaving that default with nothing --
# silent SS 6.W / SS 6.C divergence.
#
# There is no push, so the assertion is now the stronger one: the mirror gains
# NO branch and its own default gains NO commit, on both paths.
# =========================================================================
h7_root="$(mktemp -d "${TMPDIR:-/tmp}/adv-h7-XXXXXX")"; FIXTURE_DIRS+=("$h7_root")
mk_fixture "$h7_root" "subs/helixqa" 1
h7_sub="${h7_root}/parent/subs/helixqa"
git clone --quiet --bare "${h7_root}/upstream.git" "${h7_root}/mirror2.git"
git -C "${h7_root}/mirror2.git" branch -m master main
git -C "${h7_root}/mirror2.git" symbolic-ref HEAD refs/heads/main
git -C "$h7_sub" remote add zz-mirror2 "${h7_root}/mirror2.git"
printf 'local uncommitted work\n' >> "${h7_sub}/seed.txt"
h7_main_before="$(git -C "${h7_root}/mirror2.git" rev-parse main)"
run_script "${h7_root}/parent" "${h7_root}/records" "true"
h7_branches="$(git -C "${h7_root}/mirror2.git" for-each-ref --format='%(refname:short)' refs/heads | sort | tr '\n' ' ')"
expect_eq "H7(mirror-default-branch-skew/unclean)" "no stray branch created on the mirror" "main " "$h7_branches"
expect_eq "H7(mirror-default-branch-skew/unclean)" "the mirror's own default branch received nothing" "$h7_main_before" "$(git -C "${h7_root}/mirror2.git" rev-parse main)"

# ...and on the CLEAN path, which advances.
h7b_root="$(mktemp -d "${TMPDIR:-/tmp}/adv-h7b-XXXXXX")"; FIXTURE_DIRS+=("$h7b_root")
mk_fixture "$h7b_root" "subs/helixqa" 1
h7b_sub="${h7b_root}/parent/subs/helixqa"
git clone --quiet --bare "${h7b_root}/upstream.git" "${h7b_root}/mirror2.git"
git -C "${h7b_root}/mirror2.git" branch -m master main
git -C "${h7b_root}/mirror2.git" symbolic-ref HEAD refs/heads/main
git -C "$h7b_sub" remote add zz-mirror2 "${h7b_root}/mirror2.git"
h7b_main_before="$(git -C "${h7b_root}/mirror2.git" rev-parse main)"
run_script "${h7b_root}/parent" "${h7b_root}/records" "true"
expect_eq "H7b(mirror-default-branch-skew/clean)" "exit code" "0" "$RUN_EXIT"
h7b_branches="$(git -C "${h7b_root}/mirror2.git" for-each-ref --format='%(refname:short)' refs/heads | sort | tr '\n' ' ')"
expect_eq "H7b(mirror-default-branch-skew/clean)" "no stray branch created on the mirror" "main " "$h7b_branches"
expect_eq "H7b(mirror-default-branch-skew/clean)" "the mirror's own default branch received nothing" "$h7b_main_before" "$(git -C "${h7b_root}/mirror2.git" rev-parse main)"

# =========================================================================
# H8 (RETARGETED). Step 7 (`git add <submodule>` in the parent) fails because
# a stale .git/index.lock is present -- the routine cause, left here by the
# verify command so it appears in the same window a concurrent or crashed git
# process would leave one in.
#
# It used to test that a rescue ref preserved the step-6 commit that had
# ALREADY been published before HEAD was moved back off it. There is no commit
# and no push, so the case now asserts the two things that remain true and one
# that must NOT hold: the outcome is recorded honestly, the pin is not staged,
# and NO rescue ref exists -- a rescue ref here would mean this script had
# created a commit, which it must never do.
# =========================================================================
h8_root="$(mktemp -d "${TMPDIR:-/tmp}/adv-h8-XXXXXX")"; FIXTURE_DIRS+=("$h8_root")
mk_fixture "$h8_root" "subs/helixqa" 1
h8_sub="${h8_root}/parent/subs/helixqa"
cat > "${h8_root}/verify.sh" <<VEOF
#!/usr/bin/env bash
# stands in for another git process (or a killed earlier run) holding the lock
: > "${h8_root}/parent/.git/index.lock"
exit 0
VEOF
chmod +x "${h8_root}/verify.sh"
h8_up_before="$(git -C "${h8_root}/upstream.git" rev-parse master)"
h8_pin_before="$(parent_pin "${h8_root}/parent" "subs/helixqa")"
run_script "${h8_root}/parent" "${h8_root}/records" "bash ${h8_root}/verify.sh"
h8_exit="$RUN_EXIT"
rm -rf -- "${h8_root}/parent/.git/index.lock"
expect_eq "H8(parent-staging-fails)" "exit code" "1" "$h8_exit"
expect_eq "H8(parent-staging-fails)" "the upstream received nothing" "$h8_up_before" "$(git -C "${h8_root}/upstream.git" rev-parse master)"
expect_eq "H8(parent-staging-fails)" "parent pin NOT staged" "$h8_pin_before" "$(parent_pin "${h8_root}/parent" "subs/helixqa")"
h8_rescue="$(git -C "$h8_sub" for-each-ref --format='%(refname)' 'refs/lava-advance-rescue/**' | head -n1)"
if [[ -z "$h8_rescue" ]]; then
  echo "PASS: H8(parent-staging-fails): no rescue ref exists -- this script creates no commit for one to rescue"
else
  echo "FAIL: H8(parent-staging-fails): a rescue ref '${h8_rescue}' exists, which can only mean a commit was created"
  FAILURES=$((FAILURES + 1))
fi
if h8_rec="$(find_record "${h8_root}/records" "subs/helixqa")"; then
  expect_eq "H8(parent-staging-fails)" "outcome" "REJECTED_PARENT_STAGING_FAILED" "$(jq -r '.outcome' "$h8_rec")"
  expect_eq "H8(parent-staging-fails)" "local_modifications_pushed" "false" "$(jq -r '.local_modifications_pushed' "$h8_rec")"
  expect_eq "H8(parent-staging-fails)" "parent_pin_updated" "false" "$(jq -r '.parent_pin_updated' "$h8_rec")"
else
  echo "FAIL: H8(parent-staging-fails): no record found"; FAILURES=$((FAILURES + 3))
fi

# =========================================================================
# H9: the record write on the governance-deny path is the ONE record whose
# failure nothing else counts -- every other path already increments the
# failure counter for its own reason. Swallowing it with `|| true` produces
# exit 0 with zero evidence that the governance refusal ever happened.
# =========================================================================
h9_root="$(mktemp -d "${TMPDIR:-/tmp}/adv-h9-XXXXXX")"; FIXTURE_DIRS+=("$h9_root")
mk_fixture "$h9_root" "constitution" 1
mkdir -p "${h9_root}/records"
chmod 500 "${h9_root}/records"
run_script "${h9_root}/parent" "${h9_root}/records" "true"
h9_exit="$RUN_EXIT"
chmod 700 "${h9_root}/records"
expect_ne "H9(governance-record-unwritable)" "exit code (0 would be a silent green)" "0" "$h9_exit"
expect_eq "H9(governance-record-unwritable)" "parent pin still NOT advanced" \
  "$(git -C "${h9_root}/parent" rev-parse HEAD:constitution)" "$(parent_pin "${h9_root}/parent" "constitution")"

# =========================================================================
# H10: restoring the prior pin is a plain (non-discarding) checkout, which
# git REFUSES when the rebuild wrote into a tracked file that differs between
# the two commits. The submodule is then left sitting on the REJECTED commit.
# The script's header claims "on ANY rejection it restores the submodule to
# its prior pinned commit"; when that cannot be done, the run summary -- not
# only a stderr line lost in a 25-submodule run -- must say so.
# =========================================================================
h10_root="$(mktemp -d "${TMPDIR:-/tmp}/adv-h10-XXXXXX")"; FIXTURE_DIRS+=("$h10_root")
mk_fixture "$h10_root" "subs/helixqa" 0
up_commit "${h10_root}/upstream.git" "${h10_root}/p1" "upstream edits seed.txt" "seed.txt" "upstream version"
h10_sub="${h10_root}/parent/subs/helixqa"
cat > "${h10_root}/verify.sh" <<VEOF
#!/usr/bin/env bash
# a rebuild that stamps a tracked file (generated header / version stamp /
# formatter run) and then fails its tests
printf 'built artifact\n' > "${h10_sub}/seed.txt"
exit 1
VEOF
chmod +x "${h10_root}/verify.sh"
h10_pin_before="$(parent_pin "${h10_root}/parent" "subs/helixqa")"
run_script "${h10_root}/parent" "${h10_root}/records" "bash ${h10_root}/verify.sh"
h10_exit="$RUN_EXIT"
expect_eq "H10(restore-failed)" "exit code" "1" "$h10_exit"
expect_eq "H10(restore-failed)" "parent pin NOT updated" "$h10_pin_before" "$(parent_pin "${h10_root}/parent" "subs/helixqa")"
expect_contains "H10(restore-failed)" "run SUMMARY line" "not restored" \
  "$(printf '%s\n' "$LAST_OUTPUT" | grep -E '^advance-all-submodules: [0-9]+ advanced' || echo '<summary line missing>')"

# =========================================================================
# H11: a verify command KILLED by the OOM killer or a `timeout` is not a
# breaking change -- nothing was learned about the advanced state. The record
# schema has no outcome for that (a T054 review item), but the run output must
# at minimum state the real exit status instead of asserting a clean verdict.
# =========================================================================
h11_root="$(mktemp -d "${TMPDIR:-/tmp}/adv-h11-XXXXXX")"; FIXTURE_DIRS+=("$h11_root")
mk_fixture "$h11_root" "subs/helixqa" 1
cat > "${h11_root}/killed.sh" <<'KEOF'
#!/usr/bin/env bash
# stands in for an OOM kill / `timeout -s KILL` of the rebuild-and-test step
kill -9 $$
KEOF
chmod +x "${h11_root}/killed.sh"
h11_pin_before="$(parent_pin "${h11_root}/parent" "subs/helixqa")"
run_script "${h11_root}/parent" "${h11_root}/records" "bash ${h11_root}/killed.sh"
h11_exit="$RUN_EXIT"
expect_eq "H11(verify-killed)" "exit code" "1" "$h11_exit"
expect_eq "H11(verify-killed)" "parent pin NOT updated" "$h11_pin_before" "$(parent_pin "${h11_root}/parent" "subs/helixqa")"
expect_contains "H11(verify-killed)" "run output" "exit status 137" "$LAST_OUTPUT"

echo "---"
if [[ "$FAILURES" -eq 0 ]]; then
  echo "PASS: all advance-all-submodules hardening cases passed"
  exit 0
else
  echo "FAIL: ${FAILURES} advance-all-submodules hardening assertion(s) failed"
  exit 1
fi

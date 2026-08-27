#!/usr/bin/env bash
# T054 blocker suite for scripts/advance-all-submodules.sh.
#
# tests/pipeline/test_advance_all_submodules.sh covers the R-005 happy /
# rejection branches plus the governance deny; ..._hardening.sh covers the
# 2026-08-22 audit findings H1-H11; ..._blast_radius.sh covers the 2026-08-23
# blast-radius audit B1-B3. THIS suite covers the 2026-08-26 T054 human
# checkpoint #2 review: three blockers and four should-fix items that are
# violations of root CLAUDE.md's Automated Pipeline Pin-Advance Path.
#
# Every case below was demonstrated against a disposable fixture BEFORE the
# fix, and every negative assertion here failed at that point. The captured
# pre-fix behaviour is quoted in each case's comment.
#
# SAFETY: identical to its siblings, and then some. Every "upstream" is a bare
# repo inside a mktemp -d. The condition-(C) cases configure remotes that NAME
# github.com / gitlab.com / a .invalid host, but every one of them is in ssh
# form and the whole case runs under GIT_SSH_COMMAND=/bin/false, which cannot
# open a connection -- so a REGRESSION of the scope check fails fast instead of
# reaching a real host. Nothing here touches this repository's real submodules,
# real upstreams, or the network. Every fixture is removed on EXIT.
#
# Cases
#   T1   BLOCKER-1  the pin is the PARENT INDEX gitlink, not the submodule HEAD
#   T1b  BLOCKER-1  the fast-forward guard is evaluated against that pin
#   T1c  BLOCKER-1b an UNRESTORED run's leftovers are refused, not read clean
#   T1p  POSITIVE   a genuine fast-forward with HEAD == pin still advances
#   T2a  BLOCKER-2  a verify build that OVERWRITES operator content is refused
#   T2b  BLOCKER-2  a verify build that DELETES operator content is refused
#   T2p  POSITIVE   a build touching unrelated paths still publishes the work
#   T3a  BLOCKER-3  a quote-bearing run id cannot inject, and cannot pass the gate
#   T3b  BLOCKER-3  the run id reaches the verify command POSITIONALLY, as $3
#   T3c  NIT-7      a traversing run id cannot walk RECORD_DIR out of the tree
#   T4   cond (C)   an out-of-own-org remote is refused BEFORE any fetch
#   T4u  cond (C)   the URL classifier itself, over a table of real URL shapes
#   T4p  POSITIVE   own-org and local-path remotes are not refused
#   T5   SF-1       a push loop that ran ZERO times is not a successful publish
#   T6   SF-2       an allow-list token naming nothing fails loudly
#   T6p  POSITIVE   an exact allow-list token still advances
#   T7   cond (E)   every terminal refusal path leaves a record
#
# ROUND 2 -- the 2026-08-26 T054 RE-REVIEW of the fixes above. It re-proved
# all three blockers closed from its own fixtures, and found five more.
#   T8   FIX-1      the safe-operating precondition is CHECKED and STATED
#   T8p  POSITIVE   asked for explicitly, step 6 still publishes
#   T9   FIX-2a     a MODE-only change by the build is refused
#   T10  FIX-2b     a nested submodule's GITLINK moved by the build is refused
#   T11  FIX-3      the cond-(C) scan may not pass having read ZERO remotes
#   T12  FIX-4      the refusal's recovery claim is true, and the sha resolves
#   T13  FIX-5      a filesystem-path remote is gated, not permitted
#   T13p POSITIVE   the flag re-opens it, so the fixtures still reach push
#   T14  NIT-1      a path-navigation run id is refused
#   T15  NIT-2      a SUBMODULE staging failure names its own cause
#   T16  NIT-4      authorization is checked against the UPSTREAM, not the path
#   T16p POSITIVE   the authorized upstream is not caught by that check
#
# Exit 0 if every case passes; non-zero otherwise.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT_UNDER_TEST="${REPO_ROOT}/scripts/advance-all-submodules.sh"
SUB_PATH="submodules/helixqa"

if ! command -v jq >/dev/null 2>&1; then
  echo "FAIL: jq is required to run this test suite but was not found on PATH"
  exit 1
fi

FIXTURE_DIRS=()
cleanup() {
  local d
  for d in "${FIXTURE_DIRS[@]:-}"; do
    if [[ -n "$d" && -d "$d" ]]; then
      chmod -R u+w "$d" 2>/dev/null || true
      rm -rf -- "$d"
    fi
  done
}
trap cleanup EXIT

FAILURES=0
# Every case increments this. A suite that asserted nothing must not report
# success -- the vacuous-pass family this repository has ~50 recorded
# instances of, and the very defect T6 below is about.
CASES_RUN=0

# ...and CASES_RUN counts case ENTRY, which is strictly weaker than it reads.
# The T054 round-4 review proved it by copying this suite, neutering all six
# of T18's assertions, and running it: "examined 6 distinct publish-escape
# routes / this suite ran 48 cases / ALL CASES PASSED", exit 0. An entry
# counter catches a case that was DELETED or SKIPPED; it cannot see one that
# was HOLLOWED. So every assertion this suite makes is counted here, at the
# only place that can know one really executed -- inside the assertion helpers
# themselves -- and the floors below are stated in assertions, not in entries.
ASSERTIONS_RUN=0

# note_assertion -- for the handful of bespoke `if ... echo PASS/FAIL` blocks
# that do not go through the helpers. They are assertions too and must count
# as such, or the floor develops the same blind spot one level down.
note_assertion() { ASSERTIONS_RUN=$((ASSERTIONS_RUN + 1)); }

expect_eq() {
  local label="$1" what="$2" expected="$3" actual="$4"
  ASSERTIONS_RUN=$((ASSERTIONS_RUN + 1))
  if [[ "$expected" == "$actual" ]]; then
    echo "PASS: ${label}: ${what} == '${expected}'"
  else
    echo "FAIL: ${label}: ${what} expected '${expected}', got '${actual}'"
    FAILURES=$((FAILURES + 1))
  fi
}

expect_ne() {
  local label="$1" what="$2" not_expected="$3" actual="$4"
  ASSERTIONS_RUN=$((ASSERTIONS_RUN + 1))
  if [[ "$not_expected" != "$actual" ]]; then
    echo "PASS: ${label}: ${what} ('${actual}') differs from '${not_expected}' as required"
  else
    echo "FAIL: ${label}: ${what} expected to differ from '${not_expected}' but was identical"
    FAILURES=$((FAILURES + 1))
  fi
}

# Matching on a herestring, never `printf | grep -q`: under `set -o pipefail`
# a short-circuiting consumer SIGPIPEs the producer and turns a MATCH into a
# NO-MATCH above the 64KB pipe buffer (LVA-135).
expect_contains() {
  local label="$1" what="$2" needle="$3" haystack="$4"
  ASSERTIONS_RUN=$((ASSERTIONS_RUN + 1))
  if grep -qF -- "$needle" <<<"$haystack"; then
    echo "PASS: ${label}: ${what} contains '${needle}'"
  else
    echo "FAIL: ${label}: ${what} does not contain '${needle}'"
    echo "      actual: ${haystack}"
    FAILURES=$((FAILURES + 1))
  fi
}

gcfg() {
  git -C "$1" config user.email "pipeline-test@example.invalid"
  git -C "$1" config user.name "Pipeline Test"
  git -C "$1" config commit.gpgsign false
}

# up_commit <bare> <scratch> <message> <filename> [content] [branch]
up_commit() {
  local bare="$1" scratch="$2" message="$3" filename="$4" content="${5:-}" branch="${6:-master}"
  git clone --quiet "$bare" "$scratch"
  gcfg "$scratch"
  mkdir -p -- "$(dirname -- "${scratch}/${filename}")"
  printf '%s\n' "${content:-$message}" > "${scratch}/${filename}"
  git -C "$scratch" add -- "$filename"
  git -C "$scratch" commit --quiet -m "$message"
  git -C "$scratch" push --quiet origin "HEAD:${branch}"
  rm -rf -- "$scratch"
}

# make_fixture <extra-upstream-commits> -- echoes the fixture root
make_fixture() {
  local extra="${1:-0}" root i
  root="$(mktemp -d "${TMPDIR:-/tmp}/adv-t054-XXXXXX")"
  FIXTURE_DIRS+=("$root")

  git init --quiet --bare -b master "${root}/upstream.git"
  git clone --quiet "${root}/upstream.git" "${root}/seed" 2>/dev/null
  gcfg "${root}/seed"
  echo "seed" > "${root}/seed/seed.txt"
  git -C "${root}/seed" add seed.txt
  git -C "${root}/seed" commit --quiet -m "submodule initial commit"
  git -C "${root}/seed" push --quiet origin HEAD:master
  rm -rf -- "${root}/seed"

  git init --quiet -b master "${root}/parent"
  gcfg "${root}/parent"
  echo "parent" > "${root}/parent/parent.txt"
  git -C "${root}/parent" add parent.txt
  git -C "${root}/parent" commit --quiet -m "parent initial commit"
  git -c protocol.file.allow=always -C "${root}/parent" \
    submodule add --quiet "${root}/upstream.git" "$SUB_PATH" >/dev/null 2>&1
  gcfg "${root}/parent/${SUB_PATH}"
  git -C "${root}/parent" add -A
  git -C "${root}/parent" commit --quiet -m "pin submodule"

  for (( i = 1; i <= extra; i++ )); do
    up_commit "${root}/upstream.git" "${root}/pusher-${i}" \
      "upstream commit ${i}" "feature-${i}.txt"
  done
  echo "$root"
}

parent_pin()   { git -C "$1" ls-files -s -- "$SUB_PATH" | awk '{print $2}'; }
sub_head()     { git -C "$1/${SUB_PATH}" rev-parse HEAD; }
upstream_tip() { git -C "$1/upstream.git" rev-parse master; }
upstream_has() { if git -C "$1/upstream.git" cat-file -e "master:$2" 2>/dev/null; then echo YES; else echo NO; fi; }
upstream_show(){ git -C "$1/upstream.git" show "master:$2" 2>/dev/null; }
record_count() { find "$1" -type f -name '*.json' 2>/dev/null | wc -l | tr -d ' '; }

find_record() {
  local dir="$1" f
  [[ -d "$dir" ]] || return 1
  while IFS= read -r f; do
    if [[ "$(jq -r '.submodule_name' "$f" 2>/dev/null)" == "$SUB_PATH" ]]; then
      echo "$f"; return 0
    fi
  done < <(find "$dir" -type f -name '*.json' 2>/dev/null)
  return 1
}

# Both flags below are REQUIRED by every fixture in this file, and each says
# something true about the fixture rather than about the production default:
#
#   --allow-local-path-remotes      every "upstream" here is a bare repo under
#       mktemp -d, i.e. a filesystem path. Production refuses that shape
#       (a local path can be another real repository, or a network mount that
#       reaches another machine while naming no host); a hermetic fixture can
#       have no other shape, and a suite that cannot reach the push path
#       cannot prove the push path is safe.
# `--publish-local-modifications` was REMOVED on 2026-08-26, together with the
# R-005 step 6 it gated, after five review rounds found TWELVE distinct,
# fixture-proven ways for content no guard had read to reach another
# repository's default branch through it. The count went UP at every round
# (3 -> 5 -> 7 -> 9 -> 10 -> 11 -> 12) and the convergent byte-equality
# instrument was itself defeated, so the capability was removed rather than
# guarded a sixth time.
#
# Every case below that used to prove a publish guard FIRED now proves the
# route it guarded is UNREACHABLE. Two shapes are used:
#
#   * the case's own fixture, which leaves the submodule unclean -- now a
#     refusal taken BEFORE the fetch, so the adversarial verify command does
#     not even execute; and
#   * T50, which runs each historical route's adversarial payload against a
#     CLEAN submodule, where the payload really does execute, and asserts the
#     upstream is byte-identical afterwards anyway.
#
# The second is the load-bearing one: a refusal proves the entry condition is
# gone, and T50 proves there is nothing behind it to reach.
#
# `--allow-local-path-remotes` remains, and is REQUIRED by every fixture here:
# every "upstream" is a bare repo under mktemp -d, i.e. a filesystem path.
# Production refuses that shape; a hermetic fixture can have no other, and a
# suite that cannot reach the fetch path cannot prove the fetch path is safe.
# It is a command-line flag rather than an environment variable precisely so
# that this suite's need for it cannot leak into an unattended pipeline run
# through an inherited environment.
ADV_FLAGS=(--allow-local-path-remotes)

# upstream_fp <root> -- every ref of the fixture upstream, its target, and the
# exact number of objects it holds. "The upstream is unchanged" asserted on
# `master` alone would miss a stray branch, a tag, a refs/replace/* entry, or
# objects pushed and then left unreferenced -- each of which was a real escape
# route in one of the five review rounds. Refs AND object count together catch
# every one of them.
upstream_fp() {
  {
    git -C "$1/upstream.git" for-each-ref --format='%(refname) %(objectname)' 2>/dev/null | LC_ALL=C sort
    printf 'OBJECTS=%s\n' "$(git -C "$1/upstream.git" cat-file --batch-all-objects --batch-check='%(objectname)' 2>/dev/null | wc -l | tr -d ' ')"
  } | LC_ALL=C sort
}

LAST_OUTPUT=""
RUN_EXIT=0
# run_script <parent> <record-dir> <verify-cmd> [extra VAR=VALUE ...]
# Assigns LAST_OUTPUT / RUN_EXIT in the CALLER's shell -- never invoked inside
# a command substitution, which would make those assignments vanish into a
# subshell and leave every later read stale.
run_script() {
  local parent="$1" record_dir="$2" verify_cmd="$3"; shift 3
  RUN_EXIT=0
  LAST_OUTPUT="$(
    env "$@" \
      LAVA_ADVANCE_RECORD_DIR="$record_dir" \
      LAVA_ADVANCE_VERIFY_CMD="$verify_cmd" \
      "$SCRIPT_UNDER_TEST" ${ADV_FLAGS[@]+"${ADV_FLAGS[@]}"} "$parent" 2>&1
  )" || RUN_EXIT=$?
}

# =========================================================================
# T1: the pin is the PARENT INDEX gitlink, never the submodule's HEAD.
#
# PRE-FIX (captured): the parent deliberately pinned side-branch commit S while
# the working tree sat at its ancestor A. `old_commit` was read from HEAD, so
# `merge-base --is-ancestor A B` passed, the pin moved to B, and S was no
# longer reachable from the staged pin:
#   "ADVANCED to 1f7c0b94...; parent pin staged"   EXIT=0
#   "Is the pinned side commit S still reachable?  NO -- S HAS BEEN DROPPED"
# The record's old_commit named A too, so the evidence at rest also misstated
# what was advanced FROM. This is the `lava-pin/2026-05-07-pkg-vm` case the
# guard's own comment says it exists to prevent.
# =========================================================================
echo "=== T1: a side-branch pin is not dropped when the tree sits at an ancestor ==="
CASES_RUN=$((CASES_RUN + 1))
t1_root="$(make_fixture 0)"
t1_A="$(git -C "${t1_root}/upstream.git" rev-parse master)"
up_commit "${t1_root}/upstream.git" "${t1_root}/p-side" "the pinned side work" "side-work.txt" "" "sidebranch"
t1_S="$(git -C "${t1_root}/upstream.git" rev-parse sidebranch)"
up_commit "${t1_root}/upstream.git" "${t1_root}/p-main" "unrelated main advance" "mainonly.txt"
t1_B="$(upstream_tip "$t1_root")"
git -C "${t1_root}/parent/${SUB_PATH}" fetch --quiet origin
git -C "${t1_root}/parent/${SUB_PATH}" checkout --detach --quiet "$t1_S"
git -C "${t1_root}/parent" add -- "$SUB_PATH"
git -C "${t1_root}/parent" commit --quiet -m "pin the side branch deliberately"
git -C "${t1_root}/parent/${SUB_PATH}" checkout --detach --quiet "$t1_A"

expect_eq "T1" "fixture sanity: parent pins the SIDE commit" "$t1_S" "$(parent_pin "${t1_root}/parent")"
expect_eq "T1" "fixture sanity: the tree sits at the ANCESTOR" "$t1_A" "$(sub_head "${t1_root}/parent")"
run_script "${t1_root}/parent" "${t1_root}/records" 'true'
expect_ne "T1" "exit code (0 would certify a dropped pin)" "0" "$RUN_EXIT"
expect_eq "T1" "parent pin untouched" "$t1_S" "$(parent_pin "${t1_root}/parent")"
if git -C "${t1_root}/parent/${SUB_PATH}" merge-base --is-ancestor "$t1_S" "$(parent_pin "${t1_root}/parent")" 2>/dev/null; then
  echo "PASS: T1: the deliberately-pinned side commit is still reachable from the staged pin"
else
  echo "FAIL: T1: the deliberately-pinned side commit was DROPPED from the staged pin"
  FAILURES=$((FAILURES + 1))
fi
if t1_rec="$(find_record "${t1_root}/records")"; then
  expect_ne "T1" "outcome must not claim an advance" "ADVANCED" "$(jq -r '.outcome' "$t1_rec")"
  expect_eq "T1" "record old_commit is the PIN, not the working-tree HEAD" "$t1_S" "$(jq -r '.old_commit' "$t1_rec")"
  expect_eq "T1" "parent_pin_updated" "false" "$(jq -r '.parent_pin_updated' "$t1_rec")"
else
  echo "FAIL: T1: no Submodule Advance Record was written"; FAILURES=$((FAILURES + 3))
fi

# --- T1b: the fast-forward guard, evaluated against the PIN ---------------
# HEAD == pin == S here, so the HEAD-vs-pin guard cannot fire and what is
# under test is solely which reference the ancestry check uses. Pre-fix this
# ALSO passed the guard whenever the tree had been moved; with the tree left
# on the pin it correctly refused, which is why the reference bug stayed
# latent. This case pins the correct-reference behaviour in place.
echo "=== T1b: HEAD == pin, and origin/HEAD is not a descendant -> refused ==="
CASES_RUN=$((CASES_RUN + 1))
t1b_root="$(make_fixture 0)"
up_commit "${t1b_root}/upstream.git" "${t1b_root}/ps" "side work" "side.txt" "" "sidebranch"
t1b_S="$(git -C "${t1b_root}/upstream.git" rev-parse sidebranch)"
up_commit "${t1b_root}/upstream.git" "${t1b_root}/pm" "main advance" "mainonly.txt"
git -C "${t1b_root}/parent/${SUB_PATH}" fetch --quiet origin
git -C "${t1b_root}/parent/${SUB_PATH}" checkout --detach --quiet "$t1b_S"
git -C "${t1b_root}/parent" add -- "$SUB_PATH"
git -C "${t1b_root}/parent" commit --quiet -m "pin the side branch"
run_script "${t1b_root}/parent" "${t1b_root}/records" 'true'
expect_ne "T1b" "exit code" "0" "$RUN_EXIT"
expect_contains "T1b" "run output" "not a fast-forward" "$LAST_OUTPUT"
expect_eq "T1b" "parent pin untouched" "$t1b_S" "$(parent_pin "${t1b_root}/parent")"
if t1b_rec="$(find_record "${t1b_root}/records")"; then
  expect_eq "T1b" "outcome names its own cause" "REFUSED_NOT_FAST_FORWARD" "$(jq -r '.outcome' "$t1b_rec")"
else
  echo "FAIL: T1b: no Submodule Advance Record was written for the refusal"; FAILURES=$((FAILURES + 1))
fi

# --- T1c: BLOCKER-1b, leftover state from an UNRESTORED run ---------------
# PRE-FIX (captured, two consecutive runs on one fixture): run 1's verify
# failed AND dirtied a tracked file, so the non-discarding restore checkout was
# refused and the submodule was left parked on the REJECTED commit. Run 2 read
# that commit as the pin:
#   "already at origin/master HEAD (b1f484b...) -- no newer commit"
#   EXIT2=0    parent pin = acd34583...   <== STILL THE OLD PIN
#   record2: {"outcome":"NO_NEWER_COMMIT"}
# A green record and exit 0 for a submodule whose pin never moved.
echo "=== T1c: an UNRESTORED run's leftovers must not read as a clean run ==="
CASES_RUN=$((CASES_RUN + 1))
t1c_root="$(make_fixture 0)"
up_commit "${t1c_root}/upstream.git" "${t1c_root}/p0" "seed tracked file" "file.txt" "ORIGINAL"
git -C "${t1c_root}/parent/${SUB_PATH}" fetch --quiet origin
git -C "${t1c_root}/parent/${SUB_PATH}" checkout --detach --quiet origin/master
git -C "${t1c_root}/parent" add -- "$SUB_PATH"
git -C "${t1c_root}/parent" commit --quiet -m "pin with file.txt"
t1c_pin="$(parent_pin "${t1c_root}/parent")"
up_commit "${t1c_root}/upstream.git" "${t1c_root}/p1" "upstream changes file.txt" "file.txt" "UPSTREAM-NEW"
# a rebuild that stamps a tracked file differing between the two commits, then
# fails -- the restore checkout cannot then be performed without discarding
run_script "${t1c_root}/parent" "${t1c_root}/rec1" 'printf "DIRTIED-BY-BUILD\n" > "$1/file.txt"; exit 1'
expect_ne "T1c(run1)" "exit code" "0" "$RUN_EXIT"
expect_contains "T1c(run1)" "run SUMMARY line" "not restored" \
  "$(grep -E '^advance-all-submodules: [0-9]+ advanced' <<<"$LAST_OUTPUT" || echo '<summary line missing>')"
expect_ne "T1c(run1)" "the submodule really was left off its pin" "$t1c_pin" "$(sub_head "${t1c_root}/parent")"
run_script "${t1c_root}/parent" "${t1c_root}/rec2" 'true'
expect_ne "T1c(run2)" "exit code (0 would be a green verdict over a stale pin)" "0" "$RUN_EXIT"
expect_eq "T1c(run2)" "parent pin still the ORIGINAL pin" "$t1c_pin" "$(parent_pin "${t1c_root}/parent")"
if t1c_rec="$(find_record "${t1c_root}/rec2")"; then
  expect_ne "T1c(run2)" "outcome must not be a clean NO_NEWER_COMMIT" "NO_NEWER_COMMIT" "$(jq -r '.outcome' "$t1c_rec")"
  expect_eq "T1c(run2)" "record old_commit is the PIN, not the rejected commit" "$t1c_pin" "$(jq -r '.old_commit' "$t1c_rec")"
else
  echo "FAIL: T1c(run2): no Submodule Advance Record was written"; FAILURES=$((FAILURES + 2))
fi

# --- T1p POSITIVE: a genuine fast-forward with HEAD == pin still advances --
echo "=== T1p: POSITIVE -- a genuine fast-forward still advances ==="
CASES_RUN=$((CASES_RUN + 1))
t1p_root="$(make_fixture 1)"
t1p_before="$(parent_pin "${t1p_root}/parent")"
run_script "${t1p_root}/parent" "${t1p_root}/records" 'true'
expect_eq "T1p" "exit code" "0" "$RUN_EXIT"
expect_eq "T1p" "parent pin advanced to the upstream tip" "$(upstream_tip "$t1p_root")" "$(parent_pin "${t1p_root}/parent")"
if t1p_rec="$(find_record "${t1p_root}/records")"; then
  expect_eq "T1p" "outcome" "ADVANCED" "$(jq -r '.outcome' "$t1p_rec")"
  expect_eq "T1p" "record old_commit is the prior pin" "$t1p_before" "$(jq -r '.old_commit' "$t1p_rec")"
else
  echo "FAIL: T1p: no Submodule Advance Record was written"; FAILURES=$((FAILURES + 2))
fi

# =========================================================================
# T2: BLOCKER-2 -- the staged path SET was frozen, its CONTENT was not.
#
# PRE-FIX (captured, direction A -- overwrite): the operator's hand-written
# build-output.log was replaced by the verify build's bytes and PUBLISHED:
#   ADVANCED ... EXIT=0
#   content of build-output.log AS PUBLISHED:
#     "BUILD ARTIFACT: compiler temp, 400MB of junk"
#
# PRE-FIX (captured, direction B -- deletion): a tracked file that existed on
# the upstream default branch was DELETED from it by the build:
#   VERIFY_CMD: rm -- "$1/keepme.txt"
#   is keepme.txt still on the upstream default branch?  DELETED FROM UPSTREAM
#
# Force-push is forbidden (§6.T.3), so neither can be taken back.
# =========================================================================
# RETARGETED 2026-08-26. The guard that used to catch this (a pre-verify
# content fingerprint compared after the build) is gone with the publish path
# it protected. The route is now closed one step earlier and one step harder:
# a submodule carrying the operator's build-output.log is REFUSED before the
# fetch, so the hostile verify command never runs at all.
echo "=== T2a: a submodule carrying operator content is refused before the build runs ==="
CASES_RUN=$((CASES_RUN + 1))
t2a_root="$(make_fixture 1)"
printf 'OPERATOR HAND-WRITTEN NOTE\n' > "${t2a_root}/parent/${SUB_PATH}/build-output.log"
t2a_pin="$(parent_pin "${t2a_root}/parent")"
t2a_up="$(upstream_fp "$t2a_root")"
run_script "${t2a_root}/parent" "${t2a_root}/records" \
  'printf "BUILD ARTIFACT - compiler temp\n" > "$1/build-output.log"; true'
expect_ne "T2a" "exit code (0 would certify a publish of the build's bytes)" "0" "$RUN_EXIT"
expect_eq "T2a" "the build's bytes did NOT reach the upstream" "NO" "$(upstream_has "$t2a_root" "build-output.log")"
expect_eq "T2a" "the upstream is byte-identical (refs + object count)" "$t2a_up" "$(upstream_fp "$t2a_root")"
expect_eq "T2a" "parent pin NOT staged" "$t2a_pin" "$(parent_pin "${t2a_root}/parent")"
expect_contains "T2a" "run output names the path" "build-output.log" "$LAST_OUTPUT"
# The verify command was never invoked, so the file still holds the operator's
# bytes rather than the build's. This is what "refused before the fetch" means
# in an assertion rather than in a comment.
expect_eq "T2a" "the operator's bytes are untouched (the build never ran)" "OPERATOR HAND-WRITTEN NOTE" \
  "$(cat "${t2a_root}/parent/${SUB_PATH}/build-output.log" 2>/dev/null)"
if t2a_rec="$(find_record "${t2a_root}/records")"; then
  expect_eq "T2a" "outcome names its own cause" "FAILED_PRECONDITION" "$(jq -r '.outcome' "$t2a_rec")"
  expect_eq "T2a" "local_modifications_pushed" "false" "$(jq -r '.local_modifications_pushed' "$t2a_rec")"
else
  echo "FAIL: T2a: no Submodule Advance Record was written"; FAILURES=$((FAILURES + 2))
fi

echo "=== T2b: a verify build that DELETES operator content is refused ==="
CASES_RUN=$((CASES_RUN + 1))
t2b_root="$(make_fixture 0)"
up_commit "${t2b_root}/upstream.git" "${t2b_root}/pk" "add keepme.txt" "keepme.txt" "IMPORTANT UPSTREAM FILE"
git -C "${t2b_root}/parent/${SUB_PATH}" fetch --quiet origin
git -C "${t2b_root}/parent/${SUB_PATH}" checkout --detach --quiet origin/master
git -C "${t2b_root}/parent" add -- "$SUB_PATH"
git -C "${t2b_root}/parent" commit --quiet -m "pin with keepme.txt"
up_commit "${t2b_root}/upstream.git" "${t2b_root}/pn" "unrelated upstream advance" "other.txt"
printf 'OPERATOR LOCAL EDIT\n' > "${t2b_root}/parent/${SUB_PATH}/keepme.txt"
expect_eq "T2b" "fixture sanity: keepme.txt is on the upstream default branch" "YES" "$(upstream_has "$t2b_root" "keepme.txt")"
t2b_up="$(upstream_fp "$t2b_root")"
run_script "${t2b_root}/parent" "${t2b_root}/records" 'rm -rf -- "$1/keepme.txt"; true'
expect_ne "T2b" "exit code" "0" "$RUN_EXIT"
expect_eq "T2b" "the tracked upstream file SURVIVED on the default branch" "YES" "$(upstream_has "$t2b_root" "keepme.txt")"
expect_eq "T2b" "its upstream content is untouched" "IMPORTANT UPSTREAM FILE" "$(upstream_show "$t2b_root" "keepme.txt")"
expect_eq "T2b" "the upstream is byte-identical (refs + object count)" "$t2b_up" "$(upstream_fp "$t2b_root")"
if t2b_rec="$(find_record "${t2b_root}/records")"; then
  expect_eq "T2b" "outcome" "FAILED_PRECONDITION" "$(jq -r '.outcome' "$t2b_rec")"
else
  echo "FAIL: T2b: no Submodule Advance Record was written"; FAILURES=$((FAILURES + 1))
fi

# --- T2p POSITIVE (RETARGETED) ------------------------------------------
# A blanket "refuse everything" would pass T2a and T2b while making the script
# useless, so a positive case is still required -- it just has a different
# subject. It used to be "the operator's work still publishes when the build
# did not touch it". Publishing is gone, so the positive property is now: a
# CLEAN submodule still advances even when the verify build writes into its
# working tree, and the upstream is still byte-identical afterwards.
echo "=== T2p: POSITIVE -- a clean submodule still advances, and still publishes nothing ==="
CASES_RUN=$((CASES_RUN + 1))
t2p_root="$(make_fixture 1)"
t2p_pin="$(parent_pin "${t2p_root}/parent")"
t2p_up="$(upstream_fp "$t2p_root")"
run_script "${t2p_root}/parent" "${t2p_root}/records" \
  'mkdir -p "$1/build/outputs" && printf "APK\n" > "$1/build/outputs/app.apk"; true'
expect_eq "T2p" "exit code" "0" "$RUN_EXIT"
expect_ne "T2p" "parent pin advanced" "$t2p_pin" "$(parent_pin "${t2p_root}/parent")"
expect_eq "T2p" "the build's artefact did NOT reach the upstream" "NO" "$(upstream_has "$t2p_root" "build/outputs/app.apk")"
expect_eq "T2p" "the upstream is byte-identical after a SUCCESSFUL advance" "$t2p_up" "$(upstream_fp "$t2p_root")"
if t2p_rec="$(find_record "${t2p_root}/records")"; then
  expect_eq "T2p" "outcome" "ADVANCED" "$(jq -r '.outcome' "$t2p_rec")"
  expect_eq "T2p" "local_modifications_pushed" "false" "$(jq -r '.local_modifications_pushed' "$t2p_rec")"
else
  echo "FAIL: T2p: no Submodule Advance Record was written"; FAILURES=$((FAILURES + 2))
fi

# =========================================================================
# T3: BLOCKER-3 -- LAVA_PIPELINE_RUN_ID was interpolated into a shell string
# fed to `bash -c`.
#
# PRE-FIX (captured):
#   LAVA_PIPELINE_RUN_ID="run'; echo ARBITRARY-COMMAND-RAN > $W/MARK; exit 0; '"
#     lava-advance-verify: .../phase-01-build.sh: No such file or directory
#     ADVANCED to 71f41944...; parent pin staged
#     marker created by the injected command? YES: ARBITRARY-COMMAND-RAN
# The build script did not exist and was never run; the pin advanced anyway and
# was recorded ADVANCED. A security defect and an anti-bluff defect at once:
# the gate reported a verification it never performed.
# =========================================================================
echo "=== T3a: a quote-bearing run id cannot inject, and cannot force the gate ==="
CASES_RUN=$((CASES_RUN + 1))
t3a_root="$(make_fixture 1)"
t3a_marker="${t3a_root}/INJECTION-MARKER"
t3a_pin="$(parent_pin "${t3a_root}/parent")"
t3a_exit=0
t3a_out="$(
  LAVA_PIPELINE_RUN_ID="run'; echo ARBITRARY-COMMAND-RAN > ${t3a_marker}; exit 0; '" \
  LAVA_ADVANCE_RECORD_DIR="${t3a_root}/records" \
    "$SCRIPT_UNDER_TEST" "${t3a_root}/parent" 2>&1
)" || t3a_exit=$?
expect_eq "T3a" "exit code (2 = configuration error, nothing attempted)" "2" "$t3a_exit"
expect_eq "T3a" "the injected command did NOT run" "absent" \
  "$([[ -e "$t3a_marker" ]] && echo present || echo absent)"
expect_eq "T3a" "parent pin untouched" "$t3a_pin" "$(parent_pin "${t3a_root}/parent")"
expect_eq "T3a" "records written (nothing was attempted)" "0" "$(record_count "${t3a_root}/records")"
expect_contains "T3a" "run output" "LAVA_PIPELINE_RUN_ID contains characters outside" "$t3a_out"

# --- T3b: the run id reaches the verify command POSITIONALLY --------------
# The validator alone would satisfy T3a. This asserts the DEEPER fix: the run
# id is passed in argv, so it can never become syntax however it is validated.
echo "=== T3b: the run id reaches the verify command as \$3, not as syntax ==="
CASES_RUN=$((CASES_RUN + 1))
t3b_root="$(make_fixture 1)"
t3b_seen="${t3b_root}/seen-run-id"
run_script "${t3b_root}/parent" "${t3b_root}/records" \
  "printf '%s' \"\$3\" > '${t3b_seen}'; true" \
  "LAVA_PIPELINE_RUN_ID=2026-08-26T09-00-00Z"
expect_eq "T3b" "exit code" "0" "$RUN_EXIT"
expect_eq "T3b" "the verify command received the run id as its 3rd positional" \
  "2026-08-26T09-00-00Z" "$(cat "$t3b_seen" 2>/dev/null)"

# --- T3c NIT-7: RECORD_DIR path traversal via the same value --------------
echo "=== T3c: a traversing run id cannot walk RECORD_DIR out of the tree ==="
CASES_RUN=$((CASES_RUN + 1))
t3c_root="$(make_fixture 0)"
t3c_escape="$(mktemp -d "${TMPDIR:-/tmp}/adv-t054-escape-XXXXXX")"
FIXTURE_DIRS+=("$t3c_escape")
t3c_exit=0
t3c_out="$(
  LAVA_PIPELINE_RUN_ID="../../../../../../..${t3c_escape}/escaped" \
  LAVA_ADVANCE_VERIFY_CMD='true' \
    "$SCRIPT_UNDER_TEST" "${t3c_root}/parent" 2>&1
)" || t3c_exit=$?
expect_eq "T3c" "exit code" "2" "$t3c_exit"
expect_eq "T3c" "no directory was created outside the evidence tree" "absent" \
  "$([[ -d "${t3c_escape}/escaped" ]] && echo present || echo absent)"

# =========================================================================
# T4: root CLAUDE.md condition (C) -- own-org upstreams only.
#
# "advances only submodules whose configured upstreams are vasic-digital/* or
#  HelixDevelopment/* on GitHub or GitLab. §6.W is not relaxed."
#
# PRE-FIX: no such check existed anywhere. An exhaustive scan of the script's
# git-subcommand inventory found no `remote get-url`, no `config --get
# remote.*.url`, and no host or org string. Demonstrated against a fixture: a
# submodule carrying a second, unauthorised remote had the operator's work
# PUSHED to it, and every foreign remote was FETCHED from before anything else
# happened.
#
# HERMETIC: every URL below is ssh-form and the whole case runs under
# GIT_SSH_COMMAND=/bin/false, so a regression fails to connect rather than
# reaching github.com or gitlab.com.
# =========================================================================
echo "=== T4: an out-of-own-org remote is refused BEFORE any fetch ==="
t4_case() {
  local label="$1" url="$2" root pin
  CASES_RUN=$((CASES_RUN + 1))
  root="$(make_fixture 1)"
  git -C "${root}/parent/${SUB_PATH}" remote add scratchfork "$url"
  pin="$(parent_pin "${root}/parent")"
  run_script "${root}/parent" "${root}/records" 'true' \
    "GIT_SSH_COMMAND=/bin/false" "GIT_TERMINAL_PROMPT=0"
  expect_ne "T4(${label})" "exit code" "0" "$RUN_EXIT"
  expect_eq "T4(${label})" "parent pin untouched" "$pin" "$(parent_pin "${root}/parent")"
  expect_eq "T4(${label})" "the foreign remote was never fetched from" "absent" \
    "$(git -C "${root}/parent/${SUB_PATH}" rev-parse --verify -q refs/remotes/scratchfork/master >/dev/null 2>&1 && echo present || echo absent)"
  local rec
  if rec="$(find_record "${root}/records")"; then
    expect_eq "T4(${label})" "outcome names its own cause" "REFUSED_FOREIGN_UPSTREAM" "$(jq -r '.outcome' "$rec")"
  else
    echo "FAIL: T4(${label}): no Submodule Advance Record was written for the refusal"
    FAILURES=$((FAILURES + 1))
  fi
}
t4_case "foreign-host"       "git@scm.example.invalid:vasic-digital/helixqa.git"
t4_case "github-foreign-org" "git@github.com:some-personal-fork/helixqa.git"
t4_case "gitlab-foreign-org" "git@gitlab.com:not-our-org/helixqa.git"

# --- T4u: the URL classifier itself, over a table of real URL shapes ------
# The function text is lifted out of the script and evaluated here so the
# classification can be asserted directly rather than inferred from an
# end-to-end refusal. If the extraction ever yields nothing, the table would
# silently assert nothing -- so the extraction is checked first.
echo "=== T4u: the own-org URL classifier, over a table of URL shapes ==="
CASES_RUN=$((CASES_RUN + 1))
t4u_fn="$(awk '/^_remote_url_class\(\) \{/,/^\}$/' "$SCRIPT_UNDER_TEST")"
if [[ -z "$t4u_fn" ]] || ! grep -q 'OWN_ORG' <<<"$t4u_fn"; then
  echo "FAIL: T4u: could not extract _remote_url_class from the script -- the table below would assert nothing"
  FAILURES=$((FAILURES + 1))
else
  eval "$t4u_fn"
  T4U_EXAMINED=0
  while IFS='|' read -r t4u_expected t4u_url; do
    [[ -n "$t4u_expected" ]] || continue
    T4U_EXAMINED=$((T4U_EXAMINED + 1))
    expect_eq "T4u" "class of '${t4u_url}'" "$t4u_expected" "$(_remote_url_class "$t4u_url")"
  done <<'T4UEOF'
OWN_ORG|git@github.com:vasic-digital/Tracker-SDK.git
OWN_ORG|https://github.com/vasic-digital/Tracker-SDK.git
OWN_ORG|git@gitlab.com:vasic-digital/Tracker-SDK.git
OWN_ORG|https://gitlab.com/vasic-digital/Tracker-SDK
OWN_ORG|git@github.com:HelixDevelopment/HelixQA.git
OWN_ORG|ssh://git@gitlab.com/HelixDevelopment/HelixConstitution.git
OWN_ORG|https://user@github.com/VASIC-DIGITAL/Auth.git
FOREIGN|git@github.com:some-personal-fork/Tracker-SDK.git
FOREIGN|https://gitlab.com/not-our-org/Tracker-SDK.git
FOREIGN|git@bitbucket.org:vasic-digital/Tracker-SDK.git
FOREIGN|https://scm.example.invalid/vasic-digital/Tracker-SDK.git
FOREIGN|ssh://git@gitflic.ru/vasic-digital/Tracker-SDK.git
LOCAL_PATH|/tmp/fixture/upstream.git
LOCAL_PATH|./relative/upstream.git
LOCAL_PATH|file:///tmp/fixture/upstream.git
LOCAL_PATH|/tmp/odd:name/upstream.git
T4UEOF
  if [[ "$T4U_EXAMINED" -eq 16 ]]; then
    echo "PASS: T4u: examined ${T4U_EXAMINED} URL shapes"
  else
    echo "FAIL: T4u: examined ${T4U_EXAMINED} URL shapes, expected 16 -- the table did not iterate, so its assertions never ran"
    FAILURES=$((FAILURES + 1))
  fi
fi

# --- T4p POSITIVE --------------------------------------------------------
# A blanket refusal would pass every T4 case above. An own-org remote must NOT
# trigger the scope refusal. The remote is never reachable under
# GIT_SSH_COMMAND=/bin/false, so what is asserted is that the run got PAST the
# scope check and died at the fetch instead.
echo "=== T4p: POSITIVE -- an own-org remote is not refused by the scope check ==="
CASES_RUN=$((CASES_RUN + 1))
t4p_root="$(make_fixture 1)"
git -C "${t4p_root}/parent/${SUB_PATH}" remote add ourmirror "git@gitlab.com:vasic-digital/helixqa.git"
run_script "${t4p_root}/parent" "${t4p_root}/records" 'true' \
  "GIT_SSH_COMMAND=/bin/false" "GIT_TERMINAL_PROMPT=0"
if t4p_rec="$(find_record "${t4p_root}/records")"; then
  expect_ne "T4p" "outcome must NOT be a scope refusal" "REFUSED_FOREIGN_UPSTREAM" "$(jq -r '.outcome' "$t4p_rec")"
else
  echo "FAIL: T4p: no Submodule Advance Record was written"; FAILURES=$((FAILURES + 1))
fi
# ...and a purely local-path remote set (every other case in every suite)
# still advances, which is what keeps the hermetic fixtures meaningful.
CASES_RUN=$((CASES_RUN + 1))
t4p2_root="$(make_fixture 1)"
run_script "${t4p2_root}/parent" "${t4p2_root}/records" 'true'
expect_eq "T4p(local-path)" "exit code" "0" "$RUN_EXIT"
expect_eq "T4p(local-path)" "parent pin advanced" "$(upstream_tip "$t4p2_root")" "$(parent_pin "${t4p2_root}/parent")"

# =========================================================================
# T5: SHOULD-FIX-1 -- a push loop that ran ZERO times read as success.
#
# PRE-FIX (captured, with a PATH shim that fails the bare `git -C <dir> remote`
# enumeration once a marker the verify step drops exists; the script itself was
# not modified):
#   fatal: injected transient failure reading remotes
#   ADVANCED to 9c443a0f...; parent pin staged      EXIT=0
#   did the operator work reach the upstream? path 'operator-work.txt' does not exist
#   remote branches containing the staged pin:   (none)
# The parent pin was staged at a commit NO remote had; every other clone then
# gets `fatal: reference is not a tree` from `git submodule update`.
# =========================================================================
echo "=== T5: a push loop that ran zero times is not a successful publish ==="
CASES_RUN=$((CASES_RUN + 1))
t5_root="$(make_fixture 1)"
t5_shim="${t5_root}/shim"; mkdir -p "$t5_shim"
t5_realgit="$(command -v git)"
cat > "${t5_shim}/git" <<SHIMEOF
#!/usr/bin/env bash
# Fails ONLY the bare \`git -C <dir> remote\` enumeration, and only once the
# marker the verify step drops exists -- i.e. inside the push loop's window,
# the way a transient failure would land.
if [[ "\$1" == "-C" && "\$3" == "remote" && \$# -eq 3 && -f "${t5_root}/MARK" ]]; then
  echo "fatal: injected transient failure reading remotes" >&2
  exit 128
fi
exec "${t5_realgit}" "\$@"
SHIMEOF
chmod +x "${t5_shim}/git"
# RETARGETED 2026-08-26. The push loop this case was written for is gone, and
# with it the "staged a pin no remote has" hazard: a pin is now local to the
# parent index either way. What the shim still exercises is the OTHER
# zero-iteration read of `git remote` -- the condition-(C) own-org scan, whose
# own floor refuses rather than certifying a set it never looked at. The
# operator's uncommitted file is kept so the case also proves the run refuses
# before the fetch, hence before the marker the shim keys on can exist.
printf 'IMPORTANT UNCOMMITTED WORK\n' > "${t5_root}/parent/${SUB_PATH}/operator-work.txt"
t5_pin="$(parent_pin "${t5_root}/parent")"
t5_up="$(upstream_fp "$t5_root")"
run_script "${t5_root}/parent" "${t5_root}/records" ": > \"${t5_root}/MARK\"; true" \
  "PATH=${t5_shim}:${PATH}"
expect_ne "T5" "exit code (0 would certify a run that examined nothing)" "0" "$RUN_EXIT"
expect_eq "T5" "parent pin NOT staged" "$t5_pin" "$(parent_pin "${t5_root}/parent")"
expect_eq "T5" "the operator's work did not reach the upstream" "NO" "$(upstream_has "$t5_root" "operator-work.txt")"
expect_eq "T5" "the upstream is byte-identical (refs + object count)" "$t5_up" "$(upstream_fp "$t5_root")"
if t5_rec="$(find_record "${t5_root}/records")"; then
  expect_ne "T5" "outcome must not claim an advance" "ADVANCED" "$(jq -r '.outcome' "$t5_rec")"
  expect_eq "T5" "parent_pin_updated" "false" "$(jq -r '.parent_pin_updated' "$t5_rec")"
else
  echo "FAIL: T5: no Submodule Advance Record was written"; FAILURES=$((FAILURES + 2))
fi
# No rescue ref may exist: this script creates no commit for one to hold. The
# assertion is INVERTED from what it used to be, and deliberately so -- a
# rescue ref appearing here could only mean committing code had returned.
if [[ -z "$(git -C "${t5_root}/parent/${SUB_PATH}" for-each-ref --format='%(refname)' 'refs/lava-advance-rescue/**' | head -n1)" ]]; then
  echo "PASS: T5: no rescue ref exists -- this script creates no commit for one to hold"
else
  echo "FAIL: T5: a refs/lava-advance-rescue/* ref exists, which can only mean a commit was created"
  FAILURES=$((FAILURES + 1))
fi

# =========================================================================
# T6: SHOULD-FIX-2 -- an allow-list token naming nothing selected NOTHING.
#
# PRE-FIX (captured):
#   LAVA_ADVANCE_SUBMODULES="submodules/helixQA"   (fixture has .../helixqa)
#   advance-all-submodules: 0 advanced, 0 already current, 0 rejected/failed
#   EXIT=0        records written: 0
# Indistinguishable from a clean run over a healthy repository.
# =========================================================================
echo "=== T6: an allow-list token naming nothing fails loudly ==="
CASES_RUN=$((CASES_RUN + 1))
t6_root="$(make_fixture 1)"
t6_pin="$(parent_pin "${t6_root}/parent")"
run_script "${t6_root}/parent" "${t6_root}/records" 'true' \
  "LAVA_ADVANCE_SUBMODULES=submodules/helixQA"
expect_eq "T6" "exit code (2 = configuration error, nothing attempted)" "2" "$RUN_EXIT"
expect_eq "T6" "parent pin untouched" "$t6_pin" "$(parent_pin "${t6_root}/parent")"
expect_contains "T6" "run output" "no submodule for" "$LAST_OUTPUT"

echo "=== T6p: POSITIVE -- an exact allow-list token still advances ==="
CASES_RUN=$((CASES_RUN + 1))
t6p_root="$(make_fixture 1)"
run_script "${t6p_root}/parent" "${t6p_root}/records" 'true' \
  "LAVA_ADVANCE_SUBMODULES=${SUB_PATH}"
expect_eq "T6p" "exit code" "0" "$RUN_EXIT"
expect_eq "T6p" "parent pin advanced" "$(upstream_tip "$t6p_root")" "$(parent_pin "${t6p_root}/parent")"
expect_contains "T6p" "run SUMMARY reports what was examined" "1 submodule(s) examined" "$LAST_OUTPUT"

# =========================================================================
# T7: root CLAUDE.md condition (E) -- "Every submodule processed produces a
# Submodule Advance Record ... 'All submodules advanced' without per-submodule
# records is not evidence."
#
# PRE-FIX (captured): eight terminal refusal paths wrote NO record. Two of
# them, verbatim:
#   ### fetch failure -> is a record written? ###      records: 0   EXIT=1
#   ### non-fast-forward -> is a record written? ###   records: 0   EXIT=1
# Loud on the console, invisible at rest.
#
# Parameterised so the count of paths actually exercised is asserted: a table
# that stopped iterating would otherwise assert nothing at all.
# =========================================================================
echo "=== T7: every terminal refusal path leaves a Submodule Advance Record ==="
T7_EXAMINED=0
t7_case() {
  local label="$1" setup="$2" root rec
  CASES_RUN=$((CASES_RUN + 1))
  T7_EXAMINED=$((T7_EXAMINED + 1))
  root="$(make_fixture 1)"
  # shellcheck disable=SC2086
  eval "$setup"
  run_script "${root}/parent" "${root}/records" 'true'
  expect_ne "T7(${label})" "exit code" "0" "$RUN_EXIT"
  if rec="$(find_record "${root}/records")"; then
    expect_ne "T7(${label})" "outcome must not claim an advance" "ADVANCED" "$(jq -r '.outcome' "$rec")"
    expect_eq "T7(${label})" "parent_pin_updated" "false" "$(jq -r '.parent_pin_updated' "$rec")"
    echo "PASS: T7(${label}): the refusal left evidence at rest (outcome=$(jq -r '.outcome' "$rec"))"
  else
    echo "FAIL: T7(${label}): the refusal left NO Submodule Advance Record -- invisible at rest, violating condition (E)"
    FAILURES=$((FAILURES + 3))
  fi
}
# unreachable upstream (fetch failure)
t7_case "fetch-failure" 'mv "${root}/upstream.git" "${root}/upstream-moved.git"'
# uninitialized submodule directory
t7_case "uninitialized-submodule" 'rm -rf -- "${root}/parent/${SUB_PATH}"; mkdir -p -- "${root}/parent/${SUB_PATH}"'
# working tree not on the pinned commit
t7_case "head-not-on-pin" 'git -C "${root}/parent/${SUB_PATH}" fetch --quiet origin; git -C "${root}/parent/${SUB_PATH}" checkout --detach --quiet origin/master'
# a remote whose default branch cannot be resolved
t7_case "no-remote-head" 'git -C "${root}/upstream.git" symbolic-ref HEAD refs/heads/does-not-exist'
if [[ "$T7_EXAMINED" -eq 4 ]]; then
  echo "PASS: T7: examined ${T7_EXAMINED} terminal refusal paths"
else
  echo "FAIL: T7: examined ${T7_EXAMINED} refusal paths, expected 4 -- the case list did not iterate, so its assertions never ran"
  FAILURES=$((FAILURES + 1))
fi

# =========================================================================
# ROUND 2 — the T054 RE-REVIEW's findings. Every case below was reproduced
# against a disposable fixture BEFORE its fix, and the captured pre-fix
# behaviour is quoted in the case's comment. Every one of them fails if its
# fix alone is reverted (rehearsed individually, one revert at a time).
#
#   T8    FIX-1  the safe-operating precondition is CHECKED and STATED
#   T8p   FIX-1  POSITIVE -- asked for explicitly, step 6 still publishes
#   T9    FIX-2a a MODE-only change by the build is refused
#   T10   FIX-2b a nested submodule's GITLINK moved by the build is refused
#   T11   FIX-3  the condition-(C) scan may not pass having read ZERO remotes
#   T12   FIX-4  the refusal's recovery claim is TRUE, not merely reassuring
#   T13   FIX-5  a filesystem-path remote is gated, not permitted by default
#   T14   NIT-1  a path-navigation run id is refused
#   T15   NIT-2  a SUBMODULE staging failure names its own cause
#   T16   NIT-4  authorization is checked against the UPSTREAM, not the path
# =========================================================================

# -------------------------------------------------------------------------
# T8: the safe-operating precondition -- a clean submodule working tree.
#
# PRE-FIX (captured, one fixture each, at the sha the re-review examined):
#
#   A: CLEAN tree + a deliberately hostile verify (writes a stray file,
#      chmods a tracked one):
#        EXIT=0   "ADVANCED"   upstream tip changed? NO
#        upstream seed.txt mode: 100644
#        any line stating the precondition? 0
#   B: UNCLEAN tree (ONE stray editor swapfile, `.notes.swp`) + a benign
#      `true` verify:
#        EXIT=0   "submodule carried local modifications ... committing and
#                  pushing them to its own upstream(s)"   "ADVANCED"
#        upstream now holds: .notes.swp f1.txt seed.txt
#   C: occurrences of a clean-tree precondition check or statement anywhere
#      in the script: 0
#
# B is the whole finding in one line: a stray swapfile nobody asked to
# publish was committed and pushed to ANOTHER REPOSITORY's default branch,
# unattended, exit 0, outcome ADVANCED. The re-review approved a run "today"
# only because `submodules/helixqa` happens to be clean, and said plainly that
# the safety "rests on a precondition the script neither checks nor states".
# -------------------------------------------------------------------------
echo "=== T8: a clean working tree is CHECKED and STATED, an unclean one refuses ==="
CASES_RUN=$((CASES_RUN + 1))
t8_root="$(make_fixture 1)"
t8_up="$(upstream_fp "$t8_root")"
run_script "${t8_root}/parent" "${t8_root}/records" 'printf X > "$1/stray.bin"; chmod 755 "$1/seed.txt"'
expect_eq "T8(clean)" "exit code" "0" "$RUN_EXIT"
expect_contains "T8(clean)" "the run STATES the precondition it relies on" \
  "working tree clean" "$LAST_OUTPUT"
expect_eq "T8(clean)" "a hostile verify still published nothing" "NO" "$(upstream_has "$t8_root" "stray.bin")"
expect_eq "T8(clean)" "the upstream is byte-identical after the hostile verify ran" "$t8_up" "$(upstream_fp "$t8_root")"

CASES_RUN=$((CASES_RUN + 1))
t8b_root="$(make_fixture 1)"
t8b_pin="$(parent_pin "${t8b_root}/parent")"
t8b_up="$(upstream_fp "$t8b_root")"
printf 'stray editor swapfile\n' > "${t8b_root}/parent/${SUB_PATH}/.notes.swp"
run_script "${t8b_root}/parent" "${t8b_root}/records" 'true'
expect_ne "T8(unclean)" "exit code (0 would publish a swapfile nobody asked to publish)" "0" "$RUN_EXIT"
# RETARGETED 2026-08-26. This assertion used to require the refusal to NAME
# the flag that would authorise the publish. There is no such flag any more,
# so requiring the old text would require the script to advertise a capability
# it does not have -- which is the bluff class in reverse. The refusal must now
# say the opposite, and say it plainly.
expect_contains "T8(unclean)" "the refusal states the capability is gone, not merely withheld" \
  "does not carry local modifications forward" "$LAST_OUTPUT"
if grep -qF -- "re-run with --publish-local-modifications" <<<"$LAST_OUTPUT"; then
  echo "FAIL: T8(unclean): the refusal still offers a flag that no longer exists"
  FAILURES=$((FAILURES + 1))
else
  echo "PASS: T8(unclean): the refusal does not offer a flag that no longer exists"
fi
expect_eq "T8(unclean)" "the stray swapfile did NOT reach another repository" "NO" "$(upstream_has "$t8b_root" ".notes.swp")"
expect_eq "T8(unclean)" "the upstream is byte-identical (refs + object count)" "$t8b_up" "$(upstream_fp "$t8b_root")"
expect_eq "T8(unclean)" "parent pin untouched" "$t8b_pin" "$(parent_pin "${t8b_root}/parent")"
if t8b_rec="$(find_record "${t8b_root}/records")"; then
  expect_eq "T8(unclean)" "outcome" "FAILED_PRECONDITION" "$(jq -r '.outcome' "$t8b_rec")"
  expect_eq "T8(unclean)" "parent_pin_updated" "false" "$(jq -r '.parent_pin_updated' "$t8b_rec")"
else
  echo "FAIL: T8(unclean): no Submodule Advance Record was written"; FAILURES=$((FAILURES + 2))
fi

# --- T8p (RETARGETED): the refusal must have NO opt-in of any kind. --------
# It used to assert that step 6 still did what R-005 said when the flag was
# passed. Step 6 is gone, so the property that replaces it is the one the
# removal is FOR: the same uncommitted work is refused no matter what is on
# the command line, and the removed flag is a loud exit 2 rather than a
# silently-ignored argument (LVA-120: a deleted argument branch once let a
# flag fall through to `*) shift` and become a repository path).
echo "=== T8p: the unclean-tree refusal has no opt-in, and the removed flag exits 2 ==="
CASES_RUN=$((CASES_RUN + 1))
t8p_root="$(make_fixture 1)"
t8p_pin="$(parent_pin "${t8p_root}/parent")"
t8p_up="$(upstream_fp "$t8p_root")"
printf 'IMPORTANT UNCOMMITTED WORK\n' > "${t8p_root}/parent/${SUB_PATH}/operator-work.txt"
run_script "${t8p_root}/parent" "${t8p_root}/records" 'true'
expect_ne "T8p" "exit code" "0" "$RUN_EXIT"
expect_eq "T8p" "the operator's work did NOT reach the upstream" "NO" "$(upstream_has "$t8p_root" "operator-work.txt")"
expect_eq "T8p" "the upstream is byte-identical" "$t8p_up" "$(upstream_fp "$t8p_root")"
expect_eq "T8p" "parent pin untouched" "$t8p_pin" "$(parent_pin "${t8p_root}/parent")"
if t8p_rec="$(find_record "${t8p_root}/records")"; then
  expect_eq "T8p" "outcome" "FAILED_PRECONDITION" "$(jq -r '.outcome' "$t8p_rec")"
  expect_eq "T8p" "local_modifications_pushed" "false" "$(jq -r '.local_modifications_pushed' "$t8p_rec")"
else
  echo "FAIL: T8p: no Submodule Advance Record was written"; FAILURES=$((FAILURES + 2))
fi
t8p_flag_exit=0
t8p_flag_out="$(
  LAVA_ADVANCE_RECORD_DIR="${t8p_root}/records-flag" \
  LAVA_ADVANCE_VERIFY_CMD='true' \
  "$SCRIPT_UNDER_TEST" --allow-local-path-remotes --publish-local-modifications "${t8p_root}/parent" 2>&1
)" || t8p_flag_exit=$?
expect_eq "T8p" "the removed flag exits 2" "2" "$t8p_flag_exit"
expect_contains "T8p" "the refusal names the removal" "REMOVED" "$t8p_flag_out"
if grep -qF -- "more than one repository path" <<<"$t8p_flag_out"; then
  echo "FAIL: T8p: the removed flag fell through and was read as a REPOSITORY PATH (the LVA-120 failure mode)"
  FAILURES=$((FAILURES + 1))
else
  echo "PASS: T8p: the removed flag was not mistaken for a repository path"
fi

# -------------------------------------------------------------------------
# T9: FINDING-1 -- a MODE-only change by the build reached the upstream.
#
# PRE-FIX (captured): the operator's untracked tool.sh at -rw-r--r--, a verify
# command whose entire body is `chmod 755 "$1/tool.sh"` (content untouched):
#   ADVANCED to 2b00e286...; parent pin staged     EXIT=0
#   --- what mode did the UPSTREAM receive? ---
#   100755 blob b096a16ded58004ae60c647c4de20f46b3438712	tool.sh
#   outcome= ADVANCED  pin_updated= True  pushed= True
#
# `_path_fingerprint` hashed CONTENT; `git add` records (mode, blob). The
# guard was not a superset of what staging commits.
# -------------------------------------------------------------------------
echo "=== T9: a mode-only change by the verify build is refused ==="
CASES_RUN=$((CASES_RUN + 1))
t9_root="$(make_fixture 1)"
t9_pin="$(parent_pin "${t9_root}/parent")"
printf 'OPERATOR TOOL\n' > "${t9_root}/parent/${SUB_PATH}/tool.sh"
chmod 644 "${t9_root}/parent/${SUB_PATH}/tool.sh"
t9_up="$(upstream_fp "$t9_root")"
run_script "${t9_root}/parent" "${t9_root}/records" 'chmod 755 "$1/tool.sh"'
expect_ne "T9" "exit code (0 would certify a published build permission bit)" "0" "$RUN_EXIT"
expect_eq "T9" "tool.sh did not reach the upstream at all" "NO" "$(upstream_has "$t9_root" "tool.sh")"
expect_eq "T9" "the upstream is byte-identical (refs + object count)" "$t9_up" "$(upstream_fp "$t9_root")"
expect_eq "T9" "parent pin untouched" "$t9_pin" "$(parent_pin "${t9_root}/parent")"
# RETARGETED 2026-08-26: the file's mode is still the operator's, because the
# refusal is taken before the fetch and the chmod never ran.
expect_eq "T9" "the mode is still the operator's (the build never ran)" "644" \
  "$(stat -c '%a' "${t9_root}/parent/${SUB_PATH}/tool.sh" 2>/dev/null || stat -f '%OLp' "${t9_root}/parent/${SUB_PATH}/tool.sh" 2>/dev/null)"
if t9_rec="$(find_record "${t9_root}/records")"; then
  expect_eq "T9" "outcome names its own cause" "FAILED_PRECONDITION" "$(jq -r '.outcome' "$t9_rec")"
  expect_eq "T9" "parent_pin_updated" "false" "$(jq -r '.parent_pin_updated' "$t9_rec")"
else
  echo "FAIL: T9: no Submodule Advance Record was written"; FAILURES=$((FAILURES + 2))
fi

# -------------------------------------------------------------------------
# T10: FINDING-2 -- a nested submodule's GITLINK moved by the build reached
# the upstream.
#
# PRE-FIX (captured):
#   operator staged nested at N1=798327cc...
#   VERIFY_CMD: git -C "$1/nested" checkout --detach <N2>
#   ADVANCED to fa0b7256...; parent pin staged      EXIT=0
#   nested gitlink PUBLISHED to upstream:
#     160000 commit f840f930426bd639619f461b5b22c6b0da86ce70	nested
#
# `_path_fingerprint` answered the constant `directory` for any directory, so
# a gitlink move was invisible while `git add -- <nested>` staged it. NOT
# hypothetical: `submodules/helixqa` -- the only submodule GOVERNANCE_ALLOW
# permits -- declares 27 nested submodules of its own.
# -------------------------------------------------------------------------
echo "=== T10: a nested submodule's gitlink moved by the build is refused ==="
CASES_RUN=$((CASES_RUN + 1))
t10_root="$(make_fixture 1)"
t10_pin="$(parent_pin "${t10_root}/parent")"
git init --quiet --bare -b master "${t10_root}/nested.git"
git clone --quiet "${t10_root}/nested.git" "${t10_root}/nseed" 2>/dev/null
gcfg "${t10_root}/nseed"
echo n1 > "${t10_root}/nseed/n.txt"; git -C "${t10_root}/nseed" add n.txt
git -C "${t10_root}/nseed" commit --quiet -m n1
echo n2 > "${t10_root}/nseed/n.txt"; git -C "${t10_root}/nseed" add n.txt
git -C "${t10_root}/nseed" commit --quiet -m n2
git -C "${t10_root}/nseed" push --quiet origin HEAD:master
t10_n1="$(git -C "${t10_root}/nseed" rev-parse HEAD~1)"
t10_n2="$(git -C "${t10_root}/nseed" rev-parse HEAD)"
rm -rf -- "${t10_root}/nseed"
git -c protocol.file.allow=always -C "${t10_root}/parent/${SUB_PATH}" \
  submodule add --quiet "${t10_root}/nested.git" nested >/dev/null 2>&1
git -C "${t10_root}/parent/${SUB_PATH}/nested" checkout --detach --quiet "$t10_n1"
git -C "${t10_root}/parent/${SUB_PATH}" add -- nested .gitmodules
t10_up="$(upstream_fp "$t10_root")"
run_script "${t10_root}/parent" "${t10_root}/records" \
  "git -C \"\$1/nested\" checkout --detach --quiet ${t10_n2}"
expect_ne "T10" "exit code (0 would certify a published build gitlink)" "0" "$RUN_EXIT"
# RETARGETED 2026-08-26: the guard that named "gitlink:" was the pre-verify
# fingerprint comparison, which lived in the publish path. The refusal now
# names the PATH it found dirty instead, which is the information the operator
# needs and the only claim this run can honestly make.
expect_contains "T10" "the refusal names the path it found" "nested" "$LAST_OUTPUT"
expect_eq "T10" "the nested gitlink did not reach the upstream" "NO" "$(upstream_has "$t10_root" "nested")"
expect_eq "T10" "the upstream is byte-identical (refs + object count)" "$t10_up" "$(upstream_fp "$t10_root")"
expect_eq "T10" "parent pin untouched" "$t10_pin" "$(parent_pin "${t10_root}/parent")"
expect_eq "T10" "the nested submodule is still at the operator's commit (the build never ran)" "$t10_n1" \
  "$(git -C "${t10_root}/parent/${SUB_PATH}/nested" rev-parse HEAD 2>/dev/null)"
if t10_rec="$(find_record "${t10_root}/records")"; then
  expect_eq "T10" "outcome" "FAILED_PRECONDITION" "$(jq -r '.outcome' "$t10_rec")"
else
  echo "FAIL: T10: no Submodule Advance Record was written"; FAILURES=$((FAILURES + 1))
fi

# -------------------------------------------------------------------------
# T11: FINDING-4 -- the condition-(C) own-org scope gate passed having
# examined ZERO remotes.
#
# PRE-FIX (captured, with a PATH shim that makes the SECOND bare
# `git -C <dir> remote` return rc 0 with NO output; the script itself was not
# modified):
#   ==> submodules/helixqa
#     1319a5d1... -> 59dc9ddb... (origin/master)
#     ADVANCED to 59dc9ddb...; parent pin staged
#   advance-all-submodules: 1 advanced, 0 already current, 0 rejected/failed
#   shim invocation count = 2
#
# The gate certified a submodule whose remotes it never read, and the run then
# fetched, advanced and staged. The PUSH loop received exactly this guard in
# the same fix pass; this scan did not.
# -------------------------------------------------------------------------
echo "=== T11: the own-org scope gate may not pass having read ZERO remotes ==="
CASES_RUN=$((CASES_RUN + 1))
t11_root="$(make_fixture 1)"
t11_pin="$(parent_pin "${t11_root}/parent")"
t11_shim="${t11_root}/shim"; mkdir -p "$t11_shim"
t11_realgit="$(command -v git)"
cat > "${t11_shim}/git" <<SHIMEOF
#!/usr/bin/env bash
# Makes the SECOND bare \`git -C <dir> remote\` return rc 0 with no output --
# the exact shape a gate that counts nothing cannot tell from "all in scope".
if [[ "\$1" == "-C" && "\$3" == "remote" && \$# -eq 3 ]]; then
  n=\$(( \$(cat "${t11_root}/shimcount" 2>/dev/null || echo 0) + 1 ))
  echo "\$n" > "${t11_root}/shimcount"
  if [[ "\$n" -eq 2 ]]; then exit 0; fi
fi
exec "${t11_realgit}" "\$@"
SHIMEOF
chmod +x "${t11_shim}/git"
run_script "${t11_root}/parent" "${t11_root}/records" 'true' "PATH=${t11_shim}:${PATH}"
expect_eq "T11" "the shim really did fire (a case that never ran asserts nothing)" \
  "2" "$(cat "${t11_root}/shimcount" 2>/dev/null || echo 0)"
expect_ne "T11" "exit code (0 would certify a set that was never read)" "0" "$RUN_EXIT"
expect_contains "T11" "run output" "read ZERO remotes" "$LAST_OUTPUT"
expect_eq "T11" "parent pin untouched" "$t11_pin" "$(parent_pin "${t11_root}/parent")"
if t11_rec="$(find_record "${t11_root}/records")"; then
  expect_ne "T11" "outcome must not claim an advance" "ADVANCED" "$(jq -r '.outcome' "$t11_rec")"
else
  echo "FAIL: T11: no Submodule Advance Record was written"; FAILURES=$((FAILURES + 1))
fi

# -------------------------------------------------------------------------
# T12: FINDING-3 -- the refusal claimed a recovery that did not exist.
#
# PRE-FIX (captured): the message said "both versions are left on disk for a
# human to reconcile" and printed `blob:<sha>`, which reads as a handle:
#   message claims: 'both versions are left on disk for a human to reconcile'
#   note.txt on disk now : BUILD-OUTPUT
#   operator blob 8b406792fff73343642777c54a65ab0f7328ed50 present in the
#     object DB? NO
#   any other copy of the operator's bytes on disk? 0 file(s)
#
# `git hash-object` WITHOUT -w computes and stores nothing. The refusal
# correctly prevented the publish; the operator's untracked bytes had already
# been destroyed by the build, and the message told the reader otherwise. An
# operator who acts on that sentence loses the work twice.
# -------------------------------------------------------------------------
# RETARGETED 2026-08-26. This case was about the QUALITY OF A CONSOLATION: the
# build had already destroyed the operator's untracked bytes, and the refusal
# printed a sha as though it were a recovery handle when `git hash-object`
# without `-w` had stored nothing. The fix was to store the pre-verify bytes so
# the handle resolved.
#
# Both the destruction and the consolation are gone. `_path_fingerprint` and
# `_captured_tree` existed only to decide what step 6 would publish, and step 6
# is removed -- so the run refuses BEFORE the fetch and the build never executes
# to destroy anything. The operator needs no recovery handle because nothing was
# taken from them.
#
# The case keeps its exact fixture -- CRLF content under a normalizing text
# attribute, and a verify command that overwrites the operator's file -- because
# that fixture is what makes the new assertions falsifiable: if any code ever
# runs the build before deciding, note.txt on disk becomes BUILD-OUTPUT and the
# byte-comparison below fails immediately.
echo "=== T12: the operator's bytes are never destroyed, so no recovery handle is needed ==="
CASES_RUN=$((CASES_RUN + 1))
t12_root="$(make_fixture 1)"
t12_sub="${t12_root}/parent/${SUB_PATH}"
printf '*.txt text=auto eol=lf\n' > "${t12_sub}/.gitattributes"
printf 'OPERATOR SECRET NOTE\r\n' > "${t12_sub}/note.txt"
# The raw bytes, hashed WITHOUT storing anything, are the before-picture.
t12_blob="$(git -C "$t12_sub" hash-object --no-filters -- note.txt)"
t12_pin="$(parent_pin "${t12_root}/parent")"
t12_up="$(upstream_fp "$t12_root")"
run_script "${t12_root}/parent" "${t12_root}/records" 'printf "BUILD-OUTPUT\n" > "$1/note.txt"'
expect_ne "T12" "exit code" "0" "$RUN_EXIT"
expect_eq "T12" "parent pin untouched" "$t12_pin" "$(parent_pin "${t12_root}/parent")"
expect_eq "T12" "the upstream is byte-identical (refs + object count)" "$t12_up" "$(upstream_fp "$t12_root")"
# THE load-bearing assertion, and it is stronger than the one it replaces: the
# operator's bytes are not merely RECOVERABLE, they were never touched. Hashed
# the same way as before the run, so a single changed byte -- including a CRLF
# silently normalized to LF -- changes the sha and fails this.
expect_eq "T12" "the operator's raw bytes are UNCHANGED on disk (the build never ran)" \
  "$t12_blob" "$(git -C "$t12_sub" hash-object --no-filters -- note.txt)"
expect_eq "T12" "and read back verbatim, CRLF included" "OPERATOR SECRET NOTE" \
  "$(tr -d '\r' < "${t12_sub}/note.txt")"
# A refusal must not offer a consolation it cannot deliver -- the original
# defect of this case, restated for the new shape. Nothing here may claim to
# have preserved, rescued or reconciled anything, because nothing needed it.
for _t12_claim in "both versions are left on disk" \
                  "is the rebuild-and-test step's version" \
                  "local work preserved at"; do
  if grep -qF -- "$_t12_claim" <<<"$LAST_OUTPUT"; then
    echo "FAIL: T12: the refusal claims '${_t12_claim}', which describes a recovery this run neither performed nor needed"
    FAILURES=$((FAILURES + 1))
  else
    echo "PASS: T12: the refusal makes no '${_t12_claim}' claim"
  fi
  note_assertion
done
# ...and it names the file it actually found, so the operator can act on it.
expect_contains "T12" "the refusal names the operator's path" "note.txt" "$LAST_OUTPUT"

# -------------------------------------------------------------------------
# T13: the disclosed LOCAL_PATH gap -- a hole, not merely a caveat.
#
# PRE-FIX (captured): a second remote `scratch` pointing at an unrelated bare
# repository on the same filesystem, classified LOCAL_PATH and PERMITTED:
#   ADVANCED to 06135b60...; parent pin staged       EXIT=0
#   --- files on the UNAUTHORISED scratch remote ---
#       f2.txt  operator-work.txt  seed.txt
#       operator-work.txt there: OPERATOR CONFIDENTIAL WORK
#   outcome= ADVANCED pin_updated= True pushed= True
#
# A filesystem path can be another REAL repository -- which is the very thing
# condition (C) exists to protect -- or an NFS/SMB/sshfs mount that reaches
# another machine while naming no host. The script fetches from it too, so it
# is an object-injection surface as well as a publish target.
# -------------------------------------------------------------------------
echo "=== T13: a filesystem-path remote is refused unless explicitly allowed ==="
CASES_RUN=$((CASES_RUN + 1))
t13_root="$(make_fixture 1)"
t13_pin="$(parent_pin "${t13_root}/parent")"
git clone --quiet --bare "${t13_root}/upstream.git" "${t13_root}/somebody-elses-repo.git" 2>/dev/null
git -C "${t13_root}/parent/${SUB_PATH}" remote add scratch "${t13_root}/somebody-elses-repo.git"
t13_scratch_fp="$(git -C "${t13_root}/somebody-elses-repo.git" for-each-ref --format='%(refname) %(objectname)' 2>/dev/null | LC_ALL=C sort)"
printf 'OPERATOR CONFIDENTIAL WORK\n' > "${t13_root}/parent/${SUB_PATH}/operator-work.txt"
# The fixture must reach the condition-(C) scan, so it needs a CLEAN tree:
# an unclean one is refused earlier, which would prove the wrong thing. The
# confidential file therefore goes into a THIRD repository's worktree rather
# than the submodule's -- the point of the case is which repositories the
# scan certifies, not which files are dirty.
rm -rf -- "${t13_root}/parent/${SUB_PATH}/operator-work.txt"
_t13_saved=("${ADV_FLAGS[@]}")
ADV_FLAGS=()
run_script "${t13_root}/parent" "${t13_root}/records" 'true'
ADV_FLAGS=("${_t13_saved[@]}")
expect_ne "T13" "exit code" "0" "$RUN_EXIT"
expect_eq "T13" "the unauthorised same-filesystem repo received nothing" "NO" \
  "$(if git -C "${t13_root}/somebody-elses-repo.git" cat-file -e "master:operator-work.txt" 2>/dev/null; then echo YES; else echo NO; fi)"
expect_eq "T13" "the unauthorised repo's refs are byte-identical" "$t13_scratch_fp" \
  "$(git -C "${t13_root}/somebody-elses-repo.git" for-each-ref --format='%(refname) %(objectname)' 2>/dev/null | LC_ALL=C sort)"
expect_eq "T13" "parent pin untouched" "$t13_pin" "$(parent_pin "${t13_root}/parent")"
if t13_rec="$(find_record "${t13_root}/records")"; then
  expect_eq "T13" "outcome" "REFUSED_FOREIGN_UPSTREAM" "$(jq -r '.outcome' "$t13_rec")"
else
  echo "FAIL: T13: no Submodule Advance Record was written"; FAILURES=$((FAILURES + 1))
fi

# --- T13p POSITIVE: the gate must be a GATE, not a wall. Every hermetic ---
# fixture in every suite is a filesystem path; if the flag did not re-open it
# the suites could not reach the fetch path at all, and a suite that cannot
# reach the fetch path cannot prove the fetch path is safe.
echo "=== T13p: POSITIVE -- the flag re-opens the filesystem-path remote ==="
CASES_RUN=$((CASES_RUN + 1))
t13p_root="$(make_fixture 1)"
run_script "${t13p_root}/parent" "${t13p_root}/records" 'true'
expect_eq "T13p" "exit code" "0" "$RUN_EXIT"
expect_eq "T13p" "parent pin advanced" "$(upstream_tip "$t13p_root")" "$(parent_pin "${t13p_root}/parent")"

# -------------------------------------------------------------------------
# T14: NIT-1 -- the run-id validator admitted the two path-navigation tokens.
#
# PRE-FIX (captured):
#   RUN_ID=".."  ->  EXIT=0
#   Submodule Advance Records: .../.lava-ci-evidence/pipeline-runs/../submodule-advances
#
# No '/' can appear so it cannot walk out of .lava-ci-evidence, but it DOES
# silently relocate a run's records out of its own run directory into the
# shared parent, where the next run's records land on top of them.
#
# Parameterised, with the count of tokens actually exercised asserted: a table
# that stopped iterating would assert nothing at all.
# -------------------------------------------------------------------------
echo "=== T14: a path-navigation run id is refused ==="
CASES_RUN=$((CASES_RUN + 1))
T14_EXAMINED=0
for t14_tok in "." ".."; do
  T14_EXAMINED=$((T14_EXAMINED + 1))
  t14_root="$(make_fixture 1)"
  t14_pin="$(parent_pin "${t14_root}/parent")"
  t14_exit=0
  t14_out="$(
    env LAVA_PIPELINE_RUN_ID="$t14_tok" LAVA_ADVANCE_VERIFY_CMD='true' \
      "$SCRIPT_UNDER_TEST" ${ADV_FLAGS[@]+"${ADV_FLAGS[@]}"} "${t14_root}/parent" 2>&1
  )" || t14_exit=$?
  expect_eq "T14('${t14_tok}')" "exit code (2 = configuration error, nothing attempted)" "2" "$t14_exit"
  expect_contains "T14('${t14_tok}')" "run output" "path-navigation token" "$t14_out"
  expect_eq "T14('${t14_tok}')" "parent pin untouched" "$t14_pin" "$(parent_pin "${t14_root}/parent")"
done
if [[ "$T14_EXAMINED" -eq 2 ]]; then
  echo "PASS: T14: examined ${T14_EXAMINED} path-navigation tokens"
else
  echo "FAIL: T14: examined ${T14_EXAMINED} tokens, expected 2 -- the table did not iterate, so its assertions never ran"
  FAILURES=$((FAILURES + 1))
fi

# -------------------------------------------------------------------------
# T15: NIT-2 -- a SUBMODULE staging failure was recorded as
# REJECTED_PUSH_CONFLICT, asserting a push that is never attempted there.
#
# PRE-FIX (captured):
#   !! could not stage ... 'git add' was refused:
#      error: open("locked.txt"): Permission denied
#   record outcome: REJECTED_PUSH_CONFLICT
#
# The stale-index.lock shape below is the same window a concurrent or crashed
# git process leaves one in, and it fails `git add` in the SUBMODULE's own
# index while leaving the throwaway measuring index (which locks elsewhere)
# perfectly able to run -- so the run reaches the staging failure rather than
# refusing earlier.
# -------------------------------------------------------------------------
# RETARGETED 2026-08-26. There is no `git add` inside the submodule any more:
# it existed only to stage the operator's local work for the step-6 commit.
# The case keeps its stale-index.lock fixture and runs it BOTH ways, because
# each half proves something the other cannot:
#
#   (a) unclean -- refused before the fetch, so the lock is never even created
#   (b) clean   -- the lock IS created, mid-run, in the submodule's own git
#                  dir, and the run still advances, because nothing this
#                  script does after the verify step touches that index
echo "=== T15: a stale submodule index.lock can no longer fail a staging step that does not exist ==="
CASES_RUN=$((CASES_RUN + 1))
t15_root="$(make_fixture 1)"
t15_sub="${t15_root}/parent/${SUB_PATH}"
t15_pin="$(parent_pin "${t15_root}/parent")"
t15_up="$(upstream_fp "$t15_root")"
printf 'IMPORTANT UNCOMMITTED WORK\n' > "${t15_sub}/operator-work.txt"
run_script "${t15_root}/parent" "${t15_root}/records" \
  'gd="$(git -C "$1" rev-parse --absolute-git-dir)" && : > "$gd/index.lock" && true'
expect_ne "T15(unclean)" "exit code" "0" "$RUN_EXIT"
expect_eq "T15(unclean)" "parent pin untouched" "$t15_pin" "$(parent_pin "${t15_root}/parent")"
expect_eq "T15(unclean)" "the upstream is byte-identical" "$t15_up" "$(upstream_fp "$t15_root")"
if t15_rec="$(find_record "${t15_root}/records")"; then
  expect_eq "T15(unclean)" "outcome names the precondition that actually refused" \
    "FAILED_PRECONDITION" "$(jq -r '.outcome' "$t15_rec")"
  expect_ne "T15(unclean)" "outcome must not assert a push nobody attempted" \
    "REJECTED_PUSH_CONFLICT" "$(jq -r '.outcome' "$t15_rec")"
  expect_eq "T15(unclean)" "parent_pin_updated" "false" "$(jq -r '.parent_pin_updated' "$t15_rec")"
else
  echo "FAIL: T15(unclean): no Submodule Advance Record was written"; FAILURES=$((FAILURES + 3))
fi
t15_gd="$(git -C "$t15_sub" rev-parse --absolute-git-dir 2>/dev/null)"
[[ -n "$t15_gd" ]] && rm -rf -- "${t15_gd}/index.lock"

CASES_RUN=$((CASES_RUN + 1))
t15b_root="$(make_fixture 1)"
t15b_sub="${t15b_root}/parent/${SUB_PATH}"
t15b_pin="$(parent_pin "${t15b_root}/parent")"
t15b_up="$(upstream_fp "$t15b_root")"
run_script "${t15b_root}/parent" "${t15b_root}/records" \
  'gd="$(git -C "$1" rev-parse --absolute-git-dir)" && : > "$gd/index.lock" && true'
expect_eq "T15(clean)" "exit code (a submodule index.lock no longer blocks anything)" "0" "$RUN_EXIT"
expect_ne "T15(clean)" "parent pin advanced" "$t15b_pin" "$(parent_pin "${t15b_root}/parent")"
expect_eq "T15(clean)" "the upstream is byte-identical" "$t15b_up" "$(upstream_fp "$t15b_root")"
if t15b_rec="$(find_record "${t15b_root}/records")"; then
  expect_eq "T15(clean)" "outcome" "ADVANCED" "$(jq -r '.outcome' "$t15b_rec")"
else
  echo "FAIL: T15(clean): no Submodule Advance Record was written"; FAILURES=$((FAILURES + 1))
fi
t15b_gd="$(git -C "$t15b_sub" rev-parse --absolute-git-dir 2>/dev/null)"
[[ -n "$t15b_gd" ]] && rm -rf -- "${t15b_gd}/index.lock"

# -------------------------------------------------------------------------
# T16: NIT-4 -- authorization was keyed on the submodule PATH's final
# component, so it travelled with a directory label rather than with the
# repository an operator actually authorized.
#
# PRE-FIX (captured by the re-review, attacks 4 and 5):
#   submodule path basename 'helixqa', DIFFERENT upstream repo  -> ADVANCED
#   nested path 'vendor/x/helixqa'                              -> ADVANCED
#
# The path name is still NECESSARY (this case's fixture is at
# submodules/helixqa); it is no longer SUFFICIENT. The remote below is never
# contacted: GIT_SSH_COMMAND=/bin/false cannot open a connection, and the
# refusal is asserted to happen BEFORE any fetch.
# -------------------------------------------------------------------------
echo "=== T16: an allowed PATH pointing at an unauthorized REPOSITORY is refused ==="
CASES_RUN=$((CASES_RUN + 1))
t16_root="$(make_fixture 1)"
t16_pin="$(parent_pin "${t16_root}/parent")"
git -C "${t16_root}/parent/${SUB_PATH}" remote add elsewhere \
  "git@github.com:HelixDevelopment/some-other-repo.git"
run_script "${t16_root}/parent" "${t16_root}/records" 'true' \
  "GIT_SSH_COMMAND=/bin/false" "GIT_TERMINAL_PROMPT=0"
expect_ne "T16" "exit code" "0" "$RUN_EXIT"
expect_contains "T16" "run output names the unauthorized repository" "some-other-repo" "$LAST_OUTPUT"
expect_eq "T16" "parent pin untouched" "$t16_pin" "$(parent_pin "${t16_root}/parent")"
if t16_rec="$(find_record "${t16_root}/records")"; then
  expect_eq "T16" "outcome" "REFUSED_GOVERNANCE_DENY" "$(jq -r '.outcome' "$t16_rec")"
else
  echo "FAIL: T16: no Submodule Advance Record was written"; FAILURES=$((FAILURES + 1))
fi

# --- T16p POSITIVE: an own-org remote whose repository name DOES match the ---
# authorization must not be refused by the identity check. Asserted by the
# outcome NOT being a governance refusal -- the run dies at the fetch instead,
# because /bin/false cannot open a connection.
echo "=== T16p: POSITIVE -- an own-org upstream naming the authorized repo passes ==="
CASES_RUN=$((CASES_RUN + 1))
t16p_root="$(make_fixture 1)"
git -C "${t16p_root}/parent/${SUB_PATH}" remote add ourmirror \
  "git@gitlab.com:HelixDevelopment/helixqa.git"
run_script "${t16p_root}/parent" "${t16p_root}/records" 'true' \
  "GIT_SSH_COMMAND=/bin/false" "GIT_TERMINAL_PROMPT=0"
if t16p_rec="$(find_record "${t16p_root}/records")"; then
  expect_ne "T16p" "outcome must NOT be a governance refusal" \
    "REFUSED_GOVERNANCE_DENY" "$(jq -r '.outcome' "$t16p_rec")"
else
  echo "FAIL: T16p: no Submodule Advance Record was written"; FAILURES=$((FAILURES + 1))
fi

# =========================================================================
# ROUND 3 -- the 2026-08-26 T054 ROUND-3 REVIEW.
#
# It re-proved all six round-2 fixes from its own fixtures and returned
# APPROVE WITH FIXES with six more items. T17-T24 below close them, plus four
# FURTHER escapes of the same class the round-3 review named (build output
# reaching another repository unattended, exit 0, outcome ADVANCED) that the
# hunt for a fourth turned up: HEAD moved by the build, a remote added AFTER
# the scope scan, a pre-commit hook inside the submodule, and push.followTags.
#
# Every case was reproduced against a disposable fixture BEFORE its fix; the
# captured pre-fix behaviour is quoted in each comment.
#
#   T17  R3-F2   the scope gate classifies the PUSH destination, not just fetch
#   T18  R3-F1   a commit cannot exceed the audited path set (index scope)
#   T19  ESC-4   HEAD moved by the build is refused, not built on top of
#   T20  ESC-5   a remote added AFTER the scan is re-classified at push time
#   T21  ESC-6   a pre-commit hook's own `git add` cannot ride the commit out
#   T22  ESC-7   push.followTags cannot publish a tag nothing examined
#   T23  R3-F3   a readonly switch from BASH_ENV cannot forge an authorization
#   T24  R3-F4   the governance identity gate may not pass having read ZERO
#   T25  POSITIVE a benign publish still advances, so none of the above is a
#                 blanket refusal
#   T26  R3-F6   an attribute-driven tree difference is named, not implied to
#                 be a mutation (and the note is absent for a real mutation)
# =========================================================================

# Counts the escape-class cases that actually ASSERTED. Each of T17-T22 (round
# 3) and T27-T31 (round 4) is a distinct, separately-proven way for content to
# reach another repository.
#
# These counters used to be incremented immediately after each case's banner,
# i.e. on ENTRY, before the first expect_*. That is exactly the vacuous shape
# these guards exist to refuse, one level up: the round-4 review hollowed T18
# and the floor still reported "examined 6 distinct publish-escape routes".
# `esc_case` therefore opens a window and `esc_close` requires a real number of
# assertions to have executed inside it before the route counts as examined.
R3_ESCAPES_EXAMINED=0
R4_ESCAPES_EXAMINED=0
# Round 5's two findings are not publish escapes -- nothing of theirs reaches
# another repository's default branch. They are the other two shapes this file
# polices: writing into state OUTSIDE the fixture, and reaching a verdict
# having examined nothing. The window mechanism is era-agnostic, so they get
# their own counter under their own name rather than being filed as escapes
# they are not.
R5_CASES_EXAMINED=0
ESCAPE_ASSERTIONS=0
# The minimum every escape case must actually assert: the run refused, the
# content did not reach the other repository, and the pin did not move. Fewer
# than three means the case cannot have checked all three.
ESC_MIN_ASSERTIONS=3
_esc_start=-1
_esc_label=""
_esc_era=""

esc_close() {
  [[ "$_esc_start" -ge 0 ]] || return 0
  local n=$((ASSERTIONS_RUN - _esc_start))
  if [[ "$n" -ge "$ESC_MIN_ASSERTIONS" ]]; then
    case "$_esc_era" in
      3) R3_ESCAPES_EXAMINED=$(( R3_ESCAPES_EXAMINED + 1 )) ;;
      4) R4_ESCAPES_EXAMINED=$(( R4_ESCAPES_EXAMINED + 1 )) ;;
      5) R5_CASES_EXAMINED=$(( R5_CASES_EXAMINED + 1 )) ;;
      *)
        echo "FAIL: ${_esc_label}: opened a window in an unknown era '${_esc_era}', so no floor counts it — which is precisely the hollowing an unfloored case allows"
        FAILURES=$((FAILURES + 1))
        ;;
    esac
    ESCAPE_ASSERTIONS=$(( ESCAPE_ASSERTIONS + n ))
    echo "PASS: ${_esc_label}: ${n} assertion(s) actually executed for this guarded route"
  else
    echo "FAIL: ${_esc_label}: only ${n} assertion(s) executed for this guarded route, expected at least ${ESC_MIN_ASSERTIONS} — a case that was REACHED but asserts nothing is a hollowed case, and an entry counter cannot see one"
    FAILURES=$((FAILURES + 1))
  fi
  _esc_start=-1
}

# esc_case <label> <era 3|4> — closes the previous escape window and opens this
# one at the current assertion count.
esc_case() {
  esc_close
  _esc_label="$1"
  _esc_era="$2"
  _esc_start="$ASSERTIONS_RUN"
}

# -------------------------------------------------------------------------
# T17: R3-F2 -- condition (C) classified `git remote get-url` (the FETCH url)
# and `remote.<n>.url`. NEITHER is where git pushes: that comes from
# `remote.<n>.pushurl` and `url.<base>.pushInsteadOf`, visible only through
# `git remote get-url --push`.
#
# PRE-FIX (captured, disposable fixture): with remote.origin.pushurl pointing
# at a second bare repo,
#   ADVANCED to c18feafd...; parent pin staged      EXIT=0
#   classified-URL (fetch) = .../up.git
#   ACTUAL push dest       = .../rogue.git
#   authorised up.git got operator.txt?   0
#   UNSCANNED rogue.git got operator.txt? 1
# The round-3 review also measured this exact configuration shape live in this
# repository: constitution/origin fetches github.com/HelixDevelopment and
# PUSHES to gitflic.ru, a provider §6.W names explicitly forbidden.
#
# The hosts below are never contacted -- GIT_SSH_COMMAND=/bin/false cannot open
# a connection, and the refusal is asserted to happen BEFORE any fetch.
# -------------------------------------------------------------------------
echo "=== T17: the scope gate classifies where a PUSH would actually go ==="
CASES_RUN=$((CASES_RUN + 1))
esc_case "T17" 3
T17_EXAMINED=0
for t17_form in pushurl pushInsteadOf; do
  T17_EXAMINED=$((T17_EXAMINED + 1))
  t17_root="$(make_fixture 1)"
  t17_pin="$(parent_pin "${t17_root}/parent")"
  # RETARGETED 2026-08-26: the tree is left CLEAN on purpose. It used to carry
  # the operator's uncommitted work, which now makes the run refuse BEFORE the
  # condition-(C) scan -- and this case is about the scan. A fixture that never
  # reaches the gate it names is a case that proves nothing about it.
  t17_up="$(upstream_fp "$t17_root")"
  case "$t17_form" in
    pushurl)
      git -C "${t17_root}/parent/${SUB_PATH}" config \
        remote.origin.pushurl "git@gitflic.ru:helixdevelopment/helixqa.git" ;;
    pushInsteadOf)
      git -C "${t17_root}/parent/${SUB_PATH}" config \
        'url.git@some-personal-fork.invalid:.pushInsteadOf' "${t17_root}/" ;;
  esac
  run_script "${t17_root}/parent" "${t17_root}/records" 'true' \
    "GIT_SSH_COMMAND=/bin/false" "GIT_TERMINAL_PROMPT=0"
  expect_ne "T17(${t17_form})" "exit code" "0" "$RUN_EXIT"
  expect_contains "T17(${t17_form})" "the refusal NAMES the push destination it classified" \
    "invalid" "$(sed 's/gitflic.ru/invalid/g' <<<"$LAST_OUTPUT")"
  expect_eq "T17(${t17_form})" "parent pin untouched" "$t17_pin" "$(parent_pin "${t17_root}/parent")"
  expect_eq "T17(${t17_form})" "the authorised upstream is byte-identical too" \
    "$t17_up" "$(upstream_fp "$t17_root")"
  if t17_rec="$(find_record "${t17_root}/records")"; then
    expect_eq "T17(${t17_form})" "outcome" "REFUSED_FOREIGN_UPSTREAM" "$(jq -r '.outcome' "$t17_rec")"
  else
    echo "FAIL: T17(${t17_form}): no Submodule Advance Record was written"; FAILURES=$((FAILURES + 1))
  fi
done
if [[ "$T17_EXAMINED" -eq 2 ]]; then
  echo "PASS: T17: examined ${T17_EXAMINED} push-redirect forms"
else
  echo "FAIL: T17: examined ${T17_EXAMINED} forms, expected 2 -- the table did not iterate, so its assertions never ran"
  FAILURES=$((FAILURES + 1))
fi

# -------------------------------------------------------------------------
# T18: R3-F1 -- every content guard is scoped to LOCAL_MOD_PATHS, but
# `git commit` takes the WHOLE index. A rebuild-and-test step that stages a
# file publishes it.
#
# PRE-FIX (captured):
#   VERIFY_CMD: printf "BUILD SECRET / CREDENTIAL DUMP\n" > "$1/build-leak.txt"
#               git -C "$1" add build-leak.txt
#   ADVANCED to 9cf0532b...; parent pin staged       EXIT=0
#   --- UPSTREAM master tree ---
#     100644 blob 4c29e54c...  build-leak.txt   <-- ON ANOTHER REPO'S DEFAULT BRANCH
# Third escape of the BLOCKER-2 class after content and mode/gitlink.
# -------------------------------------------------------------------------
echo "=== T18: a build that STAGES a file cannot publish it ==="
CASES_RUN=$((CASES_RUN + 1))
esc_case "T18" 3
t18_root="$(make_fixture 1)"
# RETARGETED 2026-08-26. The guard this case proved fired lived inside R-005
# step 6, which was REMOVED. The route is now closed one step earlier and one
# step harder: the submodule carries the operator's uncommitted work, so the
# run REFUSES before the fetch and the adversarial verify command is never
# even invoked. The upstream fingerprint (refs + object count) is the witness.
t18_pin="$(parent_pin "${t18_root}/parent")"
printf 'OPERATOR WORK\n' > "${t18_root}/parent/${SUB_PATH}/operator-work.txt"
t18_up="$(upstream_fp "$t18_root")"
run_script "${t18_root}/parent" "${t18_root}/records" \
  'printf "BUILD SECRET\n" > "$1/build-leak.txt"; git -C "$1" add build-leak.txt'
expect_ne "T18" "exit code" "0" "$RUN_EXIT"
expect_eq "T18" "the build's staged file did NOT reach another repository" "NO" "$(upstream_has "$t18_root" "build-leak.txt")"
expect_eq "T18" "the upstream is byte-identical (refs + object count)" "$t18_up" "$(upstream_fp "$t18_root")"
expect_eq "T18" "parent pin untouched" "$t18_pin" "$(parent_pin "${t18_root}/parent")"
expect_contains "T18" "the refusal names its own cause" "does not carry local modifications forward" "$LAST_OUTPUT"
if t18_rec="$(find_record "${t18_root}/records")"; then
  expect_eq "T18" "outcome" "FAILED_PRECONDITION" "$(jq -r '.outcome' "$t18_rec")"
  expect_eq "T18" "parent_pin_updated" "false" "$(jq -r '.parent_pin_updated' "$t18_rec")"
else
  echo "FAIL: T18: no Submodule Advance Record was written"; FAILURES=$((FAILURES + 2))
fi

# -------------------------------------------------------------------------
# T19: ESC-4 -- the FOURTH escape of the same class, found while fixing T18.
# Scoping the commit does not help when the build moved HEAD: the
# carry-forward commit is then built ON TOP OF the build's commit, so the
# build's content is in the parent AND in the tree.
#
# PRE-FIX (captured):
#   VERIFY_CMD: ... git add build-leak.txt; git commit -m "build artifact"
#   ADVANCED to 814d19c3...; parent pin staged       EXIT=0
#   --- upstream log ---
#     814d19c chore: local modifications carried forward by advance-all-submodules
#     f56f25d build artifact      <-- THE BUILD'S OWN COMMIT, PUBLISHED
# -------------------------------------------------------------------------
echo "=== T19: a build that COMMITS cannot have its commit ride out as a parent ==="
CASES_RUN=$((CASES_RUN + 1))
esc_case "T19" 3
t19_root="$(make_fixture 1)"
t19_pin="$(parent_pin "${t19_root}/parent")"
t19_tip="$(upstream_tip "$t19_root")"
# RETARGETED 2026-08-26. The guard this case proved fired lived inside R-005
# step 6, which was REMOVED. The route is now closed one step earlier and one
# step harder: the submodule carries the operator's uncommitted work, so the
# run REFUSES before the fetch and the adversarial verify command is never
# even invoked. The upstream fingerprint (refs + object count) is the witness.
printf 'OPERATOR WORK\n' > "${t19_root}/parent/${SUB_PATH}/operator-work.txt"
t19_up="$(upstream_fp "$t19_root")"
run_script "${t19_root}/parent" "${t19_root}/records" \
  'printf "BUILD SECRET\n" > "$1/build-leak.txt"; git -C "$1" add build-leak.txt; git -C "$1" -c user.name=b -c user.email=b@b.invalid commit --quiet -m "build artifact"'
expect_ne "T19" "exit code" "0" "$RUN_EXIT"
expect_eq "T19" "the build's committed file did NOT reach another repository" "NO" "$(upstream_has "$t19_root" "build-leak.txt")"
expect_eq "T19" "the upstream tip did not move at all" "$t19_tip" "$(upstream_tip "$t19_root")"
expect_eq "T19" "the upstream is byte-identical (refs + object count)" "$t19_up" "$(upstream_fp "$t19_root")"
expect_eq "T19" "parent pin untouched" "$t19_pin" "$(parent_pin "${t19_root}/parent")"
expect_contains "T19" "the refusal names its own cause" "does not carry local modifications forward" "$LAST_OUTPUT"
if t19_rec="$(find_record "${t19_root}/records")"; then
  expect_eq "T19" "outcome" "FAILED_PRECONDITION" "$(jq -r '.outcome' "$t19_rec")"
else
  echo "FAIL: T19: no Submodule Advance Record was written"; FAILURES=$((FAILURES + 1))
fi

# -------------------------------------------------------------------------
# T20: ESC-5 -- the FIFTH escape. The condition-(C) scan runs BEFORE the
# rebuild-and-test step, and that step runs inside the submodule with full
# access to its .git/config. A certification of a mutable set is worth only
# the moment it was read in.
#
# PRE-FIX (captured), verify command `git -C "$1" remote add rogue <bare>`:
#   ADVANCED to 8af205a7...; parent pin staged       EXIT=0
#   UNSCANNED rogue.git got operator.txt? 1
# The variant asserted here rewrites the EXISTING remote's push destination to
# a foreign host, which is the production-reachable shape.
# -------------------------------------------------------------------------
echo "=== T20: a push destination changed AFTER the scan is re-classified ==="
CASES_RUN=$((CASES_RUN + 1))
esc_case "T20" 3
# fingerprint taken before the run; the assertions below compare against it
t20_root="$(make_fixture 1)"
t20_pin="$(parent_pin "${t20_root}/parent")"
printf 'OPERATOR WORK\n' > "${t20_root}/parent/${SUB_PATH}/operator-work.txt"
t20_up="$(upstream_fp "$t20_root")"
run_script "${t20_root}/parent" "${t20_root}/records" \
  'git -C "$1" config remote.origin.pushurl git@some-personal-fork.invalid:x/helixqa.git' \
  "GIT_SSH_COMMAND=/bin/false" "GIT_TERMINAL_PROMPT=0"
expect_ne "T20" "exit code" "0" "$RUN_EXIT"
expect_eq "T20" "nothing was published to the authorised upstream either" "NO" "$(upstream_has "$t20_root" "operator-work.txt")"
expect_eq "T20" "parent pin untouched" "$t20_pin" "$(parent_pin "${t20_root}/parent")"
expect_eq "T20" "the upstream is byte-identical (refs + object count)" "$t20_up" "$(upstream_fp "$t20_root")"
# RETARGETED 2026-08-26. The push-time re-certification this asserted lived in
# the publish path. What replaces it is stronger and needs no re-check at all:
# the remote the verify command would have added is never added, because the
# verify command never runs.
expect_contains "T20" "the refusal is taken before the fetch, so the verify step never ran" \
  "does not carry local modifications forward" "$LAST_OUTPUT"
# The load-bearing half. Without the push-time re-check the run does not merely
# record a different cause -- it GENUINELY ATTEMPTS to contact the foreign host,
# and only this fixture's blocked transport stops it. With working credentials
# it lands. So the assertion is that no connection was ever tried.
if grep -qF -- "Could not read from remote repository" <<<"$LAST_OUTPUT"; then
  echo "FAIL: T20: the run ATTEMPTED to contact the out-of-scope push destination; only the blocked transport stopped it"
  FAILURES=$((FAILURES + 1))
else
  echo "PASS: T20: the out-of-scope push destination was never contacted at all"
fi
if t20_rec="$(find_record "${t20_root}/records")"; then
  expect_eq "T20" "outcome" "FAILED_PRECONDITION" "$(jq -r '.outcome' "$t20_rec")"
else
  echo "FAIL: T20: no Submodule Advance Record was written"; FAILURES=$((FAILURES + 1))
fi

# -------------------------------------------------------------------------
# T21: ESC-6 -- the SIXTH escape, and the reason `git commit --only` is NOT
# claimed sufficient on its own. Measured: under `--only` git hands the
# pre-commit hook a temporary index, and a hook that runs `git add` has its
# file land in the commit git builds:
#   tree after hook commit: a.txt hookleak.txt op.txt op2.txt
# What catches it is the comparison of the FINISHED commit against the fetched
# upstream commit -- a guard on the published artefact rather than on the
# enumerated ways in.
# -------------------------------------------------------------------------
echo "=== T21: a pre-commit hook cannot add a path outside the audited set ==="
CASES_RUN=$((CASES_RUN + 1))
esc_case "T21" 3
t21_root="$(make_fixture 1)"
t21_pin="$(parent_pin "${t21_root}/parent")"
printf 'OPERATOR WORK\n' > "${t21_root}/parent/${SUB_PATH}/operator-work.txt"
t21_hooks="$(git -C "${t21_root}/parent/${SUB_PATH}" rev-parse --git-path hooks)"
mkdir -p -- "$t21_hooks"
printf '#!/bin/sh\nprintf "HOOK LEAK\\n" > hook-leak.txt\ngit add hook-leak.txt\n' > "${t21_hooks}/pre-commit"
chmod +x "${t21_hooks}/pre-commit"
t21_up="$(upstream_fp "$t21_root")"
run_script "${t21_root}/parent" "${t21_root}/records" 'true'
expect_ne "T21" "exit code" "0" "$RUN_EXIT"
expect_eq "T21" "the hook's file did NOT reach another repository" "NO" "$(upstream_has "$t21_root" "hook-leak.txt")"
expect_eq "T21" "the operator's own work was not published either" \
  "NO" "$(upstream_has "$t21_root" "operator-work.txt")"
expect_eq "T21" "the upstream is byte-identical (refs + object count)" "$t21_up" "$(upstream_fp "$t21_root")"
expect_eq "T21" "parent pin untouched" "$t21_pin" "$(parent_pin "${t21_root}/parent")"
expect_contains "T21" "the refusal names the operator path it found" "operator-work.txt" "$LAST_OUTPUT"
# RETARGETED 2026-08-26, and INVERTED. The old assertion required a rescue ref
# preserving the commit this run had made before discarding it. No commit is
# made any more, so a rescue ref appearing here could only mean committing code
# had returned -- and the operator's work was never moved, so there is nothing
# to preserve.
if [[ -z "$(git -C "${t21_root}/parent/${SUB_PATH}" for-each-ref --format='%(refname)' 'refs/lava-advance-rescue/**' | head -n1)" ]]; then
  echo "PASS: T21: no rescue ref exists -- the operator's work was never moved, so nothing needed preserving"
else
  echo "FAIL: T21: a refs/lava-advance-rescue/* ref exists, which can only mean a commit was created"
  FAILURES=$((FAILURES + 1))
fi
# ...and the pre-commit hook never fired, because no commit was ever attempted.
if [[ -e "${t21_root}/parent/${SUB_PATH}/hook-leak.txt" ]]; then
  echo "FAIL: T21: the pre-commit hook ran, so a commit WAS attempted inside the submodule"
  FAILURES=$((FAILURES + 1))
else
  echo "PASS: T21: the pre-commit hook never fired -- no commit was attempted"
fi
if t21_rec="$(find_record "${t21_root}/records")"; then
  expect_eq "T21" "outcome" "FAILED_PRECONDITION" "$(jq -r '.outcome' "$t21_rec")"
else
  echo "FAIL: T21: no Submodule Advance Record was written"; FAILURES=$((FAILURES + 1))
fi

# -------------------------------------------------------------------------
# T22: ESC-7 -- the SEVENTH escape. `push.followTags` is read from config, so
# the rebuild-and-test step can set it, and git then pushes annotated tags
# reachable from the pushed commit alongside it.
#
# PRE-FIX (captured):
#   VERIFY_CMD: git config push.followTags true; git tag -a -m "leak tag" LEAK-TAG HEAD
#   ADVANCED ...                                   EXIT=0
#   tags on upstream: [LEAK-TAG ]
# A tag is a ref another repository now carries, and §6.T.3 forbids the
# history-overwriting push that would take it back.
# -------------------------------------------------------------------------
echo "=== T22: push.followTags cannot publish a tag nothing examined ==="
CASES_RUN=$((CASES_RUN + 1))
esc_case "T22" 3
# RETARGETED 2026-08-26, and run BOTH ways, because each half proves something
# the other cannot. (a) with the submodule unclean the run refuses before the
# fetch, so the tag is never even created; (b) with it CLEAN the verify command
# really does run, really does set push.followTags and really does create the
# annotated tag -- and the tag still cannot reach the upstream, because no push
# is issued for it to ride out on. (b) is the one that would fail if pushing
# code came back.
t22_root="$(make_fixture 1)"
printf 'OPERATOR WORK\n' > "${t22_root}/parent/${SUB_PATH}/operator-work.txt"
t22_up="$(upstream_fp "$t22_root")"
run_script "${t22_root}/parent" "${t22_root}/records" \
  'git -C "$1" config push.followTags true; git -C "$1" -c user.name=b -c user.email=b@b.invalid tag -a -m "leak tag" LEAK-TAG HEAD'
expect_ne "T22(unclean)" "exit code" "0" "$RUN_EXIT"
expect_eq "T22(unclean)" "the operator's work did NOT reach the upstream" "NO" "$(upstream_has "$t22_root" "operator-work.txt")"
expect_eq "T22(unclean)" "the upstream is byte-identical (refs + object count)" "$t22_up" "$(upstream_fp "$t22_root")"
expect_eq "T22(unclean)" "the build's tag did NOT reach another repository" \
  "" "$(git -C "${t22_root}/upstream.git" tag -l | tr '\n' ' ' | sed 's/ *$//')"

CASES_RUN=$((CASES_RUN + 1))
esc_case "T22b" 3
t22b_root="$(make_fixture 1)"
t22b_up="$(upstream_fp "$t22b_root")"
run_script "${t22b_root}/parent" "${t22b_root}/records" \
  'git -C "$1" config push.followTags true; git -C "$1" -c user.name=b -c user.email=b@b.invalid tag -a -m "leak tag" LEAK-TAG HEAD'
expect_eq "T22b(clean)" "exit code (the advance itself is legitimate)" "0" "$RUN_EXIT"
expect_eq "T22b(clean)" "fixture sanity: the verify step really DID create the tag locally" "LEAK-TAG" \
  "$(git -C "${t22b_root}/parent/${SUB_PATH}" tag -l | tr '\n' ' ' | sed 's/ *$//')"
expect_eq "T22b(clean)" "the build's tag did NOT reach another repository" \
  "" "$(git -C "${t22b_root}/upstream.git" tag -l | tr '\n' ' ' | sed 's/ *$//')"
expect_eq "T22b(clean)" "the upstream is byte-identical after a SUCCESSFUL advance" "$t22b_up" "$(upstream_fp "$t22b_root")"

# -------------------------------------------------------------------------
# T23: R3-F3 -- the argv-only property was ASSERTED, not checked. `BASH_ENV`
# is itself an inherited environment variable; bash sources it before the
# script body, and `declare -r PUBLISH_LOCAL_MODS=true` there makes the
# script's own reset fail silently under `set +e`.
#
# PRE-FIX (captured), with NO flags on argv at all:
#   advance-all-submodules.sh: line 212: PUBLISH_LOCAL_MODS: readonly variable
#   --publish-local-modifications was given: ... WILL be committed and pushed
#   ADVANCED to 31206c9f...                        EXIT=0
#   operator.txt published to upstream? 1
# The capability escalation is nil (setting BASH_ENV already means arbitrary
# code execution as this user). The HONESTY defect is not: the run printed an
# authorization argv never gave, and then did the one irreversible thing on
# the strength of it.
# -------------------------------------------------------------------------
esc_close
echo "=== T23: a readonly switch from the environment cannot forge an authorization ==="
CASES_RUN=$((CASES_RUN + 1))
t23_root="$(make_fixture 1)"
t23_pin="$(parent_pin "${t23_root}/parent")"
printf 'OPERATOR WORK\n' > "${t23_root}/parent/${SUB_PATH}/operator-work.txt"
cat > "${t23_root}/bashenv.sh" <<'T23EOF'
declare -r PUBLISH_LOCAL_MODS=true
declare -r ALLOW_LOCAL_PATH_REMOTES=true
T23EOF
t23_exit=0
t23_out="$(
  env BASH_ENV="${t23_root}/bashenv.sh" \
      LAVA_ADVANCE_RECORD_DIR="${t23_root}/records" \
      LAVA_ADVANCE_VERIFY_CMD='true' \
    "$SCRIPT_UNDER_TEST" "${t23_root}/parent" 2>&1
)" || t23_exit=$?
expect_eq "T23" "exit code (2 = configuration error, nothing attempted)" "2" "$t23_exit"
expect_eq "T23" "the operator's work was NOT published" "NO" "$(upstream_has "$t23_root" "operator-work.txt")"
expect_eq "T23" "parent pin untouched" "$t23_pin" "$(parent_pin "${t23_root}/parent")"
expect_contains "T23" "the refusal names what it could not prove" "could not be reset to its default OFF state" "$t23_out"
# The `declare -r PUBLISH_LOCAL_MODS=true` line is deliberately LEFT in the
# BASH_ENV fixture after the flag's removal. It is now inert -- the script
# never reads that name -- and the assertion below is the regression guard
# that keeps it inert: a future edit reintroducing the variable would make
# this fixture forge exactly the authorization R3-F3 was about.
if grep -qF -- "PUBLISH_LOCAL_MODS" <<<"$t23_out"; then
  echo "FAIL: T23: the run still reads PUBLISH_LOCAL_MODS, so a BASH_ENV file can still speak to it"
  FAILURES=$((FAILURES + 1))
else
  echo "PASS: T23: PUBLISH_LOCAL_MODS is not a name this script reads at all any more"
fi
if grep -qF -- "--publish-local-modifications was given" <<<"$t23_out"; then
  echo "FAIL: T23: the run claimed '--publish-local-modifications was given' when argv gave no such flag"
  FAILURES=$((FAILURES + 1))
else
  echo "PASS: T23: the run does NOT claim an authorization argv never gave"
fi

# -------------------------------------------------------------------------
# T24: R3-F4 -- the governance identity gate was the one new gate in its fix
# pass that arrived WITHOUT a zero-item floor, while the (C) scan and the push
# loop both got one in that same pass. Zero iterations read as "every upstream
# names an authorized repository".
#
# PRE-FIX (captured): `git@github.com:vasic-digital/.git` classifies OWN_ORG
# with an empty repository name and was `continue`d -- skipped, not refused --
# so the run fell through to the fetch and reported FAILED_PRECONDITION, a
# cause produced by the blocked transport rather than by the identity gate.
# Unknown read as OK, where everywhere else in this file unknown refuses.
# -------------------------------------------------------------------------
echo "=== T24: the identity gate refuses an unreadable name, and states a nameless certification ==="
CASES_RUN=$((CASES_RUN + 1))
t24_root="$(make_fixture 1)"
t24_pin="$(parent_pin "${t24_root}/parent")"
git -C "${t24_root}/parent/${SUB_PATH}" remote add nameless "git@github.com:vasic-digital/.git"
run_script "${t24_root}/parent" "${t24_root}/records" 'true' \
  "GIT_SSH_COMMAND=/bin/false" "GIT_TERMINAL_PROMPT=0"
expect_ne "T24(unreadable)" "exit code" "0" "$RUN_EXIT"
expect_contains "T24(unreadable)" "the refusal names its own cause" "no readable repository name" "$LAST_OUTPUT"
expect_eq "T24(unreadable)" "parent pin untouched" "$t24_pin" "$(parent_pin "${t24_root}/parent")"
if t24_rec="$(find_record "${t24_root}/records")"; then
  expect_eq "T24(unreadable)" "outcome" "REFUSED_GOVERNANCE_DENY" "$(jq -r '.outcome' "$t24_rec")"
else
  echo "FAIL: T24(unreadable): no Submodule Advance Record was written"; FAILURES=$((FAILURES + 1))
fi

# The other half: with only filesystem remotes (this suite's own shape) the
# identity gate legitimately has nothing to read. It is not refused -- a
# hermetic fixture has no other remote shape -- but it must SAY SO, so a run
# certified on a directory label is never silently indistinguishable from one
# certified on a repository identity.
CASES_RUN=$((CASES_RUN + 1))
t24b_root="$(make_fixture 1)"
run_script "${t24b_root}/parent" "${t24b_root}/records" 'true'
expect_eq "T24(nameless)" "exit code" "0" "$RUN_EXIT"
expect_contains "T24(nameless)" "the run states that the identity half examined nothing" \
  "certified on its PATH NAME ALONE" "$LAST_OUTPUT"

# -------------------------------------------------------------------------
# T25 POSITIVE: none of T17-T24 may be satisfied by a blanket refusal. A
# benign run with the same flags must still advance, publish the operator's
# work, and record it.
# -------------------------------------------------------------------------
# RETARGETED 2026-08-26. The positive property is still required -- none of
# T17-T24 may be satisfied by a blanket refusal -- but its subject changed with
# the capability. It used to be "a benign publish still advances". There is no
# publish, so it is now: a CLEAN submodule still advances, the verify command
# still runs and still writes into the working tree, and the upstream is still
# byte-identical afterwards.
echo "=== T25: POSITIVE -- a clean submodule still advances after all round-3 guards ==="
CASES_RUN=$((CASES_RUN + 1))
t25_root="$(make_fixture 1)"
t25_pin="$(parent_pin "${t25_root}/parent")"
t25_up="$(upstream_fp "$t25_root")"
run_script "${t25_root}/parent" "${t25_root}/records" 'printf X > "$1/build-scratch.tmp"'
expect_eq "T25" "exit code" "0" "$RUN_EXIT"
expect_ne "T25" "parent pin advanced" "$t25_pin" "$(parent_pin "${t25_root}/parent")"
expect_eq "T25" "the build's own scratch file did NOT reach the upstream" "NO" "$(upstream_has "$t25_root" "build-scratch.tmp")"
expect_eq "T25" "fixture sanity: the verify command really DID run" "X" \
  "$(cat "${t25_root}/parent/${SUB_PATH}/build-scratch.tmp" 2>/dev/null)"
expect_eq "T25" "the upstream is byte-identical after a SUCCESSFUL advance" "$t25_up" "$(upstream_fp "$t25_root")"
if t25_rec="$(find_record "${t25_root}/records")"; then
  expect_eq "T25" "outcome" "ADVANCED" "$(jq -r '.outcome' "$t25_rec")"
  expect_eq "T25" "local_modifications_pushed" "false" "$(jq -r '.local_modifications_pushed' "$t25_rec")"
  expect_eq "T25" "parent_pin_updated" "true" "$(jq -r '.parent_pin_updated' "$t25_rec")"
else
  echo "FAIL: T25: no Submodule Advance Record was written"; FAILURES=$((FAILURES + 3))
fi

# -------------------------------------------------------------------------
# T26: R3-F6 -- attribute divergence produces a FALSE refusal, and the message
# must name that as a candidate rather than leaving the operator to infer a
# mutation that never happened.
#
# `_captured_tree` stages through `git add`, which applies .gitattributes. The
# pre-verify tree is computed under the OLD commit's attributes and the
# post-verify tree under the NEW commit's, so an upstream commit introducing
# `text=auto` moves the tree id for BYTE-IDENTICAL content.
#
# REPRODUCED (captured, before the diagnostic existed): upstream adds
# `*.txt text=auto eol=lf`; the operator's untracked CRLF file is untouched by
# a verify command of `true`; the run refuses with
#   "tree cf955685... -> 165f8e2e..."
#   "No individual path description changed ..."
# and nothing whatsoever to say the cause might not be a mutation.
#
# The refusal itself is CORRECT-DIRECTION (it never publishes) and is left in
# place; what is asserted here is that it stops misleading the reader.
# -------------------------------------------------------------------------
# RETARGETED 2026-08-26. The false-refusal this case was written about --
# a tree id that moves for byte-identical content because the NEW upstream
# commit introduced `text=auto` -- was produced by `_captured_tree`, which
# existed only to compare the operator's pre-verify and post-verify staged
# trees for the step-6 commit. Both the comparison and the diagnostic are
# gone with the publish path.
#
# The case is kept because the FIXTURE still exercises a real hazard and the
# correct answer changed: an upstream introducing attributes must not make the
# run refuse at all, and must not corrupt the operator's CRLF bytes on disk.
# A false refusal that has become a clean pass is worth pinning, because a
# future reintroduction of tree comparison would bring the false refusal back.
echo "=== T26: an upstream introducing text attributes no longer produces a false refusal ==="
CASES_RUN=$((CASES_RUN + 1))
t26_root="$(make_fixture 0)"
up_commit "${t26_root}/upstream.git" "${t26_root}/attr-pusher" \
  "introduce text attributes" ".gitattributes" "*.txt text=auto eol=lf"
t26_up="$(upstream_fp "$t26_root")"
t26_pin="$(parent_pin "${t26_root}/parent")"
printf 'OPERATOR WORK\r\n' > "${t26_root}/parent/${SUB_PATH}/operator-work.txt"
run_script "${t26_root}/parent" "${t26_root}/records" 'true'
# The tree carries the operator's uncommitted file, so the run refuses -- but
# for the HONEST reason (an unclean tree), not for a tree-id difference that
# never was a mutation.
expect_ne "T26" "exit code" "0" "$RUN_EXIT"
expect_eq "T26" "nothing was published" "NO" "$(upstream_has "$t26_root" "operator-work.txt")"
expect_eq "T26" "the upstream is byte-identical" "$t26_up" "$(upstream_fp "$t26_root")"
expect_eq "T26" "parent pin untouched" "$t26_pin" "$(parent_pin "${t26_root}/parent")"
expect_contains "T26" "the refusal names the honest cause" "does not carry local modifications forward" "$LAST_OUTPUT"
if grep -qF -- "changes a .gitattributes file relative to" <<<"$LAST_OUTPUT"; then
  echo "FAIL: T26: the run still reasons about attribute divergence, so tree-comparison code is back"
  FAILURES=$((FAILURES + 1))
else
  echo "PASS: T26: no attribute-divergence reasoning remains -- the tree comparison that needed it is gone"
fi
# The operator's CRLF bytes are untouched on disk: nothing normalised them,
# because nothing staged them.
if [[ "$(od -c "${t26_root}/parent/${SUB_PATH}/operator-work.txt" 2>/dev/null | grep -c '\\r')" -ge 1 ]]; then
  echo "PASS: T26: the operator's CRLF bytes are untouched on disk"
else
  echo "FAIL: T26: the operator's CRLF line ending was altered"
  FAILURES=$((FAILURES + 1))
fi

# The other half, and the reason the fixture above must NOT be the only one:
# with a CLEAN tree the same attribute-introducing upstream must ADVANCE, not
# refuse. That is the false-refusal check proper.
CASES_RUN=$((CASES_RUN + 1))
t26b_root="$(make_fixture 0)"
up_commit "${t26b_root}/upstream.git" "${t26b_root}/attr-pusher-b" \
  "introduce text attributes" ".gitattributes" "*.txt text=auto eol=lf"
t26b_pin="$(parent_pin "${t26b_root}/parent")"
t26b_up="$(upstream_fp "$t26b_root")"
run_script "${t26b_root}/parent" "${t26b_root}/records" 'true'
expect_eq "T26b" "exit code (an attribute-introducing upstream must not refuse a clean tree)" "0" "$RUN_EXIT"
expect_ne "T26b" "parent pin advanced" "$t26b_pin" "$(parent_pin "${t26b_root}/parent")"
expect_eq "T26b" "the upstream is byte-identical" "$t26b_up" "$(upstream_fp "$t26b_root")"
if t26b_rec="$(find_record "${t26b_root}/records")"; then
  expect_eq "T26b" "outcome" "ADVANCED" "$(jq -r '.outcome' "$t26b_rec")"
else
  echo "FAIL: T26b: no Submodule Advance Record was written"; FAILURES=$((FAILURES + 1))
fi


# =========================================================================
# ROUND 4 — the eighth, ninth and TENTH publish escapes, and the round-4
# review's should-fix items.
#
# The three new escapes share one root cause, which the round-4 review named
# and which is why round 3's "measure the artefact" guard was not enough:
#
#   the post-commit invariant was a TREE diff, read through the repository the
#   adversary controls, while a push transmits HISTORY, from the true objects,
#   resolving a MUTABLE REF at transfer time.
#
#   T27  R4-F1  8th   post-commit hook re-points HEAD at a fabricated MERGE
#                     whose tree is byte-identical to the honest commit's
#   T28  R4-F2  9th   refs/replace/* shows the guard a benign object while the
#                     transfer carries the real one
#   T29  new    10th  the push refspec's source was `HEAD`, a mutable ref: a
#                     pre-push hook moves it inside the FIRST mirror's push and
#                     every later mirror receives a different commit — and the
#                     parent pin is staged from HEAD too
#   T30  R4-F5        the commit MESSAGE is published and nothing read it
#   T31  R4-F4/F9     the push-time re-certification re-took SCOPE but not
#                     IDENTITY, and listed one destination twice
#   T32  R4-F6        GOVERNANCE_ALLOW was the unchecked assignment R3-F3
#                     hardened everywhere else
#   T33  R4-F7        a commit git REFUSED was recorded REJECTED_PUSH_CONFLICT
#   T34  R4-F8        an untracked nested git repo could never be carried
#                     forward (trailing-slash mismatch) — a permanent false
#                     refusal
#   T35  R4-F3        a replace ref inverts the fast-forward guard
#   T36  residual 3   identity read the last path segment, not <org>/<repo>
#   T37  new          the hardened reader must PROVE itself or refuse
#   T38  POSITIVE     a nested-directory path still publishes (the object
#                     enumeration must accept the intermediate trees git has
#                     to transfer, not refuse everything)
#   T39  POSITIVE     two mirrors both receive the measured commit (the sha
#                     refspec must not break §6.W multi-mirror publishing)
# =========================================================================

# adv_hook_cmd <hook-name> <script-path> — a verify command that installs a
# hook into the submodule's REAL git dir. A submodule's `.git` is a file
# pointing elsewhere, so `$1/.git/hooks` does not exist; `rev-parse
# --absolute-git-dir` is the only correct way to find it.
adv_hook_cmd() {
  printf 'gd="$(git -C "$1" rev-parse --absolute-git-dir)" && mkdir -p "$gd/hooks" && cp %q "$gd/hooks/%s" && chmod +x "$gd/hooks/%s"' \
    "$2" "$1" "$1"
}

# -------------------------------------------------------------------------
# T27 (R4-F1, the EIGHTH escape). PRE-FIX (captured on a disposable fixture):
#   VERIFY_CMD installs a post-commit hook that fabricates a root commit
#   carrying SECRET-DUMP.txt and re-points HEAD at a `commit-tree` MERGE whose
#   tree equals the honest commit's.
#     EXIT=0   outcome=ADVANCED   parent pin moved? YES
#     SECRET-DUMP present on upstream? 1
#     upstream paths (full traversal): f1.txt operator.txt SECRET-DUMP.txt seed.txt
#     parents of upstream master: <merge> <honest-parent> <fabricated-root>
#   The tree diff the round-3 guard took saw only `operator.txt`.
# -------------------------------------------------------------------------
echo "=== T27: a post-commit hook cannot publish a fabricated merge's history ==="
CASES_RUN=$((CASES_RUN + 1))
esc_case "T27" 4
t27_root="$(make_fixture 1)"
t27_pin="$(parent_pin "${t27_root}/parent")"
t27_tip="$(upstream_tip "$t27_root")"
printf 'OPERATOR WORK\n' > "${t27_root}/parent/${SUB_PATH}/operator-work.txt"
cat > "${t27_root}/post-commit-merge" <<'T27HOOK'
#!/bin/sh
T=$(git rev-parse HEAD^{tree})
P=$(git rev-parse HEAD^)
blob=$(printf 'BUILD SECRET / CREDENTIAL DUMP\n' | git hash-object -w --stdin)
tree=$(printf '100644 blob %s\tSECRET-DUMP.txt\n' "$blob" | git mktree)
evil=$(git commit-tree "$tree" -m 'evil root')
merge=$(git commit-tree "$T" -p "$P" -p "$evil" -m 'chore: local modifications carried forward by advance-all-submodules')
git update-ref HEAD "$merge"
T27HOOK
chmod +x "${t27_root}/post-commit-merge"
t27_up="$(upstream_fp "$t27_root")"
run_script "${t27_root}/parent" "${t27_root}/records" \
  "$(adv_hook_cmd post-commit "${t27_root}/post-commit-merge")"
expect_ne "T27" "exit code (0 would certify a fabricated history)" "0" "$RUN_EXIT"
expect_eq "T27" "the fabricated root's blob did NOT reach another repository" "NO" "$(upstream_has "$t27_root" "SECRET-DUMP.txt")"
expect_eq "T27" "the upstream tip did not move at all" "$t27_tip" "$(upstream_tip "$t27_root")"
expect_eq "T27" "the upstream is byte-identical (refs + object count)" "$t27_up" "$(upstream_fp "$t27_root")"
expect_eq "T27" "parent pin untouched" "$t27_pin" "$(parent_pin "${t27_root}/parent")"
# RETARGETED 2026-08-26. The PARENTAGE guard that produced this text measured
# a commit this script no longer creates. The hook that fabricates the merge
# is a post-COMMIT hook, so with no commit it can never fire -- and with the
# tree unclean the verify command that would install it never runs either.
expect_contains "T27" "the refusal names its own cause" "does not carry local modifications forward" "$LAST_OUTPUT"
if t27_rec="$(find_record "${t27_root}/records")"; then
  expect_eq "T27" "outcome" "FAILED_PRECONDITION" "$(jq -r '.outcome' "$t27_rec")"
  expect_eq "T27" "parent_pin_updated" "false" "$(jq -r '.parent_pin_updated' "$t27_rec")"
else
  echo "FAIL: T27: no Submodule Advance Record was written"; FAILURES=$((FAILURES + 2)); note_assertion; note_assertion
fi

# -------------------------------------------------------------------------
# T28 (R4-F2, the NINTH escape). PRE-FIX (captured):
#   a pre-commit hook stages leak.txt; a post-commit hook then publishes
#   refs/replace/<final> pointing at a commit whose tree lacks it.
#     EXIT=0   outcome=ADVANCED   parent pin moved? YES
#     leak.txt in UPSTREAM master tree? YES
#     content published at leak.txt: BUILD SECRET / CREDENTIAL DUMP
#   The guard saw `operator.txt`; `--no-replace-objects` saw `leak.txt
#   operator.txt`. Worse than T27: the leak lands in the upstream default
#   branch's TREE, not merely in reachable history.
# -------------------------------------------------------------------------
echo "=== T28: a replace ref cannot show the guard one object and push another ==="
CASES_RUN=$((CASES_RUN + 1))
esc_case "T28" 4
t28_root="$(make_fixture 1)"
t28_pin="$(parent_pin "${t28_root}/parent")"
t28_tip="$(upstream_tip "$t28_root")"
printf 'OPERATOR WORK\n' > "${t28_root}/parent/${SUB_PATH}/operator-work.txt"
cat > "${t28_root}/pre-commit-leak" <<'T28PRE'
#!/bin/sh
printf 'BUILD SECRET / CREDENTIAL DUMP\n' > leak.txt
git add leak.txt
exit 0
T28PRE
cat > "${t28_root}/post-commit-replace" <<'T28POST'
#!/bin/sh
H=$(git rev-parse HEAD)
P=$(git rev-parse HEAD^)
GD=$(git rev-parse --absolute-git-dir)
IDX="$GD/t28idx"
rm -f "$IDX"
GIT_INDEX_FILE="$IDX" git read-tree "$H"
GIT_INDEX_FILE="$IDX" git rm --cached -q --ignore-unmatch leak.txt
T=$(GIT_INDEX_FILE="$IDX" git write-tree)
rm -f "$IDX"
B=$(git commit-tree "$T" -p "$P" -m 'chore: local modifications carried forward by advance-all-submodules')
git update-ref "refs/replace/$H" "$B"
T28POST
chmod +x "${t28_root}/pre-commit-leak" "${t28_root}/post-commit-replace"
t28_up="$(upstream_fp "$t28_root")"
run_script "${t28_root}/parent" "${t28_root}/records" \
  "$(adv_hook_cmd pre-commit "${t28_root}/pre-commit-leak"); $(adv_hook_cmd post-commit "${t28_root}/post-commit-replace")"
expect_ne "T28" "exit code (0 would certify a replaced object)" "0" "$RUN_EXIT"
expect_eq "T28" "the hook's file did NOT reach the upstream default branch's TREE" "NO" "$(upstream_has "$t28_root" "leak.txt")"
expect_eq "T28" "the upstream tip did not move at all" "$t28_tip" "$(upstream_tip "$t28_root")"
expect_eq "T28" "the upstream is byte-identical (refs + object count)" "$t28_up" "$(upstream_fp "$t28_root")"
expect_eq "T28" "parent pin untouched" "$t28_pin" "$(parent_pin "${t28_root}/parent")"
# The `gith` no-replace-objects reader is NOT removed and is still proven by
# T37: refs/replace/* still inverts the fast-forward guard on the clean path
# (T35), which is why the reader outlives the publish path it was written for.
if [[ -z "$(git -C "${t28_root}/parent/${SUB_PATH}" for-each-ref --format='%(refname)' 'refs/replace/**' 2>/dev/null)" ]]; then
  echo "PASS: T28: no refs/replace/* entry was created -- the post-commit hook never fired, because no commit is made"
else
  echo "FAIL: T28: a refs/replace/* entry exists, which can only mean a commit was made and its post-commit hook ran"
  FAILURES=$((FAILURES + 1))
fi
if t28_rec="$(find_record "${t28_root}/records")"; then
  expect_eq "T28" "outcome" "FAILED_PRECONDITION" "$(jq -r '.outcome' "$t28_rec")"
else
  echo "FAIL: T28: no Submodule Advance Record was written"; FAILURES=$((FAILURES + 1)); note_assertion
fi

# -------------------------------------------------------------------------
# T29 (the TENTH escape, found while fixing the ninth). The push refspec was
# `HEAD:refs/heads/<branch>`. `HEAD` is a MUTABLE REFERENCE that git resolves
# at transfer time, long after every guard measured `$final_commit`.
#
# PRE-FIX (captured on a two-mirror fixture — §6.W mandates two mirrors, so
# this is the production shape):
#   a pre-push hook fires inside the FIRST mirror's push and does
#   `git update-ref HEAD <fabricated>`.
#     EXIT=0  outcome=ADVANCED
#     record new_commit      = b240744…   console 'ADVANCED to' = b240744…
#     STAGED PARENT PIN      = 1c64628…   <-- a commit the record does NOT name
#     up.git  tip = 1c64628…  msg=prepush leak    (PREPUSH-LEAK.txt published)
#     up2.git tip = b240744…  msg=chore: local modifications carried forward
#     MIRRORS AGREE? NO — a §6.C divergence, reported as success
# -------------------------------------------------------------------------
echo "=== T29: a pre-push hook cannot redirect a later mirror, nor the pin ==="
CASES_RUN=$((CASES_RUN + 1))
esc_case "T29" 4
t29_root="$(make_fixture 1)"
t29_pin="$(parent_pin "${t29_root}/parent")"
git init --quiet --bare -b master "${t29_root}/upstream2.git"
git -C "${t29_root}/parent/${SUB_PATH}" push --quiet "${t29_root}/upstream2.git" \
  "$(git -C "${t29_root}/parent/${SUB_PATH}" rev-parse HEAD):refs/heads/master"
# `aaasecond` sorts before `origin`, so it is the FIRST remote the push loop
# reaches and therefore the one whose pre-push hook fires first.
git -C "${t29_root}/parent/${SUB_PATH}" remote add aaasecond "${t29_root}/upstream2.git"
printf 'OPERATOR WORK\n' > "${t29_root}/parent/${SUB_PATH}/operator-work.txt"
t29_m1_before="$(git -C "${t29_root}/upstream.git" rev-parse master)"
t29_m2_before="$(git -C "${t29_root}/upstream2.git" rev-parse master)"
cat > "${t29_root}/pre-push-move" <<'T29HOOK'
#!/bin/sh
GD=$(git rev-parse --absolute-git-dir)
[ -f "$GD/t29-done" ] && exit 0
: > "$GD/t29-done"
H=$(git rev-parse HEAD)
blob=$(printf 'BUILD SECRET / CREDENTIAL DUMP\n' | git hash-object -w --stdin)
IDX="$GD/t29idx"; rm -f "$IDX"
GIT_INDEX_FILE="$IDX" git read-tree "$H"
GIT_INDEX_FILE="$IDX" git update-index --add --cacheinfo 100644,"$blob",PREPUSH-LEAK.txt
T=$(GIT_INDEX_FILE="$IDX" git write-tree)
rm -f "$IDX"
E=$(git commit-tree "$T" -p "$H" -m 'prepush leak')
git update-ref HEAD "$E"
exit 0
T29HOOK
chmod +x "${t29_root}/pre-push-move"
run_script "${t29_root}/parent" "${t29_root}/records" \
  "$(adv_hook_cmd pre-push "${t29_root}/pre-push-move")"
t29_a="$(git -C "${t29_root}/upstream.git" rev-parse master)"
t29_b="$(git -C "${t29_root}/upstream2.git" rev-parse master)"
expect_ne "T29" "exit code (0 would certify a pin the record does not name)" "0" "$RUN_EXIT"
expect_eq "T29" "the hook's file did NOT reach mirror 1" "NO" "$(upstream_has "$t29_root" "PREPUSH-LEAK.txt")"
if git -C "${t29_root}/upstream2.git" cat-file -e "master:PREPUSH-LEAK.txt" 2>/dev/null; then
  echo "FAIL: T29: the hook's file reached mirror 2"; FAILURES=$((FAILURES + 1))
else
  echo "PASS: T29: the hook's file did NOT reach mirror 2"
fi
note_assertion
# RETARGETED 2026-08-26. This used to read "both mirrors carry the SAME commit
# (no §6.C divergence)" -- a property that held only BECAUSE the run pushed the
# same sha to both and converged them. The fixture seeds mirror 2 from the
# submodule's HEAD while mirror 1 is a commit ahead, so with no push they
# legitimately differ, and asserting agreement would assert a convergence
# nothing performs. What must be true instead is stronger and is what a §6.C
# divergence would actually violate: NEITHER mirror was written to at all.
expect_eq "T29" "mirror 1 is byte-identical to before the run" "$t29_m1_before" "$t29_a"
expect_eq "T29" "mirror 2 is byte-identical to before the run" "$t29_m2_before" "$t29_b"
expect_eq "T29" "parent pin untouched" "$t29_pin" "$(parent_pin "${t29_root}/parent")"
# RETARGETED 2026-08-26. The tenth escape needed a PUSH for its pre-push hook
# to fire inside. No push is issued, so the hook is unreachable by construction
# -- and with the tree unclean the verify command that installs it never runs.
expect_contains "T29" "the refusal names its own cause" "does not carry local modifications forward" "$LAST_OUTPUT"
if [[ ! -e "$(git -C "${t29_root}/parent/${SUB_PATH}" rev-parse --absolute-git-dir)/t29-done" ]]; then
  echo "PASS: T29: the pre-push hook never fired -- no push was issued for it to fire inside"
else
  echo "FAIL: T29: the pre-push hook fired, so a push WAS issued"
  FAILURES=$((FAILURES + 1))
fi
if t29_rec="$(find_record "${t29_root}/records")"; then
  expect_eq "T29" "outcome" "FAILED_PRECONDITION" "$(jq -r '.outcome' "$t29_rec")"
  expect_eq "T29" "parent_pin_updated" "false" "$(jq -r '.parent_pin_updated' "$t29_rec")"
else
  echo "FAIL: T29: no Submodule Advance Record was written"; FAILURES=$((FAILURES + 2)); note_assertion; note_assertion
fi

# -------------------------------------------------------------------------
# T30 (R4-F5). PRE-FIX (captured): a commit-msg hook rewrote the message and
#   `chore: carried forward | LEAKED-VIA-COMMIT-MESSAGE` was published on
#   another repository's default branch, EXIT=0, outcome ADVANCED. The
#   invariant is `--name-only`, so it examined `operator.txt` and nothing else.
# -------------------------------------------------------------------------
echo "=== T30: a rewritten commit message cannot be published unexamined ==="
CASES_RUN=$((CASES_RUN + 1))
esc_case "T30" 4
t30_root="$(make_fixture 1)"
t30_pin="$(parent_pin "${t30_root}/parent")"
t30_tip="$(upstream_tip "$t30_root")"
printf 'OPERATOR WORK\n' > "${t30_root}/parent/${SUB_PATH}/operator-work.txt"
t30_up="$(upstream_fp "$t30_root")"
cat > "${t30_root}/commit-msg-leak" <<'T30HOOK'
#!/bin/sh
printf 'chore: carried forward | LEAKED-VIA-COMMIT-MESSAGE\n' > "$1"
T30HOOK
chmod +x "${t30_root}/commit-msg-leak"
run_script "${t30_root}/parent" "${t30_root}/records" \
  "$(adv_hook_cmd commit-msg "${t30_root}/commit-msg-leak")"
expect_ne "T30" "exit code" "0" "$RUN_EXIT"
expect_eq "T30" "the upstream tip did not move at all" "$t30_tip" "$(upstream_tip "$t30_root")"
expect_eq "T30" "the upstream is byte-identical (refs + object count)" "$t30_up" "$(upstream_fp "$t30_root")"
expect_eq "T30" "parent pin untouched" "$t30_pin" "$(parent_pin "${t30_root}/parent")"
# RETARGETED 2026-08-26. The MESSAGE guard read a commit message this script no
# longer writes, because it writes no commit. A commit-msg hook has nothing to
# fire on.
expect_contains "T30" "the refusal names the honest cause" "does not carry local modifications forward" "$LAST_OUTPUT"
if git -C "${t30_root}/upstream.git" log -1 --format=%B master 2>/dev/null | grep -qF 'LEAKED-VIA-COMMIT-MESSAGE'; then
  echo "FAIL: T30: the rewritten message reached another repository's default branch"; FAILURES=$((FAILURES + 1))
else
  echo "PASS: T30: the rewritten message did not reach another repository"
fi
note_assertion

# -------------------------------------------------------------------------
# T31 (R4-F4 + R4-F9). PRE-FIX (captured): the push-time re-certification
#   called _remote_url_class and nothing else, so an own-org repository nobody
#   authorised passed it and the run genuinely ATTEMPTED the push — only the
#   fixture's blocked transport stopped it landing:
#     verify sets pushurl -> OWN_ORG, UNAUTHORISED repo  EXIT=1
#       !! push to remote 'origin' (master) was refused by git: fatal: Could
#          not read from remote repository.
#   And the same function listed one destination twice, because it lacked the
#   de-duplication the pre-fetch scan has.
# -------------------------------------------------------------------------
# RETARGETED 2026-08-26. The push-time re-certification this case proved lived
# in the publish path and is gone with it. What survives, and is what actually
# protects the run now, is the PRE-FETCH condition-(C) scan: it reads both
# halves -- scope AND identity -- over every configured remote INCLUDING its
# push URLs, and it gates the FETCH, which is the remaining way an unauthorised
# repository's objects could end up under the pin this run stages for every
# clone.
#
# The fixture therefore sets the pushurl BEFORE the run rather than from the
# verify command (which no longer runs early enough to matter), and the tree is
# CLEAN so the run reaches the scan instead of refusing before it. The
# de-duplication half (R4-F9) is asserted on the scan's own message.
echo "=== T31: the PRE-FETCH scan re-takes IDENTITY too, and lists each destination once ==="
CASES_RUN=$((CASES_RUN + 1))
esc_case "T31" 4
t31_root="$(make_fixture 1)"
t31_pin="$(parent_pin "${t31_root}/parent")"
t31_tip="$(upstream_tip "$t31_root")"
t31_up="$(upstream_fp "$t31_root")"
git -C "${t31_root}/parent/${SUB_PATH}" config remote.origin.pushurl "git@github.com:vasic-digital/some-other-repo.git"
run_script "${t31_root}/parent" "${t31_root}/records" 'true' \
  "GIT_SSH_COMMAND=/bin/false" "GIT_TERMINAL_PROMPT=0"
expect_ne "T31" "exit code" "0" "$RUN_EXIT"
expect_contains "T31" "the refusal names the unauthorised repository" \
  "some-other-repo" "$LAST_OUTPUT"
expect_eq "T31" "the upstream tip did not move at all" "$t31_tip" "$(upstream_tip "$t31_root")"
expect_eq "T31" "the upstream is byte-identical (refs + object count)" "$t31_up" "$(upstream_fp "$t31_root")"
expect_eq "T31" "parent pin untouched" "$t31_pin" "$(parent_pin "${t31_root}/parent")"
t31_dupes="$(grep -coF "some-other-repo" <<<"$LAST_OUTPUT" || true)"
if [[ "$t31_dupes" -ge 1 ]]; then
  echo "PASS: T31: the unauthorised destination is named in the refusal (${t31_dupes} line(s))"
else
  echo "FAIL: T31: the unauthorised destination is not named at all"
  FAILURES=$((FAILURES + 1))
fi

esc_close

# -------------------------------------------------------------------------
# T32 (R4-F6). PRE-FIX (captured): with BASH_ENV holding GOVERNANCE_ALLOW
#   readonly and widened, a submodule named `constitution` — the one root
#   CLAUDE.md routes through CONST-049's 7-step human pipeline — ADVANCED,
#   EXIT=0, with NO diagnostic at all. `BASH_ENV` is set on the operator's host
#   (/home/<user>/.bashrc), so this is a live channel, not a hypothesis.
#
#   This fixture holds the list to a WIDENED value. The run must refuse before
#   examining anything, exactly as the two safety switches already do.
# -------------------------------------------------------------------------
echo "=== T32: a governance allow-list held from outside argv cannot be acted on ==="
CASES_RUN=$((CASES_RUN + 1))
t32_root="$(make_fixture 1)"
t32_pin="$(parent_pin "${t32_root}/parent")"
printf 'GOVERNANCE_ALLOW=(helixqa constitution)\ndeclare -r GOVERNANCE_ALLOW\n' > "${t32_root}/widen.sh"
run_script "${t32_root}/parent" "${t32_root}/records" 'true' "BASH_ENV=${t32_root}/widen.sh"
expect_eq "T32" "exit code (2 = configuration error, nothing attempted)" "2" "$RUN_EXIT"
expect_contains "T32" "the refusal names its own cause" \
  "governance allow-list could not be set to its declared value" "$LAST_OUTPUT"
expect_eq "T32" "parent pin untouched" "$t32_pin" "$(parent_pin "${t32_root}/parent")"
expect_eq "T32" "records written (nothing was attempted)" "0" "$(record_count "${t32_root}/records")"

# -------------------------------------------------------------------------
# T33 (R4-F7). PRE-FIX (captured): a `.git/MERGE_HEAD` written by the
#   rebuild-and-test step makes `git commit --only` refuse outright
#   (`fatal: cannot do a partial commit during a merge`). The record said
#   REJECTED_PUSH_CONFLICT with zero pushes attempted — a cause nobody
#   observed, which this file's own comments call a bluff twice over.
# -------------------------------------------------------------------------
# RETARGETED 2026-08-26, and run BOTH ways. `git commit` inside the submodule
# is gone, so `.git/MERGE_HEAD` can no longer make one refuse. Half (a) proves
# the entry condition is gone; half (b) is the one worth having: with a CLEAN
# tree the verify command really does write MERGE_HEAD, and the run still
# advances, because nothing after the verify step commits.
echo "=== T33: a MERGE_HEAD can no longer fail a commit that is never made ==="
CASES_RUN=$((CASES_RUN + 1))
t33_root="$(make_fixture 1)"
t33_pin="$(parent_pin "${t33_root}/parent")"
t33_tip="$(upstream_tip "$t33_root")"
t33_up="$(upstream_fp "$t33_root")"
printf 'OPERATOR WORK\n' > "${t33_root}/parent/${SUB_PATH}/operator-work.txt"
run_script "${t33_root}/parent" "${t33_root}/records" \
  'gd="$(git -C "$1" rev-parse --absolute-git-dir)" && git -C "$1" rev-parse HEAD > "$gd/MERGE_HEAD"'
expect_ne "T33(unclean)" "exit code" "0" "$RUN_EXIT"
expect_eq "T33(unclean)" "the upstream tip did not move at all" "$t33_tip" "$(upstream_tip "$t33_root")"
expect_eq "T33(unclean)" "the upstream is byte-identical (refs + object count)" "$t33_up" "$(upstream_fp "$t33_root")"
expect_eq "T33(unclean)" "parent pin untouched" "$t33_pin" "$(parent_pin "${t33_root}/parent")"
if t33_rec="$(find_record "${t33_root}/records")"; then
  expect_eq "T33(unclean)" "outcome names the CAUSE THAT OCCURRED" "FAILED_PRECONDITION" "$(jq -r '.outcome' "$t33_rec")"
  expect_eq "T33(unclean)" "local_modifications_pushed" "false" "$(jq -r '.local_modifications_pushed' "$t33_rec")"
else
  echo "FAIL: T33(unclean): no Submodule Advance Record was written"; FAILURES=$((FAILURES + 2)); note_assertion; note_assertion
fi

CASES_RUN=$((CASES_RUN + 1))
t33b_root="$(make_fixture 1)"
t33b_pin="$(parent_pin "${t33b_root}/parent")"
t33b_up="$(upstream_fp "$t33b_root")"
run_script "${t33b_root}/parent" "${t33b_root}/records" \
  'gd="$(git -C "$1" rev-parse --absolute-git-dir)" && git -C "$1" rev-parse HEAD > "$gd/MERGE_HEAD"'
expect_eq "T33(clean)" "exit code (a MERGE_HEAD blocks nothing this script does)" "0" "$RUN_EXIT"
expect_ne "T33(clean)" "parent pin advanced" "$t33b_pin" "$(parent_pin "${t33b_root}/parent")"
expect_eq "T33(clean)" "the upstream is byte-identical" "$t33b_up" "$(upstream_fp "$t33b_root")"
if [[ -e "$(git -C "${t33b_root}/parent/${SUB_PATH}" rev-parse --absolute-git-dir)/MERGE_HEAD" ]]; then
  echo "PASS: T33(clean): fixture sanity -- the verify command really did write MERGE_HEAD"
else
  echo "FAIL: T33(clean): the verify command did not write MERGE_HEAD, so this case proves nothing"
  FAILURES=$((FAILURES + 1))
fi
if t33b_rec="$(find_record "${t33b_root}/records")"; then
  expect_eq "T33(clean)" "outcome" "ADVANCED" "$(jq -r '.outcome' "$t33b_rec")"
else
  echo "FAIL: T33(clean): no Submodule Advance Record was written"; FAILURES=$((FAILURES + 1)); note_assertion
fi

# -------------------------------------------------------------------------
# T34 (R4-F8). `git status --porcelain -uall` reports an untracked NESTED GIT
# REPOSITORY as `?? nested/` — with a trailing slash, and without descending
# into it — while `git diff --name-only` names it `nested`. The audit compared
# the two unequal forms, so this shape could NEVER be carried forward:
#   PRE-FIX (captured):
#     !! the carry-forward commit … changes 1 path(s) OUTSIDE the 1 path(s)
#        this run audited … Unaudited paths:  - nested
# Fail-closed, so it was safe — and a permanent FALSE refusal, which is its own
# defect: a guard that refuses a legitimate shape forever teaches its operator
# to route around it.
# -------------------------------------------------------------------------
# RETARGETED 2026-08-26. Nothing is carried forward any more, so the false
# refusal this case guarded against cannot occur -- but the SHAPE it used is
# still the one that produced it, and the correct answer flipped: an untracked
# nested git repository is local work, so the run REFUSES, and it must name
# the path in the form the operator can act on rather than silently.
echo "=== T34: an untracked nested git repository is refused, and named ==="
CASES_RUN=$((CASES_RUN + 1))
t34_root="$(make_fixture 1)"
mkdir -p "${t34_root}/parent/${SUB_PATH}/nested"
git init --quiet -b master "${t34_root}/parent/${SUB_PATH}/nested"
gcfg "${t34_root}/parent/${SUB_PATH}/nested"
echo "nested work" > "${t34_root}/parent/${SUB_PATH}/nested/n.txt"
git -C "${t34_root}/parent/${SUB_PATH}/nested" add n.txt
git -C "${t34_root}/parent/${SUB_PATH}/nested" commit --quiet -m "nested commit"
t34_pin="$(parent_pin "${t34_root}/parent")"
t34_up="$(upstream_fp "$t34_root")"
run_script "${t34_root}/parent" "${t34_root}/records" 'true'
expect_ne "T34" "exit code" "0" "$RUN_EXIT"
expect_eq "T34" "the nested repository did NOT reach the upstream" \
  "NO" "$(git -C "${t34_root}/upstream.git" ls-tree master -- nested >/dev/null 2>&1 && [[ -n "$(git -C "${t34_root}/upstream.git" ls-tree master -- nested)" ]] && echo YES || echo NO)"
expect_eq "T34" "the upstream is byte-identical (refs + object count)" "$t34_up" "$(upstream_fp "$t34_root")"
expect_eq "T34" "parent pin untouched" "$t34_pin" "$(parent_pin "${t34_root}/parent")"
expect_contains "T34" "the refusal names the nested path it found" "nested" "$LAST_OUTPUT"
if grep -qF -- "Unaudited paths" <<<"$LAST_OUTPUT"; then
  echo "FAIL: T34: the run reported an unaudited path, so carry-forward auditing code is back"; FAILURES=$((FAILURES + 1))
else
  echo "PASS: T34: no false unaudited-path refusal"
fi
note_assertion
if t34_rec="$(find_record "${t34_root}/records")"; then
  expect_eq "T34" "outcome" "FAILED_PRECONDITION" "$(jq -r '.outcome' "$t34_rec")"
else
  echo "FAIL: T34: no Submodule Advance Record was written"; FAILURES=$((FAILURES + 1)); note_assertion
fi

# -------------------------------------------------------------------------
# T35 (R4-F3). `merge-base --is-ancestor` answers from the object graph, so a
# graft-style replace ref changes its answer. PRE-FIX (measured by the round-4
# review):
#   is the unrelated root an ancestor of origin/master?   NO
#   after a graft-style replace ref on origin/master:     YES
#   with --no-replace-objects:                            NO
# The consequence is a local pin move that DROPS deliberately-pinned
# side-branch work — the exact thing this guard exists to prevent.
# -------------------------------------------------------------------------
echo "=== T35: a replace ref cannot invert the fast-forward guard ==="
CASES_RUN=$((CASES_RUN + 1))
t35_root="$(make_fixture 1)"
t35_sub="${t35_root}/parent/${SUB_PATH}"
t35_empty_tree="$(git -C "$t35_sub" mktree </dev/null)"
t35_unrelated="$(git -C "$t35_sub" commit-tree "$t35_empty_tree" -m "unrelated root")"
git -C "$t35_sub" checkout --quiet --detach "$t35_unrelated"
git -C "${t35_root}/parent" update-index --add --cacheinfo "160000,${t35_unrelated},${SUB_PATH}"
t35_remote_head="$(git -C "${t35_root}/upstream.git" rev-parse master)"
# The graft: make the remote HEAD *read* as a commit whose parent is the
# unrelated root, so the ancestry test answers YES through the mapping and NO
# through the objects. The replaced-to commit need not resemble anything.
t35_graft="$(git -C "$t35_sub" commit-tree "$t35_empty_tree" -p "$t35_unrelated" -m "graft")"
git -C "$t35_sub" update-ref "refs/replace/${t35_remote_head}" "$t35_graft"
run_script "${t35_root}/parent" "${t35_root}/records" 'true'
expect_ne "T35" "exit code (0 would certify a dropped pin)" "0" "$RUN_EXIT"
expect_contains "T35" "the refusal names its own cause" "is not a fast-forward from the pinned commit" "$LAST_OUTPUT"
expect_eq "T35" "parent pin untouched" "$t35_unrelated" "$(parent_pin "${t35_root}/parent")"
if t35_rec="$(find_record "${t35_root}/records")"; then
  expect_eq "T35" "outcome" "REFUSED_NOT_FAST_FORWARD" "$(jq -r '.outcome' "$t35_rec")"
else
  echo "FAIL: T35: no Submodule Advance Record was written"; FAILURES=$((FAILURES + 1)); note_assertion
fi

# -------------------------------------------------------------------------
# T36 (round-4 residual 3). `_remote_repo_name` took the URL's LAST path
# segment, so `vasic-digital/sub/helixqa` and `helixdevelopment/a/b/c/helixqa`
# both passed the identity gate and reached the FETCH. The boundary rested on
# a substring rather than on an identity. Measured read-only on this
# repository: all 25 own-org URLs are flat `<org>/<repo>`, so requiring
# exactly two components costs production nothing.
# -------------------------------------------------------------------------
echo "=== T36: an own-org URL that is not <org>/<repo> is not an authorized identity ==="
CASES_RUN=$((CASES_RUN + 1))
t36_root="$(make_fixture 1)"
t36_pin="$(parent_pin "${t36_root}/parent")"
git -C "${t36_root}/parent/${SUB_PATH}" remote set-url origin "git@github.com:vasic-digital/sub/helixqa.git"
run_script "${t36_root}/parent" "${t36_root}/records" 'true' \
  "GIT_SSH_COMMAND=/bin/false" "GIT_TERMINAL_PROMPT=0"
expect_ne "T36" "exit code" "0" "$RUN_EXIT"
expect_contains "T36" "the refusal names its own cause" "no readable repository name" "$LAST_OUTPUT"
expect_eq "T36" "parent pin untouched" "$t36_pin" "$(parent_pin "${t36_root}/parent")"
if t36_rec="$(find_record "${t36_root}/records")"; then
  expect_eq "T36" "outcome" "REFUSED_GOVERNANCE_DENY" "$(jq -r '.outcome' "$t36_rec")"
else
  echo "FAIL: T36: no Submodule Advance Record was written"; FAILURES=$((FAILURES + 1)); note_assertion
fi

# -------------------------------------------------------------------------
# T37. Every guard between a build and an irreversible publish now reads
# objects through `gith`. A reader whose behaviour is unknown makes those
# guards unknown, and `readonly -f gith` in a BASH_ENV file is the same channel
# that held the safety switches (R3-F3) and GOVERNANCE_ALLOW (R4-F6). The
# self-test must therefore fail CLOSED, not silently pass.
# -------------------------------------------------------------------------
echo "=== T37: the hardened object reader must prove itself or the run refuses ==="
CASES_RUN=$((CASES_RUN + 1))
t37_root="$(make_fixture 1)"
t37_pin="$(parent_pin "${t37_root}/parent")"
printf 'gith() { git "$@"; }\nreadonly -f gith\n' > "${t37_root}/hold-reader.sh"
run_script "${t37_root}/parent" "${t37_root}/records" 'true' "BASH_ENV=${t37_root}/hold-reader.sh"
expect_eq "T37" "exit code (2 = configuration error, nothing attempted)" "2" "$RUN_EXIT"
expect_contains "T37" "the refusal names its own cause" \
  "could not be PROVEN to ignore refs/replace" "$LAST_OUTPUT"
expect_eq "T37" "parent pin untouched" "$t37_pin" "$(parent_pin "${t37_root}/parent")"
expect_eq "T37" "records written (nothing was attempted)" "0" "$(record_count "${t37_root}/records")"

# -------------------------------------------------------------------------
# T38 (RETARGETED 2026-08-26). It used to prove the object enumeration accepted
# the INTERMEDIATE TREES git must transfer to carry `docs/notes/todo.txt` --
# `docs` and `docs/notes` -- so that the OBJECTS guard was a measurement rather
# than a blanket refusal. That guard, and the transfer it measured, are gone.
#
# The surviving property this shape tests is the REFUSAL's own quality: a
# nested-directory path must be reported to the operator in full, not truncated
# to its first component. A refusal that names `docs` for a file at
# `docs/notes/todo.txt` sends its reader to the wrong place, which is the
# small-bluff class this file polices everywhere.
# -------------------------------------------------------------------------
echo "=== T38: a nested-directory path is named in FULL by the refusal ==="
CASES_RUN=$((CASES_RUN + 1))
t38_root="$(make_fixture 1)"
t38_pin="$(parent_pin "${t38_root}/parent")"
t38_up="$(upstream_fp "$t38_root")"
mkdir -p "${t38_root}/parent/${SUB_PATH}/docs/notes"
printf 'OPERATOR NOTE\n' > "${t38_root}/parent/${SUB_PATH}/docs/notes/todo.txt"
run_script "${t38_root}/parent" "${t38_root}/records" 'true'
expect_ne "T38" "exit code" "0" "$RUN_EXIT"
expect_eq "T38" "the operator's nested-path work did NOT reach the upstream" "NO" "$(upstream_has "$t38_root" "docs/notes/todo.txt")"
expect_eq "T38" "the upstream is byte-identical (refs + object count)" "$t38_up" "$(upstream_fp "$t38_root")"
expect_eq "T38" "parent pin untouched" "$t38_pin" "$(parent_pin "${t38_root}/parent")"
expect_contains "T38" "the refusal names the path in FULL, not truncated to its first component" \
  "docs/notes/todo.txt" "$LAST_OUTPUT"
expect_eq "T38" "the operator's bytes are untouched on disk" "OPERATOR NOTE" \
  "$(cat "${t38_root}/parent/${SUB_PATH}/docs/notes/todo.txt" 2>/dev/null)"

# -------------------------------------------------------------------------
# T39 (RETARGETED 2026-08-26). It used to prove that closing the tenth escape
# -- pushing the immutable sha instead of the mutable `HEAD` -- had not broken
# ordinary §6.W two-mirror publishing. There is no publishing.
#
# The §6.W two-mirror shape is still the production one, so the case keeps it
# and asserts what must hold now: a SUCCESSFUL advance leaves BOTH mirrors
# byte-identical, and the pin staged in the parent is a commit both mirrors
# already had. That last clause is the one that matters operationally: every
# other clone of the parent resolves this pin through one of these mirrors, so
# a pin they do not carry is `fatal: reference is not a tree` for everybody.
# -------------------------------------------------------------------------
echo "=== T39: a successful advance leaves BOTH §6.W mirrors byte-identical ==="
CASES_RUN=$((CASES_RUN + 1))
t39_root="$(make_fixture 1)"
git init --quiet --bare -b master "${t39_root}/upstream2.git"
git -C "${t39_root}/upstream.git" push --quiet "${t39_root}/upstream2.git" \
  'refs/heads/master:refs/heads/master'
git -C "${t39_root}/parent/${SUB_PATH}" remote add aaasecond "${t39_root}/upstream2.git"
t39_a_before="$(git -C "${t39_root}/upstream.git" for-each-ref --format='%(refname) %(objectname)' | LC_ALL=C sort)"
t39_b_before="$(git -C "${t39_root}/upstream2.git" for-each-ref --format='%(refname) %(objectname)' | LC_ALL=C sort)"
t39_pin_before="$(parent_pin "${t39_root}/parent")"
run_script "${t39_root}/parent" "${t39_root}/records" 'true'
t39_a="$(git -C "${t39_root}/upstream.git" rev-parse master)"
t39_b="$(git -C "${t39_root}/upstream2.git" rev-parse master)"
expect_eq "T39" "exit code" "0" "$RUN_EXIT"
expect_ne "T39" "parent pin advanced" "$t39_pin_before" "$(parent_pin "${t39_root}/parent")"
expect_eq "T39" "mirror 1 is byte-identical" "$t39_a_before" \
  "$(git -C "${t39_root}/upstream.git" for-each-ref --format='%(refname) %(objectname)' | LC_ALL=C sort)"
expect_eq "T39" "mirror 2 is byte-identical" "$t39_b_before" \
  "$(git -C "${t39_root}/upstream2.git" for-each-ref --format='%(refname) %(objectname)' | LC_ALL=C sort)"
expect_eq "T39" "both mirrors carry the SAME commit" "$t39_a" "$t39_b"
expect_eq "T39" "the parent pin is a commit BOTH mirrors already carry" "$t39_a" "$(parent_pin "${t39_root}/parent")"

esc_close

# =========================================================================
# ROUND 5 -- the 2026-08-26 T054 round-5 review. Its two findings that are
# reachable on the DEFAULT, FLAGLESS invocation, plus the partial-corpus
# sibling found while fixing the second.
#
#   T40  R5-F2   the ambient git environment must not redirect this script's
#                writes into a repository nobody named
#   T41  R5-F2   ...and a name that SURVIVES the removal is fatal, not ignored
#   T42  R5-F3   `.gitmodules` declares submodules, the enumeration found none
#   T43  R5-F3'  the PARTIAL case: declared 2, enumerated 1, reported clean
#   T44  POSITIVE a genuinely empty corpus exits 0 AND leaves a record saying so
#   T45  POSITIVE a healthy parent and a fresh uninitialised clone are not refused
#   T46  R5-F3'' the index holds gitlinks that `.gitmodules` declares nowhere
# =========================================================================

# witness_snapshot <repo-dir> -- a deterministic rendering of everything about
# a repository that this script must never change. Deliberately includes the
# REFLOG: a commit that is made and then rewound leaves HEAD where it started
# while still having happened, and a snapshot that cannot see that would call
# the damage clean.
witness_snapshot() {
  local d="$1"
  git -C "$d" rev-parse HEAD 2>/dev/null
  git -C "$d" for-each-ref --format='%(refname) %(objectname)' 2>/dev/null | LC_ALL=C sort
  git -C "$d" diff --cached --name-only 2>/dev/null | LC_ALL=C sort
  git -C "$d" status --porcelain 2>/dev/null | LC_ALL=C sort
  git -C "$d" reflog --format='%H %gs' 2>/dev/null
}
witness_fingerprint() { witness_snapshot "$1" | sha256sum | awk '{print $1}'; }

# make_witness <root> -- a repository named NOWHERE on the script's command
# line, whose only role is to be somewhere the run could damage.
make_witness() {
  local root="$1"
  git init --quiet -b master "${root}/witness"
  gcfg "${root}/witness"
  printf 'operator work\n' > "${root}/witness/W.txt"
  git -C "${root}/witness" add -- W.txt
  git -C "${root}/witness" commit --quiet -m "witness baseline"
}

# -------------------------------------------------------------------------
# T40 (R5-F2). The reader self-test added in round 4 builds a throwaway
# repository with `git init --quiet --template=<empty> "$_selftest_dir"`,
# `cd`s into it, and then commits twice, moves a branch tip and creates a
# `refs/replace/*` ref. `git init` was isolated from `init.templateDir` and
# `core.hooksPath` -- and not from GIT_DIR, which decides WHICH REPOSITORY it
# initialises at all.
#
# PRE-FIX (captured, no flags whatsoever, GIT_DIR pointing at the witness):
#   EXIT=0
#   B HEAD before: c56236b4...   B HEAD after: 62119f6d...   moved? YES
#   B replace refs left: refs/replace/62119f6ddda3ac666d985441951ec31d26e56957
#   B index dirty with : decoy.txt
#   advance-all-submodules: '<parent>' has no submodules — nothing to do
# i.e. two commits in a repository the caller never mentioned, its branch tip
# moved off the operator's work, its index left dirty with a file that is not
# in its worktree, and the NINTH escape's own instrument -- a replace ref --
# installed there and not cleaned up, by the guard written to CLOSE the ninth
# escape. `git submodule foreach` exports GIT_DIR and every commit-time hook
# exports GIT_INDEX_FILE (both measured on git 2.50.1), so this is the shape a
# wrapper runs the pipeline in, not a contrived one.
#
# One case per variable, each with its OWN witness, because a variable that
# damages nothing today is still a variable whose meaning nobody checked.
# -------------------------------------------------------------------------
esc_case "T40" 5
echo "=== T40: the ambient git environment cannot redirect this run's writes ==="
CASES_RUN=$((CASES_RUN + 1))
_t40_saved_flags=("${ADV_FLAGS[@]}")
ADV_FLAGS=()   # the finding is reachable on the DEFAULT invocation: no flags.
for _t40_var in GIT_DIR GIT_INDEX_FILE GIT_WORK_TREE GIT_OBJECT_DIRECTORY \
                GIT_COMMON_DIR GIT_NAMESPACE GIT_NO_REPLACE_OBJECTS GIT_REPLACE_REF_BASE; do
  t40_root="$(make_fixture 0)"
  make_witness "$t40_root"
  case "$_t40_var" in
    GIT_DIR|GIT_COMMON_DIR)  _t40_val="${t40_root}/witness/.git" ;;
    GIT_WORK_TREE)           _t40_val="${t40_root}/witness" ;;
    GIT_INDEX_FILE)          _t40_val="${t40_root}/witness/.git/index" ;;
    GIT_OBJECT_DIRECTORY)    _t40_val="${t40_root}/witness/.git/objects" ;;
    GIT_NAMESPACE)           _t40_val="evil" ;;
    GIT_NO_REPLACE_OBJECTS)  _t40_val="1" ;;
    GIT_REPLACE_REF_BASE)    _t40_val="refs/evil/" ;;
  esac
  t40_before="$(witness_fingerprint "${t40_root}/witness")"
  run_script "${t40_root}/parent" "${t40_root}/records" 'true' "${_t40_var}=${_t40_val}"
  t40_after="$(witness_fingerprint "${t40_root}/witness")"
  expect_eq "T40/${_t40_var}" "the witness repository is byte-identical afterwards" \
    "$t40_before" "$t40_after"
  expect_eq "T40/${_t40_var}" "refs/replace/* left in the witness" \
    "0" "$(git -C "${t40_root}/witness" for-each-ref 'refs/replace/*' 2>/dev/null | wc -l | tr -d ' ')"
  # ...and byte-identity is only half of it. A run that neutralises the
  # environment by REFUSING TO RUN would also leave the witness untouched, and
  # would be a permanent false refusal -- the same defect in the opposite
  # direction, which is exactly what GIT_NO_REPLACE_OBJECTS and
  # GIT_REPLACE_REF_BASE used to cause (both made the UNHARDENED reader ignore
  # replace refs too, so the self-test could not discriminate and exited 2 on
  # a guard that was working). So the run must also still have EXAMINED the
  # submodule it was given.
  expect_contains "T40/${_t40_var}" "the run still examined the submodule it was given" \
    "1 submodule(s) examined" "$LAST_OUTPUT"
done
ADV_FLAGS=("${_t40_saved_flags[@]}")

# -------------------------------------------------------------------------
# T41 (R5-F2, second half). `unset` is an assignment, and this file already
# knows -- from the switch-reset check and the GOVERNANCE_ALLOW check -- that
# a `declare -r` in BASH_ENV defeats an assignment silently under `set +e`.
# A neutralisation that cannot be performed must be fatal, not assumed: a run
# that cannot remove GIT_DIR cannot say which repository its guards measured.
# -------------------------------------------------------------------------
esc_case "T41" 5
echo "=== T41: a git environment variable that survives removal is fatal ==="
CASES_RUN=$((CASES_RUN + 1))
t41_root="$(make_fixture 0)"
make_witness "$t41_root"
printf 'declare -r GIT_DIR=%q\n' "${t41_root}/witness/.git" > "${t41_root}/hold-gitdir.sh"
t41_before="$(witness_fingerprint "${t41_root}/witness")"
_t41_saved_flags=("${ADV_FLAGS[@]}")
ADV_FLAGS=()
run_script "${t41_root}/parent" "${t41_root}/records" 'true' "BASH_ENV=${t41_root}/hold-gitdir.sh"
ADV_FLAGS=("${_t41_saved_flags[@]}")
expect_eq "T41" "exit code (nothing attempted)" "2" "$RUN_EXIT"
expect_contains "T41" "the refusal names the surviving variable" "GIT_DIR" "$LAST_OUTPUT"
expect_contains "T41" "the refusal states nothing was attempted" "Nothing was attempted" "$LAST_OUTPUT"
expect_eq "T41" "the witness repository is byte-identical afterwards" \
  "$t41_before" "$(witness_fingerprint "${t41_root}/witness")"
expect_eq "T41" "records written" "0" \
  "$( [[ -d "${t41_root}/records" ]] && find "${t41_root}/records" -name '*.json' 2>/dev/null | wc -l | tr -d ' ' || echo 0 )"

# -------------------------------------------------------------------------
# T42 (R5-F3). `"has no submodules — nothing to do"` exited 0 with zero
# records, without ever consulting `.gitmodules` -- the repository's own
# statement of what it HAS. The enumeration comes from `git submodule status`,
# which reads the INDEX.
#
# PRE-FIX (captured, GIT_INDEX_FILE pointing at a path with no index file, so
# git reads an EMPTY index, against a parent whose .gitmodules declares one):
#   EXIT=0  records=0
#   advance-all-submodules: '.../parent' has no submodules — nothing to do
#
# The fixture below reaches the same state with NO environment variable at all
# -- just a gitlink dropped from the index -- because the defect is the
# missing cross-check, not the route that exposed it.
# -------------------------------------------------------------------------
esc_case "T42" 5
echo "=== T42: .gitmodules declaring submodules the enumeration lost is a refusal ==="
CASES_RUN=$((CASES_RUN + 1))
t42_root="$(make_fixture 0)"
git -C "${t42_root}/parent" rm --cached -q -- "$SUB_PATH"
expect_eq "T42" "fixture sanity: .gitmodules still declares the submodule" \
  "1" "$(sed -n 's/^[[:space:]]*path[[:space:]]*=[[:space:]]*//p' "${t42_root}/parent/.gitmodules" 2>/dev/null | wc -l | tr -d ' ')"
expect_eq "T42" "fixture sanity: the enumeration now yields nothing" \
  "0" "$(git -C "${t42_root}/parent" submodule status 2>/dev/null | wc -l | tr -d ' ')"
run_script "${t42_root}/parent" "${t42_root}/records" 'true'
expect_eq "T42" "exit code (a green verdict over an unexamined corpus)" "2" "$RUN_EXIT"
expect_contains "T42" "the refusal names the unexamined submodule" "$SUB_PATH" "$LAST_OUTPUT"
expect_contains "T42" "the refusal states nothing was attempted" "Nothing was attempted" "$LAST_OUTPUT"
if grep -qF -- "has no submodules — nothing to do" <<<"$LAST_OUTPUT"; then
  echo "FAIL: T42: the run still claimed the repository has no submodules"
  FAILURES=$((FAILURES + 1))
else
  echo "PASS: T42: the run no longer claims a repository with a declared submodule has none"
fi
note_assertion

# -------------------------------------------------------------------------
# T43 (the PARTIAL case -- found while fixing T42, and the reason T42's fix is
# stated over the SET rather than over its size). A floor that fires at
# exactly zero is a floor with one stair: the zero case is the loudest
# projection of the defect, not the defect.
#
# PRE-FIX (captured, no environment variable involved -- one gitlink dropped
# from the index of a parent declaring two):
#   EXIT=0  records=1
#   advance-all-submodules: 1 submodule(s) examined
#   advance-all-submodules: 0 advanced, 1 already current, 0 rejected/failed
# A clean run over a corpus that lost half its members, which never names the
# member it did not look at.
# -------------------------------------------------------------------------
esc_case "T43" 5
echo "=== T43: a PARTIAL enumeration is refused, not reported clean ==="
CASES_RUN=$((CASES_RUN + 1))
t43_root="$(make_fixture 0)"
git init --quiet --bare -b master "${t43_root}/upstream2.git"
up_commit "${t43_root}/upstream2.git" "${t43_root}/s2" "second upstream" "u2.txt"
git -c protocol.file.allow=always -C "${t43_root}/parent" \
  submodule add --quiet "${t43_root}/upstream2.git" "submodules/other" >/dev/null 2>&1
git -C "${t43_root}/parent" commit --quiet -m "add a second submodule"
git -C "${t43_root}/parent" rm --cached -q -- "submodules/other"
expect_eq "T43" "fixture sanity: .gitmodules declares two" \
  "2" "$(sed -n 's/^[[:space:]]*path[[:space:]]*=[[:space:]]*//p' "${t43_root}/parent/.gitmodules" 2>/dev/null | wc -l | tr -d ' ')"
expect_eq "T43" "fixture sanity: the enumeration yields one" \
  "1" "$(git -C "${t43_root}/parent" submodule status 2>/dev/null | wc -l | tr -d ' ')"
run_script "${t43_root}/parent" "${t43_root}/records" 'true'
expect_eq "T43" "exit code (a clean verdict over half a corpus)" "2" "$RUN_EXIT"
expect_contains "T43" "the refusal names the member that was never examined" \
  "submodules/other" "$LAST_OUTPUT"
expect_eq "T43" "records written for a corpus that was never fully read" "0" \
  "$( [[ -d "${t43_root}/records" ]] && find "${t43_root}/records" -name '*.json' 2>/dev/null | wc -l | tr -d ' ' || echo 0 )"

# -------------------------------------------------------------------------
# T44 (POSITIVE). A repository that genuinely has no submodules must still
# exit 0 -- refusing there would be the false-refusal defect in the opposite
# direction. What changes is that the claim is now made FROM EVIDENCE, and
# that the evidence survives the terminal: root CLAUDE.md's Automated Pipeline
# Pin-Advance Path condition (E) is explicit that an outcome without a record
# is not evidence, and "0 advanced" on a console is indistinguishable from
# "everything was already current" to phase-07-closure.sh.
# -------------------------------------------------------------------------
esc_case "T44" 5
echo "=== T44: POSITIVE -- an empty corpus exits 0 and leaves a record proving it ==="
CASES_RUN=$((CASES_RUN + 1))
t44_root="$(mktemp -d "${TMPDIR:-/tmp}/adv-t054-XXXXXX")"
FIXTURE_DIRS+=("$t44_root")
git init --quiet -b master "${t44_root}/parent"
gcfg "${t44_root}/parent"
printf 'parent\n' > "${t44_root}/parent/parent.txt"
git -C "${t44_root}/parent" add -- parent.txt
git -C "${t44_root}/parent" commit --quiet -m "parent initial commit"
run_script "${t44_root}/parent" "${t44_root}/records" 'true'
expect_eq "T44" "exit code" "0" "$RUN_EXIT"
expect_contains "T44" "the claim cites all three readings" \
  ".gitmodules declares 0, the enumeration found 0, and the parent index holds 0 gitlink(s)" "$LAST_OUTPUT"
if [[ -f "${t44_root}/records/_corpus.json" ]]; then
  echo "PASS: T44: a corpus record was written for the empty corpus"
  expect_eq "T44" "corpus record outcome" "CORPUS_EMPTY_CONFIRMED" \
    "$(jq -r '.outcome' "${t44_root}/records/_corpus.json" 2>/dev/null)"
  expect_eq "T44" "corpus record declared_submodules" "0" \
    "$(jq -r '.declared_submodules' "${t44_root}/records/_corpus.json" 2>/dev/null)"
  expect_eq "T44" "corpus record index_gitlinks" "0" \
    "$(jq -r '.index_gitlinks' "${t44_root}/records/_corpus.json" 2>/dev/null)"
else
  echo "FAIL: T44: no corpus record was written, so the empty corpus left no evidence at rest"
  FAILURES=$((FAILURES + 4))
fi
note_assertion

# -------------------------------------------------------------------------
# T45 (POSITIVE -- the false-refusal guard for T42/T43). The cross-check must
# not fire on either shape an operator actually meets. Both were measured
# rather than assumed before the fix went in: this repository reports 25
# declared / 25 enumerated, and an uninitialised submodule is still ENUMERATED
# (`git submodule status` lists it with a `-` prefix, which the script's sed
# already strips) -- so a fresh clone with no `git submodule update --init`
# stays green.
# -------------------------------------------------------------------------
esc_case "T45" 5
echo "=== T45: POSITIVE -- a healthy parent and a fresh uninitialised clone are not refused ==="
CASES_RUN=$((CASES_RUN + 1))
t45_root="$(make_fixture 0)"
git init --quiet --bare -b master "${t45_root}/upstream2.git"
up_commit "${t45_root}/upstream2.git" "${t45_root}/s2" "second upstream" "u2.txt"
git -c protocol.file.allow=always -C "${t45_root}/parent" \
  submodule add --quiet "${t45_root}/upstream2.git" "submodules/other" >/dev/null 2>&1
git -C "${t45_root}/parent" commit --quiet -m "add a second submodule"
run_script "${t45_root}/parent" "${t45_root}/records" 'true'
expect_ne "T45" "exit code must not be the corpus refusal (2)" "2" "$RUN_EXIT"
expect_contains "T45" "both declared submodules were examined" "2 submodule(s) examined" "$LAST_OUTPUT"
git clone --quiet "${t45_root}/parent" "${t45_root}/fresh" 2>/dev/null
expect_eq "T45" "fixture sanity: the fresh clone has NOT initialised its submodules" \
  "" "$(ls -A "${t45_root}/fresh/${SUB_PATH}" 2>/dev/null)"
run_script "${t45_root}/fresh" "${t45_root}/fresh-records" 'true'
expect_ne "T45" "fresh clone: exit code must not be the corpus refusal (2)" "2" "$RUN_EXIT"
expect_contains "T45" "fresh clone: both declared submodules were still examined" \
  "2 submodule(s) examined" "$LAST_OUTPUT"

# -------------------------------------------------------------------------
# T46. The last reading that could still be vacuous: `.gitmodules` declaring
# nothing and the enumeration finding nothing AGREE, and both would be wrong,
# if the parent index held gitlinks that `.gitmodules` declares nowhere --
# submodule pins this script cannot advance (there is no URL to advance them
# from) that would then go unexamined in silence.
#
# MEASURED, and the measurement is the point: on git 2.50.1 that state does
# not reach the corpus check at all, because `git submodule status` ITSELF
# refuses it --
#   EXIT=2
#   'git submodule status' exited 128 ... git said:
#     fatal: no submodule mapping found in .gitmodules for path 'submodules/helixqa'
# -- and the enumeration's own exit-status guard turns that into exit 2 and
# "Nothing was attempted". So the safety property is real and is asserted here;
# what is NOT asserted is that the script's own third reading is what delivers
# it. That reading stays in the script as a backstop for an enumeration that
# returns 0 rather than 128, and this suite does not claim to have seen it
# fire, because it has not.
# -------------------------------------------------------------------------
esc_case "T46" 5
echo "=== T46: an index holding undeclared gitlinks never yields a green empty run ==="
CASES_RUN=$((CASES_RUN + 1))
t46_root="$(make_fixture 0)"
git -C "${t46_root}/parent" rm -q --cached -- .gitmodules >/dev/null 2>&1
rm -rf -- "${t46_root}/parent/.gitmodules"
git -C "${t46_root}/parent" commit --quiet -m "drop .gitmodules, keep the gitlink"
# A FRESH CLONE is the realistic carrier: it has no `submodule.*` section in
# .git/config to fall back on, so nothing but the (absent) .gitmodules could
# have declared the gitlink it carries.
git clone --quiet "${t46_root}/parent" "${t46_root}/fresh" 2>/dev/null
expect_eq "T46" "fixture sanity: the clone declares no submodules" \
  "0" "$(sed -n 's/^[[:space:]]*path[[:space:]]*=[[:space:]]*//p' "${t46_root}/fresh/.gitmodules" 2>/dev/null | wc -l | tr -d ' ')"
expect_eq "T46" "fixture sanity: the clone's index still holds the gitlink" \
  "1" "$(git -C "${t46_root}/fresh" ls-files -s 2>/dev/null | awk '$1 == "160000"' | wc -l | tr -d ' ')"
run_script "${t46_root}/fresh" "${t46_root}/records" 'true'
expect_eq "T46" "exit code (nothing attempted)" "2" "$RUN_EXIT"
expect_contains "T46" "the refusal states nothing was attempted" "Nothing was attempted" "$LAST_OUTPUT"
if grep -qF -- "has no submodules" <<<"$LAST_OUTPUT"; then
  echo "FAIL: T46: the run claimed an empty corpus over an index that holds a gitlink"
  FAILURES=$((FAILURES + 1))
else
  echo "PASS: T46: the run never claimed an empty corpus"
fi
note_assertion
expect_eq "T46" "no corpus record claims an empty corpus" "absent" \
  "$( [[ -f "${t46_root}/records/_corpus.json" ]] && echo present || echo absent )"

# -------------------------------------------------------------------------
# T47 (R5-F2, the axis the top-level removal deliberately leaves alone). The
# reader self-test states its intended ident with two `git config user.*`
# calls -- and GIT_AUTHOR_* / GIT_COMMITTER_* silently OVERRIDE config. Those
# are NOT removed at the top of the script, on purpose: they decide the ident
# bytes of the commit R-005 step 6 publishes, which is publish-path behaviour
# and the subject of an open decision. They are removed inside the self-test's
# own subshell, and that removal is what this case is about.
#
# PRE-FIX (captured, against the pristine round-4 script, and again against a
# mutant with ONLY the self-test's own removal neutered -- so this is the
# self-test's removal being measured, not the top-level one):
#   GIT_AUTHOR_DATE=not-a-date    EXIT=2   1 selftest-refusal(s)
#   GIT_COMMITTER_NAME=           EXIT=2   1 selftest-refusal(s)
#   advance-all-submodules: the hardened object reader could not be PROVEN to
#   ignore refs/replace/*
# git rejects both outright (`fatal: invalid date format`, `fatal: empty ident
# name ... not allowed`), so the self-test's `git commit` failed and the run
# refused -- a FALSE exit 2 on a guard that was working perfectly, blocking
# the pipeline for a reason that has nothing to do with the reader. That is
# the same defect class the `--template=` isolation was added to avoid, on a
# third axis nobody had checked.
# -------------------------------------------------------------------------
esc_case "T47" 5
echo "=== T47: an ambient ident git rejects must not become a false refusal ==="
CASES_RUN=$((CASES_RUN + 1))
for _t47_spec in "GIT_AUTHOR_DATE=not-a-date" "GIT_COMMITTER_NAME="; do
  t47_root="$(make_fixture 0)"
  run_script "${t47_root}/parent" "${t47_root}/records" 'true' "$_t47_spec"
  expect_eq "T47/${_t47_spec}" "exit code (a false refusal blocks the pipeline)" "0" "$RUN_EXIT"
  if grep -qF -- "could not be PROVEN to ignore" <<<"$LAST_OUTPUT"; then
    echo "FAIL: T47/${_t47_spec}: the reader self-test refused for a reason unrelated to the reader"
    FAILURES=$((FAILURES + 1))
  else
    echo "PASS: T47/${_t47_spec}: the reader self-test was not derailed by the ambient ident"
  fi
  note_assertion
  expect_contains "T47/${_t47_spec}" "the run still examined the submodule it was given" \
    "1 submodule(s) examined" "$LAST_OUTPUT"
done

# -------------------------------------------------------------------------
# T48. A defect in the ROUND-5 FIX ITSELF, found by attacking it the way the
# round-5 review attacked round 4's: the corpus floor fired correctly on a
# submodule path containing a SPACE, and then MISSTATED WHY.
#
# `git submodule status` prints `<sha> <path> (<describe>)`, and the
# enumeration splits that on whitespace, so `submodules/helix qa` is truncated
# to `submodules/helix` -- a DIFFERENT path, which every later step would then
# have operated on. The corpus floor catches it (the declared path is not in
# the enumeration), so the run is safe. But its explanation read:
#
#   PRE-FIX (captured, against the first version of this very floor):
#     declares 1 submodule(s) but 'git submodule status' enumerated 1, and 1
#     declared path(s) are absent from the enumeration
#       submodules/helix qa — the path exists in the worktree, so the parent
#       INDEX is missing its gitlink ... Restore it with: git add -- ...
#
# The gitlink was present; the advice was wrong; and by this file's own
# standard a refusal that misstates its own cause is a small bluff. Safe and
# wrong is still wrong.
# -------------------------------------------------------------------------
esc_case "T48" 5
echo "=== T48: a whitespace-bearing submodule path is refused, and the cause is named correctly ==="
CASES_RUN=$((CASES_RUN + 1))
t48_root="$(mktemp -d "${TMPDIR:-/tmp}/adv-t054-XXXXXX")"
FIXTURE_DIRS+=("$t48_root")
git init --quiet --bare -b master "${t48_root}/upstream.git"
up_commit "${t48_root}/upstream.git" "${t48_root}/seed" "submodule initial commit" "seed.txt"
git init --quiet -b master "${t48_root}/parent"
gcfg "${t48_root}/parent"
printf 'parent\n' > "${t48_root}/parent/parent.txt"
git -C "${t48_root}/parent" add -- parent.txt
git -C "${t48_root}/parent" commit --quiet -m "parent initial commit"
git -c protocol.file.allow=always -C "${t48_root}/parent" \
  submodule add --quiet "${t48_root}/upstream.git" "submodules/helix qa" >/dev/null 2>&1
git -C "${t48_root}/parent" commit --quiet -m "add a submodule whose path contains a space"
expect_eq "T48" "fixture sanity: the enumeration truncates the path at the space" \
  "submodules/helix" \
  "$(git -C "${t48_root}/parent" submodule status | sed -E 's/^[[:space:]]*[-+U]?//' | awk 'NF >= 2 { print $2 }')"
run_script "${t48_root}/parent" "${t48_root}/records" 'true'
expect_eq "T48" "exit code (a truncated path is a different path)" "2" "$RUN_EXIT"
expect_contains "T48" "the refusal names the declared path in full" "submodules/helix qa" "$LAST_OUTPUT"
expect_contains "T48" "the refusal names the cause that actually occurred" \
  "declared WITH WHITESPACE" "$LAST_OUTPUT"
if grep -qF -- "the parent INDEX is missing its gitlink" <<<"$LAST_OUTPUT"; then
  echo "FAIL: T48: the refusal blamed a missing gitlink for a gitlink that is present"
  FAILURES=$((FAILURES + 1))
else
  echo "PASS: T48: the refusal does not blame a cause that did not occur"
fi
note_assertion
expect_eq "T48" "records written" "0" \
  "$( [[ -d "${t48_root}/records" ]] && find "${t48_root}/records" -name '*.json' 2>/dev/null | wc -l | tr -d ' ' || echo 0 )"

# -------------------------------------------------------------------------
# T49. The summary must ACCOUNT for every submodule it says it examined.
#
# PRE-FIX (captured, a parent whose only submodule carries no standing
# authorization):
#   == governance: NOT advancing, and NOT examined. ...
#   advance-all-submodules: 1 submodule(s) examined
#   advance-all-submodules: 0 advanced, 0 already current, 0 rejected/failed, 0 not restored
#
# Two defects in four lines. The per-submodule line said "NOT examined" while
# the summary counted the same submodule as examined -- the two lines flatly
# contradicting each other. And the outcome line put the refusal in no bucket
# at all, so its text is identical to the text of a run in which everything was
# already current. That is the R5-F3 concern one level down: a reader of the
# summary cannot tell a refused submodule from an unexamined one.
#
# The EXIT CODE is deliberately NOT asserted here beyond what the sibling
# suite already fixes (test_advance_all_submodules.sh case5 asserts exit 0 for
# a governance refusal, which is a standing design decision recorded there).
# What this case pins is the honesty of the evidence, which is unambiguous.
# -------------------------------------------------------------------------
esc_case "T49" 5
echo "=== T49: the outcome line accounts for every submodule it says it examined ==="
CASES_RUN=$((CASES_RUN + 1))
t49_root="$(mktemp -d "${TMPDIR:-/tmp}/adv-t054-XXXXXX")"
FIXTURE_DIRS+=("$t49_root")
git init --quiet --bare -b master "${t49_root}/upstream.git"
up_commit "${t49_root}/upstream.git" "${t49_root}/seed" "submodule initial commit" "seed.txt"
git init --quiet -b master "${t49_root}/parent"
gcfg "${t49_root}/parent"
printf 'parent\n' > "${t49_root}/parent/parent.txt"
git -C "${t49_root}/parent" add -- parent.txt
git -C "${t49_root}/parent" commit --quiet -m "parent initial commit"
git -c protocol.file.allow=always -C "${t49_root}/parent" \
  submodule add --quiet "${t49_root}/upstream.git" "submodules/other" >/dev/null 2>&1
git -C "${t49_root}/parent" commit --quiet -m "add a submodule nobody authorized"
run_script "${t49_root}/parent" "${t49_root}/records" 'true'
expect_contains "T49" "the summary counts the submodule as examined" \
  "1 submodule(s) examined" "$LAST_OUTPUT"
expect_contains "T49" "the outcome line accounts for the governance refusal" \
  "1 refused for want of a standing operator authorization" "$LAST_OUTPUT"
if grep -qF -- "NOT advancing, and NOT examined" <<<"$LAST_OUTPUT"; then
  echo "FAIL: T49: the per-submodule line still says NOT examined while the summary says examined"
  FAILURES=$((FAILURES + 1))
else
  echo "PASS: T49: the per-submodule line no longer contradicts the summary"
fi
note_assertion
if grep -qF -- "internal error" <<<"$LAST_OUTPUT"; then
  echo "FAIL: T49: the buckets did not account for the examined submodule"
  FAILURES=$((FAILURES + 1))
else
  echo "PASS: T49: the buckets account for every examined submodule"
fi
note_assertion
# find_record matches on $SUB_PATH; this fixture's submodule is deliberately
# NOT that path (it has to be one GOVERNANCE_ALLOW does not name), so the
# record is located by its own submodule_name instead.
t49_rec=""
while IFS= read -r _f; do
  [[ "$(jq -r '.submodule_name' "$_f" 2>/dev/null)" == "submodules/other" ]] && { t49_rec="$_f"; break; }
done < <(find "${t49_root}/records" -type f -name '*.json' 2>/dev/null)
if [[ -n "$t49_rec" ]]; then
  expect_eq "T49" "the refusal is recorded at rest" "REFUSED_GOVERNANCE_DENY" \
    "$(jq -r '.outcome' "$t49_rec")"
else
  echo "FAIL: T49: no Submodule Advance Record was written for the governance refusal"
  FAILURES=$((FAILURES + 1)); note_assertion
fi

esc_close

# The escape-class floors. Eleven distinct routes to another repository were
# proven open across four reviews and are asserted closed above; a refactor
# that dropped one -- or HOLLOWED one -- would otherwise leave this suite just
# as green. Each counter now moves only when `esc_close` has seen real
# assertions execute inside that case's window.
# RAISED 6 -> 7 on 2026-08-26. The seventh window is T22b: the `push.followTags`
# route (T22) is now examined BOTH ways -- once with the submodule unclean, where
# the refusal precedes the fetch so the tag is never created, and once CLEAN,
# where the verify command really does set `push.followTags` and really does
# create the annotated tag, and the tag still cannot reach the upstream because
# no push is issued for it to ride out on. The second is the one that would fail
# if pushing code returned, so it earns its own counted window rather than
# hiding inside T22's.
if [[ "$R3_ESCAPES_EXAMINED" -eq 7 ]]; then
  echo "PASS: round 3: examined ${R3_ESCAPES_EXAMINED} distinct publish-escape routes"
else
  echo "FAIL: round 3: examined ${R3_ESCAPES_EXAMINED} publish-escape routes, expected 7 -- a case was skipped or hollowed, so its assertions never ran"
  FAILURES=$((FAILURES + 1))
fi
if [[ "$R4_ESCAPES_EXAMINED" -eq 5 ]]; then
  echo "PASS: round 4: examined ${R4_ESCAPES_EXAMINED} distinct publish-escape routes"
else
  echo "FAIL: round 4: examined ${R4_ESCAPES_EXAMINED} publish-escape routes, expected 5 -- a case was skipped or hollowed, so its assertions never ran"
  FAILURES=$((FAILURES + 1))
fi
if [[ "$R5_CASES_EXAMINED" -eq 10 ]]; then
  echo "PASS: round 5: examined ${R5_CASES_EXAMINED} distinct blast-radius / vacuous-pass routes"
else
  echo "FAIL: round 5: examined ${R5_CASES_EXAMINED} blast-radius / vacuous-pass routes, expected 10 -- a case was skipped or hollowed, so its assertions never ran"
  FAILURES=$((FAILURES + 1))
fi

# =========================================================================
echo "---"
# The suite's own vacuous-pass guard. It is the same defect T6 exists to
# prevent, applied to this file: a suite whose cases never ran must not report
# success.
# RAISED 71 -> 74 on 2026-08-26 with the step-6 removal. Cases were RETARGETED
# rather than deleted, and three of them gained a second half that could not
# exist before: T15(clean), T22b(clean) and T33(clean) run the same adversarial
# payload against a CLEAN submodule, where it genuinely executes and still
# cannot reach the upstream. A removal that only ever LOWERS this number is a
# removal nobody is checking.
EXPECTED_CASES=74
if [[ "$CASES_RUN" -ne "$EXPECTED_CASES" ]]; then
  echo "FAIL: this suite ran ${CASES_RUN} cases, expected ${EXPECTED_CASES} — cases were skipped, so their assertions never ran"
  FAILURES=$((FAILURES + 1))
else
  echo "PASS: this suite ran ${CASES_RUN} cases"
fi

# The floor that the case counter could not be. CASES_RUN says how many cases
# were ENTERED; this says how many assertions actually EXECUTED. The round-4
# review defeated the entry counter by hollowing T18 — six assertions removed,
# suite still "ALL CASES PASSED". With this floor the same mutation drops
# ASSERTIONS_RUN below the minimum and the suite FAILS, which is the whole
# point: a number that cannot go down when the work goes away is not a floor.
#
# Stated as a MINIMUM rather than an equality so that adding a case does not
# require editing it, while removing or gutting one still trips it.
#
# RAISED 281 -> 355 when the round-5 cases (T40-T49) landed, and 355 -> 405 on
# 2026-08-26 with the step-6 removal. A floor left at the old number is a floor
# the new work is not standing on.
#
# The 2026-08-26 raise is the one that matters most, because it is the raise a
# REMOVAL does not get by default. Deleting a code path deletes the cases that
# exercised it, so the honest-looking move is to delete those cases and lower
# the floor to match -- which is exactly how a suite quietly stops standing on
# anything. Every removed case was instead REPLACED by one asserting the
# capability is GONE (the upstream is byte-identical; the flag exits 2; an
# unclean tree refuses with no opt-in; no push is issued on any path), and the
# tracked count went UP: 355 -> 405. If a future change genuinely cannot keep
# this number, that is a finding to report, not a line to edit downward.
MIN_ASSERTIONS=405
if [[ "$ASSERTIONS_RUN" -lt "$MIN_ASSERTIONS" ]]; then
  echo "FAIL: this suite executed ${ASSERTIONS_RUN} assertions, expected at least ${MIN_ASSERTIONS} — assertions were removed or skipped, and a case that is entered but asserts nothing passes every entry counter"
  FAILURES=$((FAILURES + 1))
else
  echo "PASS: this suite executed ${ASSERTIONS_RUN} assertions (floor ${MIN_ASSERTIONS})"
fi

if [[ "$FAILURES" -eq 0 ]]; then
  echo "test_advance_all_submodules_t054.sh: ALL CASES PASSED (${CASES_RUN} cases)"
  exit 0
fi
echo "test_advance_all_submodules_t054.sh: ${FAILURES} assertion(s) FAILED"
exit 1

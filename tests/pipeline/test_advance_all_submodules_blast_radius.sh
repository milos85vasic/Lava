#!/usr/bin/env bash
# Blast-radius suite for scripts/advance-all-submodules.sh.
#
# tests/pipeline/test_advance_all_submodules.sh covers the four R-005 happy /
# rejection branches plus the governance deny; ..._hardening.sh covers the
# 2026-08-22 pre-T054 audit findings H1-H11. THIS suite covers the three
# findings of the 2026-08-23 pre-T054 blast-radius audit. Each case below was
# demonstrated against a disposable fixture BEFORE the fix, and each negative
# assertion here failed at that point.
#
# SAFETY: identical to its siblings. Every "upstream" is a bare repo inside a
# mktemp -d; nothing reaches a network host; nothing touches this repository's
# real submodules/ tree or constitution/; every fixture is removed on EXIT.
#
# Cases
#   B1  an existing-but-UNWRITABLE record directory must be refused up front
#       (exit 2, nothing attempted) rather than discovered only after the
#       submodule has been fetched, advanced and its pin staged
#   B1p POSITIVE: a writable record directory still advances normally
#   B1p POSITIVE: a writable record directory still advances normally
#
# ---------------------------------------------------------------------------
# REWRITTEN 2026-08-26, when R-005 step 6 and --publish-local-modifications
# were REMOVED. B2 / B2p / B2i / B3 / B3p measured the blast radius of the
# PUBLISH path -- which bytes step 6 carried to the submodule's upstream, and
# which it must not. That path no longer exists, so those cases could not be
# repaired, only replaced: a case asserting that a removed thing behaves
# correctly is a case asserting nothing.
#
# The blast radius this suite now measures is the one that remains: the set of
# repositories a run can WRITE TO AT ALL. Each replacement asserts the removed
# capability is GONE, on evidence, rather than that it is well-behaved.
#
#   B2r the upstream is BYTE-IDENTICAL after a run over a submodule carrying
#       local work -- refusal, no fetch, no ref moved, operator's bytes still
#       on disk untouched, and neither the operator's file nor the verify
#       build's output anywhere on the upstream
#   B2i a pre-existing untracked file the NEW upstream commit gitignores is
#       still local work at the moment it is measured, so it is REFUSED --
#       the H5 "nothing left to commit" branch it used to exercise is gone
#   B3r a CLEAN submodule advances, and BOTH §6.W mirrors plus an unrelated
#       witness repository are byte-identical in refs AND object count
#       afterwards: a successful advance writes to no repository but the
#       parent's index
#   B4  --publish-local-modifications is REFUSED, loudly, exit 2 -- never
#       ignored, and never falling through to be read as a repository path
#       (LVA-120: deleting an argument branch once let a flag reach `*) shift`
#       and silently become a different mode)
#   B5  the script contains no reachable `git push` at all
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

git_quiet_config() {
  git -C "$1" config user.email "pipeline-test@example.invalid"
  git -C "$1" config user.name "Pipeline Test"
  git -C "$1" config commit.gpgsign false
}

# publish_upstream_commit <bare> <scratch> <message> <filename> [content]
# Lands one commit on the bare fixture upstream's master via a throwaway clone.
publish_upstream_commit() {
  local bare="$1" scratch="$2" message="$3" filename="$4" content="${5:-}"
  git clone --quiet "$bare" "$scratch"
  git_quiet_config "$scratch"
  mkdir -p -- "$(dirname -- "${scratch}/${filename}")"
  printf '%s\n' "${content:-$message}" > "${scratch}/${filename}"
  git -C "$scratch" add -- "$filename"
  git -C "$scratch" commit --quiet -m "$message"
  git -C "$scratch" push --quiet origin HEAD:master
  rm -rf -- "$scratch"
}

# make_fixture <extra-upstream-commits> -- echoes the fixture root
make_fixture() {
  local extra="${1:-0}" root i
  root="$(mktemp -d)"
  FIXTURE_DIRS+=("$root")

  git init --quiet --bare -b master "${root}/upstream.git"
  git clone --quiet "${root}/upstream.git" "${root}/seed" 2>/dev/null
  git_quiet_config "${root}/seed"
  echo "seed" > "${root}/seed/seed.txt"
  git -C "${root}/seed" add seed.txt
  git -C "${root}/seed" commit --quiet -m "submodule initial commit"
  git -C "${root}/seed" push --quiet origin HEAD:master
  rm -rf -- "${root}/seed"

  git init --quiet -b master "${root}/parent"
  git_quiet_config "${root}/parent"
  echo "parent" > "${root}/parent/parent.txt"
  git -C "${root}/parent" add parent.txt
  git -C "${root}/parent" commit --quiet -m "parent initial commit"
  git -c protocol.file.allow=always -C "${root}/parent" \
    submodule add --quiet "${root}/upstream.git" "$SUB_PATH" >/dev/null 2>&1
  git_quiet_config "${root}/parent/${SUB_PATH}"
  git -C "${root}/parent" add -A
  git -C "${root}/parent" commit --quiet -m "pin submodule"

  for (( i = 1; i <= extra; i++ )); do
    publish_upstream_commit "${root}/upstream.git" "${root}/pusher-${i}" \
      "upstream commit ${i}" "feature-${i}.txt"
  done
  echo "$root"
}

parent_pin()      { git -C "$1" ls-files -s -- "$SUB_PATH" | awk '{print $2}'; }
sub_head()        { git -C "$1/${SUB_PATH}" rev-parse HEAD; }
sub_remote_ref()  { git -C "$1/${SUB_PATH}" rev-parse --verify -q refs/remotes/origin/master 2>/dev/null; }
upstream_tip()    { git -C "$1/upstream.git" rev-parse master; }

# upstream_has <root> <path-in-tree> -- YES/NO
upstream_has() {
  if git -C "$1/upstream.git" cat-file -e "master:$2" 2>/dev/null; then echo YES; else echo NO; fi
}

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
#       have no other shape, and a suite that cannot reach the fetch path
#       cannot prove the fetch path is safe.
#
# `--publish-local-modifications` was REMOVED on 2026-08-26 and is no longer
# passed by anything here except B4, which asserts that passing it is a loud
# refusal.
#
# It is a command-line flag rather than an environment variable precisely so
# that this suite's need for it cannot leak into an unattended pipeline run
# through an inherited environment.
ADV_FLAGS=(--allow-local-path-remotes)

LAST_OUTPUT=""
run_script() {
  local parent="$1" record_dir="$2" verify_cmd="$3"
  local exit_code=0
  LAST_OUTPUT="$(
    LAVA_ADVANCE_RECORD_DIR="$record_dir" \
    LAVA_ADVANCE_VERIFY_CMD="$verify_cmd" \
    "$SCRIPT_UNDER_TEST" "${ADV_FLAGS[@]}" "$parent" 2>&1
  )" || exit_code=$?
  echo "$exit_code"
}

# =========================================================================
# B1: an existing-but-unwritable record directory is refused UP FRONT.
#
# `mkdir -p` returns 0 for a directory that already exists no matter what its
# mode is, so the pre-fix script sailed past its own configuration check and
# only discovered the problem at the first record write -- by which time the
# submodule had been fetched, checked out, verified and its pin staged in the
# parent index, with zero evidence written. The header has always documented
# exit 2 / "Nothing was attempted" for this condition.
#
# `origin/master` inside the submodule is the "was anything attempted?"
# witness: the fixture's clone predates the extra upstream commit, so the ref
# still equals the pin until a fetch moves it.
# =========================================================================
echo "=== B1: existing-but-unwritable record directory ==="
b1_root="$(make_fixture 1)"
b1_rec="${b1_root}/records"
mkdir -p "$b1_rec"
chmod 500 "$b1_rec"
b1_pin_before="$(parent_pin "${b1_root}/parent")"
b1_head_before="$(sub_head "${b1_root}/parent")"
b1_ref_before="$(sub_remote_ref "${b1_root}/parent")"
b1_exit="$(run_script "${b1_root}/parent" "$b1_rec" 'true')"

expect_eq "B1" "exit code" "2" "$b1_exit"
expect_eq "B1" "parent pin" "$b1_pin_before" "$(parent_pin "${b1_root}/parent")"
expect_eq "B1" "submodule HEAD" "$b1_head_before" "$(sub_head "${b1_root}/parent")"
expect_eq "B1" "submodule origin/master (proves no fetch ran)" "$b1_ref_before" "$(sub_remote_ref "${b1_root}/parent")"
chmod 700 "$b1_rec"
expect_eq "B1" "records written" "0" "$(find "$b1_rec" -type f -name '*.json' 2>/dev/null | wc -l | tr -d ' ')"

# --- B1p POSITIVE: a writable record directory still advances -------------
echo "=== B1p: POSITIVE -- writable record directory still advances ==="
b1p_root="$(make_fixture 1)"
b1p_rec="${b1p_root}/records"
b1p_exit="$(run_script "${b1p_root}/parent" "$b1p_rec" 'true')"
expect_eq "B1p" "exit code" "0" "$b1p_exit"
b1p_record="$(find_record "$b1p_rec")"
if [[ -n "$b1p_record" ]]; then
  expect_eq "B1p" "outcome" "ADVANCED" "$(jq -r '.outcome' "$b1p_record")"
else
  echo "FAIL: B1p: no Submodule Advance Record was written"; FAILURES=$((FAILURES + 1))
fi
expect_eq "B1p" "parent pin advanced to upstream tip" "$(upstream_tip "$b1p_root")" "$(parent_pin "${b1p_root}/parent")"

# repo_fingerprint <git-dir-or-worktree> -- every ref, its target, and the
# exact number of objects the repository holds, as one string. This is the
# instrument the whole suite now rests on: "the upstream is unchanged" asserted
# on `master` alone would miss a stray branch, a tag, a refs/replace/* entry
# or loose objects pushed and then unreferenced. Refs AND object count together
# catch every one of those.
repo_fingerprint() {
  local repo="$1"
  {
    git -C "$repo" for-each-ref --format='%(refname) %(objectname)' 2>/dev/null | LC_ALL=C sort
    printf 'OBJECTS=%s\n' "$(git -C "$repo" cat-file --batch-all-objects --batch-check='%(objectname)' 2>/dev/null | wc -l | tr -d ' ')"
  } | LC_ALL=C sort
}

# =========================================================================
# B2r: a submodule carrying local work leaves its upstream BYTE-IDENTICAL.
#
# This replaces B2/B2p, which asserted that step 6 published the operator's
# bytes and not the build's. There is no step 6: the correct behaviour is now
# that NOTHING is published, and that the run does not even contact the
# upstream, because the refusal happens before the fetch.
#
# The verify command still writes build output, so the fixture is the same
# adversarial shape it always was -- it just must never run at all here.
# =========================================================================
echo "=== B2r: local work in a submodule leaves its upstream byte-identical ==="
b2_root="$(make_fixture 1)"
b2_sub="${b2_root}/parent/${SUB_PATH}"
printf 'operator note\n' > "${b2_sub}/NOTES.txt"
b2_notes_before="$(cat "${b2_sub}/NOTES.txt")"
b2_pin_before="$(parent_pin "${b2_root}/parent")"
b2_head_before="$(sub_head "${b2_root}/parent")"
b2_ref_before="$(sub_remote_ref "${b2_root}/parent")"
b2_up_before="$(repo_fingerprint "${b2_root}/upstream.git")"
b2_verify='mkdir -p "$1/build/outputs" && printf "APK\n" > "$1/build/outputs/app.apk" && printf "props\n" > "$1/build/local.properties" && true'
# Invoked directly rather than through run_script: run_script is called inside
# a command substitution, so its LAST_OUTPUT assignment vanishes into that
# subshell, and this case asserts on the console text.
b2_exit=0
b2_out="$(
  LAVA_ADVANCE_RECORD_DIR="${b2_root}/records" \
  LAVA_ADVANCE_VERIFY_CMD="$b2_verify" \
  "$SCRIPT_UNDER_TEST" "${ADV_FLAGS[@]}" "${b2_root}/parent" 2>&1
)" || b2_exit=$?

expect_ne "B2r" "exit code (an unclean tree is a refusal)" "0" "$b2_exit"
expect_eq "B2r" "upstream fingerprint (refs + object count) unchanged" "$b2_up_before" "$(repo_fingerprint "${b2_root}/upstream.git")"
expect_eq "B2r" "the operator's NOTES.txt reached the upstream" "NO" "$(upstream_has "$b2_root" "NOTES.txt")"
expect_eq "B2r" "the verify build's app.apk reached the upstream" "NO" "$(upstream_has "$b2_root" "build/outputs/app.apk")"
expect_eq "B2r" "submodule origin/master (proves no fetch ran)" "$b2_ref_before" "$(sub_remote_ref "${b2_root}/parent")"
expect_eq "B2r" "submodule HEAD" "$b2_head_before" "$(sub_head "${b2_root}/parent")"
expect_eq "B2r" "parent pin" "$b2_pin_before" "$(parent_pin "${b2_root}/parent")"
expect_eq "B2r" "the operator's bytes are untouched on disk" "$b2_notes_before" "$(cat "${b2_sub}/NOTES.txt" 2>/dev/null)"
b2_record="$(find_record "${b2_root}/records")"
if [[ -n "$b2_record" ]]; then
  expect_eq "B2r" "outcome" "FAILED_PRECONDITION" "$(jq -r '.outcome' "$b2_record")"
  expect_eq "B2r" "local_modifications_pushed" "false" "$(jq -r '.local_modifications_pushed' "$b2_record")"
else
  echo "FAIL: B2r: no Submodule Advance Record was written"; FAILURES=$((FAILURES + 1))
fi
if grep -qF -- "NOTES.txt" <<<"$b2_out"; then
  echo "PASS: B2r: the refusal names the path it found"
else
  echo "FAIL: B2r: the refusal does not name 'NOTES.txt', so the operator cannot act on it"
  FAILURES=$((FAILURES + 1))
fi

# --- B2i: a file the NEW upstream commit gitignores is STILL local work ---
# H5's "the ignore filter left nothing to commit" branch lived inside step 6
# and is gone. What survives is the measurement that armed it: the file is
# untracked at the moment `git status -uall` runs, BEFORE the new commit
# carrying the .gitignore is checked out -- so it is local work and refused.
echo "=== B2i: a soon-to-be-gitignored file is still local work at measurement time ==="
b2i_root="$(make_fixture 0)"
publish_upstream_commit "${b2i_root}/upstream.git" "${b2i_root}/pusher-ign" \
  "ignore the operator's scratch file" ".gitignore" "scratch.txt"
b2i_sub="${b2i_root}/parent/${SUB_PATH}"
echo "scratch" > "${b2i_sub}/scratch.txt"
b2i_pin_before="$(parent_pin "${b2i_root}/parent")"
b2i_up_before="$(repo_fingerprint "${b2i_root}/upstream.git")"
b2i_exit="$(run_script "${b2i_root}/parent" "${b2i_root}/records" 'true')"
expect_ne "B2i" "exit code" "0" "$b2i_exit"
expect_eq "B2i" "parent pin" "$b2i_pin_before" "$(parent_pin "${b2i_root}/parent")"
expect_eq "B2i" "upstream fingerprint unchanged" "$b2i_up_before" "$(repo_fingerprint "${b2i_root}/upstream.git")"
b2i_record="$(find_record "${b2i_root}/records")"
if [[ -n "$b2i_record" ]]; then
  expect_eq "B2i" "outcome" "FAILED_PRECONDITION" "$(jq -r '.outcome' "$b2i_record")"
  expect_eq "B2i" "local_modifications_pushed" "false" "$(jq -r '.local_modifications_pushed' "$b2i_record")"
else
  echo "FAIL: B2i: no Submodule Advance Record was written"; FAILURES=$((FAILURES + 1))
fi

# =========================================================================
# B3r: a SUCCESSFUL advance writes to no repository but the parent's index.
#
# This replaces B3/B3p, which asserted that a failed step-6 stage was not
# reported as ADVANCED. The staging it guarded happened inside the submodule
# and is gone.
#
# The successful path is the one worth measuring now, and it is measured
# against three repositories at once: two §6.W mirrors (production shape) and
# a WITNESS repository the run is never told about. A run that writes to any
# of them fails here.
# =========================================================================
echo "=== B3r: a successful advance leaves every repository except the parent index untouched ==="
b3_root="$(make_fixture 1)"
b3_sub="${b3_root}/parent/${SUB_PATH}"
git init --quiet --bare -b master "${b3_root}/mirror2.git"
git -C "${b3_root}/upstream.git" push --quiet "${b3_root}/mirror2.git" 'refs/heads/master:refs/heads/master'
git -C "$b3_sub" remote add mirror2 "${b3_root}/mirror2.git"
git init --quiet --bare -b master "${b3_root}/witness.git"
git -C "${b3_root}/upstream.git" push --quiet "${b3_root}/witness.git" 'refs/heads/master:refs/heads/master'

b3_up_before="$(repo_fingerprint "${b3_root}/upstream.git")"
b3_m2_before="$(repo_fingerprint "${b3_root}/mirror2.git")"
b3_wit_before="$(repo_fingerprint "${b3_root}/witness.git")"
b3_pin_before="$(parent_pin "${b3_root}/parent")"
b3_exit="$(run_script "${b3_root}/parent" "${b3_root}/records" 'true')"

expect_eq "B3r" "exit code" "0" "$b3_exit"
expect_ne "B3r" "parent pin (the ONE thing a run may change)" "$b3_pin_before" "$(parent_pin "${b3_root}/parent")"
expect_eq "B3r" "upstream fingerprint unchanged by a SUCCESSFUL advance" "$b3_up_before" "$(repo_fingerprint "${b3_root}/upstream.git")"
expect_eq "B3r" "second §6.W mirror fingerprint unchanged" "$b3_m2_before" "$(repo_fingerprint "${b3_root}/mirror2.git")"
expect_eq "B3r" "witness repository (never named on argv) unchanged" "$b3_wit_before" "$(repo_fingerprint "${b3_root}/witness.git")"
b3_record="$(find_record "${b3_root}/records")"
if [[ -n "$b3_record" ]]; then
  expect_eq "B3r" "outcome" "ADVANCED" "$(jq -r '.outcome' "$b3_record")"
  expect_eq "B3r" "local_modifications_pushed" "false" "$(jq -r '.local_modifications_pushed' "$b3_record")"
else
  echo "FAIL: B3r: no Submodule Advance Record was written"; FAILURES=$((FAILURES + 1))
fi
if [[ -z "$(git -C "$b3_sub" for-each-ref --format='%(refname)' 'refs/lava-advance-rescue/**' 2>/dev/null)" ]]; then
  echo "PASS: B3r: no rescue ref was created (there is no local work of this run's making to rescue)"
else
  echo "FAIL: B3r: a refs/lava-advance-rescue/* ref exists, but nothing here commits"
  FAILURES=$((FAILURES + 1))
fi

# =========================================================================
# B4: --publish-local-modifications is REFUSED, loudly.
#
# LVA-120's lesson, stated as a test rather than as a comment: when a flag's
# argument branch is DELETED, the flag falls through to the catch-all -- there
# `*) shift` read it as a repository path and the run silently continued in a
# different mode. A removed capability must FAIL, and must not be mistaken
# for an argument of another kind.
# =========================================================================
echo "=== B4: the removed flag is a loud refusal, not a silently-ignored argument ==="
b4_root="$(make_fixture 1)"
b4_pin_before="$(parent_pin "${b4_root}/parent")"
b4_ref_before="$(sub_remote_ref "${b4_root}/parent")"
b4_up_before="$(repo_fingerprint "${b4_root}/upstream.git")"
b4_exit=0
b4_out="$(
  LAVA_ADVANCE_RECORD_DIR="${b4_root}/records" \
  LAVA_ADVANCE_VERIFY_CMD='true' \
  "$SCRIPT_UNDER_TEST" --allow-local-path-remotes --publish-local-modifications "${b4_root}/parent" 2>&1
)" || b4_exit=$?
expect_eq "B4" "exit code" "2" "$b4_exit"
expect_eq "B4" "parent pin" "$b4_pin_before" "$(parent_pin "${b4_root}/parent")"
expect_eq "B4" "submodule origin/master (proves no fetch ran)" "$b4_ref_before" "$(sub_remote_ref "${b4_root}/parent")"
expect_eq "B4" "upstream fingerprint unchanged" "$b4_up_before" "$(repo_fingerprint "${b4_root}/upstream.git")"
expect_eq "B4" "records written" "0" "$(find "${b4_root}/records" -type f -name '*.json' 2>/dev/null | wc -l | tr -d ' ')"
if grep -qF -- "REMOVED" <<<"$b4_out"; then
  echo "PASS: B4: the refusal says the capability was REMOVED"
else
  echo "FAIL: B4: the refusal does not name the removal, so a caller cannot tell it from an ordinary usage error"
  FAILURES=$((FAILURES + 1))
fi
if grep -qF -- "more than one repository path" <<<"$b4_out"; then
  echo "FAIL: B4: the flag fell through and was read as a REPOSITORY PATH -- this is the LVA-120 failure mode exactly"
  FAILURES=$((FAILURES + 1))
else
  echo "PASS: B4: the flag was not mistaken for a repository path"
fi
# ...and alone, with no parent path, it must still refuse rather than fall
# through to `git rev-parse --show-toplevel`.
#
# HERMETICITY, and it is not decoration here. This invocation deliberately
# passes NO repository path, so the script resolves one from the CURRENT
# WORKING DIRECTORY. Run from anywhere inside this checkout that resolves to
# THIS repository -- and a falsifiability rehearsal on 2026-08-26 proved the
# consequence rather than supposing it: with the refusal mutated to accept the
# flag, this invocation examined the real repository's 25 real submodules and
# wrote 25 records. Nothing was advanced (the pins were current, and
# `git submodule status` showed no `+` afterwards), but "nothing happened
# because the state happened to be benign" is exactly the coincidence-with-
# good-timing this suite exists to refuse.
#
# `cd`-ing into the fixture parent keeps the property under test intact -- argv
# still carries no path -- while making the discovered repository a disposable
# one. A hermetic suite must stay hermetic when the code under test is WRONG;
# that is the only condition under which its hermeticity is load-bearing.
b4b_exit=0
b4b_out="$(
  cd "${b4_root}/parent" && \
  LAVA_ADVANCE_RECORD_DIR="${b4_root}/records-b" \
  LAVA_ADVANCE_VERIFY_CMD='true' \
  "$SCRIPT_UNDER_TEST" --publish-local-modifications 2>&1
)" || b4b_exit=$?
expect_eq "B4" "exit code with the flag ALONE (no parent path)" "2" "$b4b_exit"
expect_eq "B4" "records written by the flag-alone invocation" "0" "$(find "${b4_root}/records-b" -type f -name '*.json' 2>/dev/null | wc -l | tr -d ' ')"

# =========================================================================
# B5: there is no reachable `git push` in the script at all.
#
# A structural assertion, and deliberately a crude one. It cannot prove the
# absence of a publish on its own -- that is what B2r/B3r measure behaviourally
# -- but it is the guard that fires the moment somebody reintroduces pushing
# code, before any fixture has to catch it at runtime.
#
# `push` must be matched as a git SUBCOMMAND, never as an option to another
# one: the condition-(C) scan legitimately runs `git remote get-url --push
# --all` to read a remote's push DESTINATION, which is a read. A matcher that
# cannot tell `push` from `--push` would fail on correct code, and a guard
# that fails on correct code is one its operator learns to delete.
# =========================================================================
echo "=== B5: the script contains no reachable 'git push' ==="
b5_matches="$(grep -nE '(^|[^-[:alnum:]_])git([[:space:]]|$)[^#]*[[:space:]]push([[:space:]]|$)' "$SCRIPT_UNDER_TEST" | grep -vE '^[0-9]+:[[:space:]]*#' || true)"
b5_hits="$(printf '%s' "$b5_matches" | grep -c . || true)"
if [[ "$b5_hits" != "0" ]]; then
  printf '%s\n' "$b5_matches" | sed 's/^/    /'
fi
expect_eq "B5" "non-comment lines invoking git's 'push' subcommand" "0" "$b5_hits"
# ...and the matcher itself must be able to see a push, or it certifies nothing.
b5_probe="$(mktemp)"; FIXTURE_DIRS+=("$b5_probe")
printf 'x() {\n  git -C "$d" push --quiet origin HEAD:master\n}\n' > "$b5_probe"
b5_probe_hits="$(grep -cE '(^|[^-[:alnum:]_])git([[:space:]]|$)[^#]*[[:space:]]push([[:space:]]|$)' "$b5_probe" || true)"
expect_eq "B5" "the matcher DOES detect a real push (self-test)" "1" "$b5_probe_hits"

echo "---"
if [[ "$FAILURES" -eq 0 ]]; then
  echo "test_advance_all_submodules_blast_radius.sh: ALL CASES PASSED"
  exit 0
fi
echo "test_advance_all_submodules_blast_radius.sh: ${FAILURES} assertion(s) FAILED"
exit 1

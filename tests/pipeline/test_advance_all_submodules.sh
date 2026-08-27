#!/usr/bin/env bash
# Hermetic test suite for scripts/advance-all-submodules.sh (FR-015/FR-016,
# research.md R-005, tasks.md T050-T053).
#
# This test constructs isolated, throwaway git fixture repositories under
# temp directories -- one disposable "parent" repo with one disposable
# submodule wired to a disposable LOCAL bare repo standing in for that
# submodule's own upstream -- and invokes the real production script
# against each fixture via its documented LAVA_ADVANCE_* override
# environment variables.
#
# SAFETY: it never touches this repository's real submodules/ tree, never
# reaches any network upstream (every "upstream" here is a bare repo inside
# the temp dir), and every fixture is removed by the EXIT trap.
#
# Covers all four R-005 branches:
#   Case 1  no-newer-commit          -> NO_NEWER_COMMIT,           old == new, pin NOT updated
#   Case 2  clean advance            -> ADVANCED,                  pin updated to upstream HEAD
#   Case 3  breaking change detected -> REJECTED_BREAKING_CHANGE,  pin NOT updated, prior state restored
#   Case 4  concurrent upstream commit during the verify window
#                                    -> ADVANCED at the MEASURED commit, upstream untouched
#   Case 4b an UNCLEAN submodule working tree
#                                    -> FAILED_PRECONDITION, no opt-in exists, and passing
#                                       the removed --publish-local-modifications is exit 2
#
# Case 4 used to be "push conflict -> REJECTED_PUSH_CONFLICT". R-005 step 6
# and --publish-local-modifications were REMOVED on 2026-08-26 after five
# review rounds found twelve fixture-proven ways for unaudited content to
# reach another repository's default branch through them, so this script now
# issues no push and REJECTED_PUSH_CONFLICT is unemittable. Case 4 keeps the
# concurrency fixture and asserts the new correct answer; case 4b asserts the
# capability is gone.
#
# Exit 0 if every case passes; non-zero otherwise.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT_UNDER_TEST="${REPO_ROOT}/scripts/advance-all-submodules.sh"

if ! command -v jq >/dev/null 2>&1; then
  echo "FAIL: jq is required to run this test suite but was not found on PATH"
  exit 1
fi

# All fixture directories created during this run, cleaned up on exit no
# matter how the script terminates (success, failure, or interrupt).
FIXTURE_DIRS=()

cleanup() {
  for d in "${FIXTURE_DIRS[@]:-}"; do
    if [[ -n "$d" && -d "$d" ]]; then
      rm -rf -- "$d"
    fi
  done
}
trap cleanup EXIT

# The submodule's path inside the fixture parent repo. Deliberately NOT
# "submodules/<x>" so that an implementation that hardcoded this project's
# own real submodule directory name would fail this suite.
SUB_PATH="subs/helixqa"

# git_quiet_config <repo-dir> -- fixture identity, so commits work in any
# environment (including one with no global git identity configured).
git_quiet_config() {
  git -C "$1" config user.email "fixture@example.invalid"
  git -C "$1" config user.name "Fixture"
  git -C "$1" config commit.gpgsign false
}

# make_fixture <name> <extra-upstream-commits>
#
# Builds, under a fresh temp dir:
#   <root>/upstream.git        bare repo on branch master  (the submodule's "own upstream")
#   <root>/parent              parent repo on branch master, with a submodule at $SUB_PATH
#
# The submodule is pinned at upstream's HEAD *at the moment it was added*.
# <extra-upstream-commits> additional commits are then pushed to the bare
# upstream AFTERWARDS, so the pin lags the upstream by exactly that many
# commits (0 => the R-005 step-3 no-op case).
#
# NOTE: invoked via command substitution (a subshell), so this function does
# NOT register the fixture in FIXTURE_DIRS itself -- every call site
# registers the returned path immediately after capturing it. (Same idiom,
# and same reason, as tests/pipeline/test_phase_00_precondition.sh.)
make_fixture() {
  local name="$1"
  local extra_commits="$2"
  local root
  root="$(mktemp -d "${TMPDIR:-/tmp}/advance-subs-fixture-${name}-XXXXXX")"

  # --- the submodule's own "upstream": a local bare repo ------------------
  local seed="${root}/seed-clone"
  git init --quiet --initial-branch=master "$seed"
  git_quiet_config "$seed"
  echo "seed" > "${seed}/seed.txt"
  git -C "$seed" add seed.txt
  git -C "$seed" commit --quiet -m "upstream initial commit"
  git clone --quiet --bare "$seed" "${root}/upstream.git"
  rm -rf -- "$seed"

  # --- the parent repo, with the submodule wired in ----------------------
  git init --quiet --initial-branch=master "${root}/parent"
  git_quiet_config "${root}/parent"
  echo "parent" > "${root}/parent/parent.txt"
  git -C "${root}/parent" add parent.txt
  git -C "${root}/parent" commit --quiet -m "parent initial commit"

  # protocol.file.allow is required since git 2.38 (CVE-2022-39253) to clone
  # a submodule over a local path. Scoped to this one invocation via -c.
  git -c protocol.file.allow=always -C "${root}/parent" \
    submodule add --quiet "${root}/upstream.git" "$SUB_PATH" >/dev/null 2>&1
  git_quiet_config "${root}/parent/${SUB_PATH}"
  git -C "${root}/parent" add -A
  git -C "${root}/parent" commit --quiet -m "pin submodule"

  # --- advance the upstream past the pin, if this case wants that --------
  local i
  for (( i = 1; i <= extra_commits; i++ )); do
    push_upstream_commit "${root}/upstream.git" "${root}/pusher-${i}" "upstream commit ${i}" "feature-${i}.txt"
  done

  echo "$root"
}

# push_upstream_commit <bare-repo> <scratch-clone-dir> <message> <filename>
# Adds one commit to the bare upstream's master branch, via a throwaway
# clone. Used both by make_fixture (to build the "upstream has moved ahead"
# state) and by Case 4's verify hook (to simulate another developer landing
# a commit DURING our rebuild-and-test window).
push_upstream_commit() {
  local bare="$1" scratch="$2" message="$3" filename="$4"
  git clone --quiet "$bare" "$scratch"
  git -C "$scratch" config user.email "upstream@example.invalid"
  git -C "$scratch" config user.name "Upstream Author"
  git -C "$scratch" config commit.gpgsign false
  echo "$message" > "${scratch}/${filename}"
  git -C "$scratch" add "$filename"
  git -C "$scratch" commit --quiet -m "$message"
  git -C "$scratch" push --quiet origin HEAD:master
  rm -rf -- "$scratch"
}

# upstream_head <bare-repo> -- the bare repo's master tip SHA.
upstream_head() {
  git -C "$1" rev-parse master
}

# sub_head <parent-repo> -- the submodule working tree's current HEAD SHA.
sub_head() {
  git -C "$1/${SUB_PATH}" rev-parse HEAD
}

# parent_pin <parent-repo> -- the gitlink SHA the PARENT repo's index
# currently records for the submodule. This is the real "is the pin
# updated?" signal: `git add <submodule-path>` is exactly what changes it.
parent_pin() {
  git -C "$1" ls-files -s -- "$SUB_PATH" | awk '{print $2}'
}

# find_record <record-dir> -- path of the Submodule Advance Record whose
# submodule_name field is $SUB_PATH. Located by CONTENT, not by filename,
# so the test does not couple itself to the script's filename-sanitizing
# scheme.
find_record() {
  local dir="$1" f
  [[ -d "$dir" ]] || return 1
  while IFS= read -r f; do
    if [[ "$(jq -r '.submodule_name' "$f" 2>/dev/null)" == "$SUB_PATH" ]]; then
      echo "$f"
      return 0
    fi
  done < <(find "$dir" -type f -name '*.json' 2>/dev/null)
  return 1
}

FAILURES=0

# expect_eq <case-label> <what> <expected> <actual>
expect_eq() {
  local label="$1" what="$2" expected="$3" actual="$4"
  if [[ "$expected" == "$actual" ]]; then
    echo "PASS: ${label}: ${what} == '${expected}'"
  else
    echo "FAIL: ${label}: ${what} expected '${expected}', got '${actual}'"
    FAILURES=$((FAILURES + 1))
  fi
}

# expect_ne <case-label> <what> <not-expected> <actual>
expect_ne() {
  local label="$1" what="$2" not_expected="$3" actual="$4"
  if [[ "$not_expected" != "$actual" ]]; then
    echo "PASS: ${label}: ${what} ('${actual}') differs from '${not_expected}' as required"
  else
    echo "FAIL: ${label}: ${what} expected to differ from '${not_expected}' but was identical"
    FAILURES=$((FAILURES + 1))
  fi
}

# run_script <parent-repo> <record-dir> <verify-cmd> -- invokes the real
# production script against a fixture. Echoes its exit code; its combined
# output is captured into the global LAST_OUTPUT.
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
# passed by anything here except case 4b, which asserts that passing it is a
# loud refusal.
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
# Case 1: no newer commit upstream -> no-op (R-005 step 3 / spec Edge Case)
# =========================================================================
c1_root="$(make_fixture "no-newer" 0)"
FIXTURE_DIRS+=("$c1_root")
c1_records="${c1_root}/records"
c1_pin_before="$(parent_pin "${c1_root}/parent")"

# verify-cmd is `false` on purpose: a no-op advance must never even REACH
# the rebuild-and-test step, so a failing verify command must not matter.
c1_exit="$(run_script "${c1_root}/parent" "$c1_records" "false")"

expect_eq "case1(no-newer-commit)" "exit code" "0" "$c1_exit"
if c1_rec="$(find_record "$c1_records")"; then
  echo "PASS: case1(no-newer-commit): a Submodule Advance Record was written for ${SUB_PATH}"
  expect_eq "case1(no-newer-commit)" "outcome"        "NO_NEWER_COMMIT" "$(jq -r '.outcome' "$c1_rec")"
  expect_eq "case1(no-newer-commit)" "old==new commit" "$(jq -r '.old_commit' "$c1_rec")" "$(jq -r '.new_commit' "$c1_rec")"
  expect_eq "case1(no-newer-commit)" "parent_pin_updated" "false" "$(jq -r '.parent_pin_updated' "$c1_rec")"
  expect_eq "case1(no-newer-commit)" "old_commit is the real pin" "$c1_pin_before" "$(jq -r '.old_commit' "$c1_rec")"
else
  echo "FAIL: case1(no-newer-commit): no Submodule Advance Record found under ${c1_records}; script output: ${LAST_OUTPUT}"
  FAILURES=$((FAILURES + 4))
fi
expect_eq "case1(no-newer-commit)" "parent pin unchanged" "$c1_pin_before" "$(parent_pin "${c1_root}/parent")"

# =========================================================================
# Case 2: newer commit upstream, rebuild-and-test passes -> clean advance
# =========================================================================
c2_root="$(make_fixture "clean-advance" 1)"
FIXTURE_DIRS+=("$c2_root")
c2_records="${c2_root}/records"
c2_pin_before="$(parent_pin "${c2_root}/parent")"
c2_upstream_head="$(upstream_head "${c2_root}/upstream.git")"

c2_exit="$(run_script "${c2_root}/parent" "$c2_records" "true")"

expect_eq "case2(clean-advance)" "exit code" "0" "$c2_exit"
if c2_rec="$(find_record "$c2_records")"; then
  echo "PASS: case2(clean-advance): a Submodule Advance Record was written for ${SUB_PATH}"
  expect_eq "case2(clean-advance)" "outcome"            "ADVANCED"          "$(jq -r '.outcome' "$c2_rec")"
  expect_eq "case2(clean-advance)" "old_commit"          "$c2_pin_before"    "$(jq -r '.old_commit' "$c2_rec")"
  expect_eq "case2(clean-advance)" "new_commit"          "$c2_upstream_head" "$(jq -r '.new_commit' "$c2_rec")"
  expect_ne "case2(clean-advance)" "new_commit"          "$c2_pin_before"    "$(jq -r '.new_commit' "$c2_rec")"
  expect_eq "case2(clean-advance)" "parent_pin_updated"  "true"              "$(jq -r '.parent_pin_updated' "$c2_rec")"
  expect_eq "case2(clean-advance)" "local_modifications_pushed" "false"      "$(jq -r '.local_modifications_pushed' "$c2_rec")"
else
  echo "FAIL: case2(clean-advance): no Submodule Advance Record found under ${c2_records}; script output: ${LAST_OUTPUT}"
  FAILURES=$((FAILURES + 6))
fi
# The two assertions that actually matter to a human: the submodule really
# moved, and the PARENT really recorded the new pin.
expect_eq "case2(clean-advance)" "submodule HEAD advanced to upstream HEAD" "$c2_upstream_head" "$(sub_head "${c2_root}/parent")"
expect_eq "case2(clean-advance)" "parent pin advanced to upstream HEAD"     "$c2_upstream_head" "$(parent_pin "${c2_root}/parent")"

# =========================================================================
# Case 3: newer commit upstream, rebuild-and-test FAILS -> advance discarded
# =========================================================================
c3_root="$(make_fixture "breaking-change" 1)"
FIXTURE_DIRS+=("$c3_root")
c3_records="${c3_root}/records"
c3_pin_before="$(parent_pin "${c3_root}/parent")"
c3_upstream_head="$(upstream_head "${c3_root}/upstream.git")"

# `false` stands in for "phase-01-build.sh / phase-02-test.sh failed against
# the advanced submodule" -- R-005 step 5's breaking-change branch.
c3_exit="$(run_script "${c3_root}/parent" "$c3_records" "false")"

expect_eq "case3(breaking-change)" "exit code" "1" "$c3_exit"
if c3_rec="$(find_record "$c3_records")"; then
  echo "PASS: case3(breaking-change): a Submodule Advance Record was written for ${SUB_PATH}"
  expect_eq "case3(breaking-change)" "outcome"           "REJECTED_BREAKING_CHANGE" "$(jq -r '.outcome' "$c3_rec")"
  expect_eq "case3(breaking-change)" "old_commit"         "$c3_pin_before"           "$(jq -r '.old_commit' "$c3_rec")"
  expect_eq "case3(breaking-change)" "new_commit (the rejected candidate)" "$c3_upstream_head" "$(jq -r '.new_commit' "$c3_rec")"
  expect_eq "case3(breaking-change)" "parent_pin_updated" "false"                    "$(jq -r '.parent_pin_updated' "$c3_rec")"
else
  echo "FAIL: case3(breaking-change): no Submodule Advance Record found under ${c3_records}; script output: ${LAST_OUTPUT}"
  FAILURES=$((FAILURES + 4))
fi
# The load-bearing assertions: prior state restored, pin untouched. If an
# implementation left the pin bumped to a commit that broke the build, THIS
# is the assertion that catches it.
expect_eq "case3(breaking-change)" "submodule HEAD restored to the prior pin" "$c3_pin_before" "$(sub_head "${c3_root}/parent")"
expect_eq "case3(breaking-change)" "parent pin NOT updated"                   "$c3_pin_before" "$(parent_pin "${c3_root}/parent")"

# =========================================================================
# Case 4 (RETARGETED 2026-08-26): another developer lands a commit on the
#        submodule's upstream DURING the rebuild-and-test window.
#
# It used to test REJECTED_PUSH_CONFLICT: the submodule carried uncommitted
# work, step 6 committed it, the concurrent upstream commit made the push a
# genuine non-fast-forward, and FR-016 required that be refused rather than
# forced. R-005 step 6 and --publish-local-modifications were REMOVED on
# 2026-08-26, so REJECTED_PUSH_CONFLICT is no longer emittable by any code
# path -- this script issues no push at all.
#
# The fixture keeps its full shape, because the concurrency it creates is
# still real and still worth pinning. What changed is the correct answer:
#
#   * the run must ADVANCE, because a concurrent upstream commit is no longer
#     a conflict for anything this script does;
#   * the pin it stages must be EXACTLY the commit it measured at step 2 --
#     not the concurrent developer's newer one, which no guard in this run
#     examined and no rebuild-and-test step verified;
#   * the upstream must still hold the concurrent developer's commit as its
#     tip, untouched, and must have received nothing;
#   * no rescue ref may exist, because nothing was committed to rescue.
#
# The last two are the assertions that would have caught the removed
# capability coming back.
# =========================================================================
c4_root="$(make_fixture "concurrent-upstream" 1)"
FIXTURE_DIRS+=("$c4_root")
c4_records="${c4_root}/records"
c4_pin_before="$(parent_pin "${c4_root}/parent")"
c4_measured="$(upstream_head "${c4_root}/upstream.git")"

# The verify hook stands in for the rebuild-and-test window (R-005 step 5).
# It succeeds -- and, while it is "running", another developer lands a commit
# on the submodule's upstream master.
cat > "${c4_root}/verify.sh" <<VERIFYEOF
#!/usr/bin/env bash
set -euo pipefail
scratch="\$(mktemp -d "${c4_root}/concurrent-XXXXXX")"
rm -rf -- "\$scratch"
git clone --quiet "${c4_root}/upstream.git" "\$scratch"
git -C "\$scratch" config user.email "other@example.invalid"
git -C "\$scratch" config user.name "Other Developer"
git -C "\$scratch" config commit.gpgsign false
echo "landed while we were building" > "\$scratch/concurrent.txt"
git -C "\$scratch" add concurrent.txt
git -C "\$scratch" commit --quiet -m "concurrent upstream commit"
git -C "\$scratch" push --quiet origin HEAD:master
rm -rf -- "\$scratch"
exit 0
VERIFYEOF
chmod +x "${c4_root}/verify.sh"

c4_exit="$(run_script "${c4_root}/parent" "$c4_records" "bash ${c4_root}/verify.sh")"

expect_eq "case4(concurrent-upstream)" "exit code" "0" "$c4_exit"
if c4_rec="$(find_record "$c4_records")"; then
  echo "PASS: case4(concurrent-upstream): a Submodule Advance Record was written for ${SUB_PATH}"
  expect_eq "case4(concurrent-upstream)" "outcome"                    "ADVANCED"       "$(jq -r '.outcome' "$c4_rec")"
  expect_eq "case4(concurrent-upstream)" "old_commit"                  "$c4_pin_before" "$(jq -r '.old_commit' "$c4_rec")"
  expect_eq "case4(concurrent-upstream)" "parent_pin_updated"          "true"           "$(jq -r '.parent_pin_updated' "$c4_rec")"
  expect_eq "case4(concurrent-upstream)" "local_modifications_pushed"  "false"          "$(jq -r '.local_modifications_pushed' "$c4_rec")"
  expect_eq "case4(concurrent-upstream)" "new_commit is the commit STEP 2 measured, not the concurrent one" "$c4_measured" "$(jq -r '.new_commit' "$c4_rec")"
else
  echo "FAIL: case4(concurrent-upstream): no Submodule Advance Record found under ${c4_records}; script output: ${LAST_OUTPUT}"
  FAILURES=$((FAILURES + 5))
fi
expect_eq "case4(concurrent-upstream)" "parent pin staged at the MEASURED commit" "$c4_measured" "$(parent_pin "${c4_root}/parent")"
expect_eq "case4(concurrent-upstream)" "submodule HEAD sits on the MEASURED commit" "$c4_measured" "$(sub_head "${c4_root}/parent")"

# No commit was created anywhere, so there is nothing for a rescue ref to
# hold. A rescue ref appearing here could only mean this script had committed.
c4_rescue_ref="$(git -C "${c4_root}/parent/${SUB_PATH}" for-each-ref --format='%(refname)' 'refs/lava-advance-rescue/**' | head -n1)"
if [[ -z "$c4_rescue_ref" ]]; then
  echo "PASS: case4(concurrent-upstream): no refs/lava-advance-rescue/* ref exists -- this script creates no commit for one to hold"
else
  echo "FAIL: case4(concurrent-upstream): a rescue ref '${c4_rescue_ref}' exists, which can only mean a commit was created"
  FAILURES=$((FAILURES + 1))
fi

# The upstream must be exactly where the concurrent developer left it: its tip
# is that developer's commit, and nothing this run produced is reachable from
# it. This is the assertion that fails the moment a publish path returns.
c4_final_upstream="$(upstream_head "${c4_root}/upstream.git")"
if git -C "${c4_root}/upstream.git" log --format='%s' -n1 "$c4_final_upstream" | grep -q "concurrent upstream commit"; then
  echo "PASS: case4(concurrent-upstream): fixture upstream master tip is still the concurrent developer's commit"
else
  echo "FAIL: case4(concurrent-upstream): fixture upstream master tip is not the concurrent developer's commit -- something wrote to it"
  FAILURES=$((FAILURES + 1))
fi
expect_eq "case4(concurrent-upstream)" "upstream branch count (no stray branch was created)" "1" \
  "$(git -C "${c4_root}/upstream.git" for-each-ref --format='%(refname)' refs/heads | wc -l | tr -d ' ')"

echo "---"
# =========================================================================
# Case 4b (NEW 2026-08-26): an UNCLEAN submodule working tree is refused, and
#          there is no flag that overrides the refusal.
#
# This is the replacement for what case 4 used to establish. Case 4 proved the
# publish path behaved correctly under conflict; 4b proves the publish path is
# gone: the same uncommitted work that used to ARM it is now the reason the
# submodule is not touched at all.
# =========================================================================
c4b_root="$(make_fixture "unclean-refusal" 1)"
FIXTURE_DIRS+=("$c4b_root")
c4b_records="${c4b_root}/records"
c4b_pin_before="$(parent_pin "${c4b_root}/parent")"
c4b_up_before="$(upstream_head "${c4b_root}/upstream.git")"
echo "local uncommitted work" >> "${c4b_root}/parent/${SUB_PATH}/seed.txt"
c4b_sub_ref_before="$(git -C "${c4b_root}/parent/${SUB_PATH}" rev-parse --verify -q refs/remotes/origin/master 2>/dev/null)"

c4b_exit="$(run_script "${c4b_root}/parent" "$c4b_records" "true")"

expect_eq "case4b(unclean-refusal)" "exit code" "1" "$c4b_exit"
expect_eq "case4b(unclean-refusal)" "parent pin NOT updated" "$c4b_pin_before" "$(parent_pin "${c4b_root}/parent")"
expect_eq "case4b(unclean-refusal)" "submodule HEAD untouched" "$c4b_pin_before" "$(sub_head "${c4b_root}/parent")"
expect_eq "case4b(unclean-refusal)" "submodule origin/master (proves no fetch ran)" "$c4b_sub_ref_before" \
  "$(git -C "${c4b_root}/parent/${SUB_PATH}" rev-parse --verify -q refs/remotes/origin/master 2>/dev/null)"
expect_eq "case4b(unclean-refusal)" "upstream received nothing" "$c4b_up_before" "$(upstream_head "${c4b_root}/upstream.git")"
if c4b_rec="$(find_record "$c4b_records")"; then
  expect_eq "case4b(unclean-refusal)" "outcome" "FAILED_PRECONDITION" "$(jq -r '.outcome' "$c4b_rec")"
  expect_eq "case4b(unclean-refusal)" "parent_pin_updated" "false" "$(jq -r '.parent_pin_updated' "$c4b_rec")"
  expect_eq "case4b(unclean-refusal)" "local_modifications_pushed" "false" "$(jq -r '.local_modifications_pushed' "$c4b_rec")"
else
  echo "FAIL: case4b(unclean-refusal): no Submodule Advance Record found"; FAILURES=$((FAILURES + 3))
fi
# The operator's bytes are still exactly where they were.
if grep -qF "local uncommitted work" "${c4b_root}/parent/${SUB_PATH}/seed.txt"; then
  echo "PASS: case4b(unclean-refusal): the operator's uncommitted bytes are untouched on disk"
else
  echo "FAIL: case4b(unclean-refusal): the operator's uncommitted work is no longer in the working tree"
  FAILURES=$((FAILURES + 1))
fi

# ...and the removed flag is a LOUD refusal, never a silently-ignored argument.
# LVA-120's lesson as a test: deleting an argument branch once let a flag fall
# through to the catch-all, where `*) shift` read it as a repository path and
# the run continued in a mode nobody asked for.
c4b_flag_exit=0
c4b_flag_out="$(
  LAVA_ADVANCE_RECORD_DIR="${c4b_root}/records-flag" \
  LAVA_ADVANCE_VERIFY_CMD="true" \
  "$SCRIPT_UNDER_TEST" --allow-local-path-remotes --publish-local-modifications "${c4b_root}/parent" 2>&1
)" || c4b_flag_exit=$?
expect_eq "case4b(removed-flag)" "exit code for --publish-local-modifications" "2" "$c4b_flag_exit"
expect_eq "case4b(removed-flag)" "records written" "0" \
  "$(find "${c4b_root}/records-flag" -type f -name '*.json' 2>/dev/null | wc -l | tr -d ' ')"
if grep -qF -- "REMOVED" <<<"$c4b_flag_out"; then
  echo "PASS: case4b(removed-flag): the refusal states the capability was REMOVED"
else
  echo "FAIL: case4b(removed-flag): the refusal does not say the capability was removed"
  FAILURES=$((FAILURES + 1))
fi
if grep -qF -- "more than one repository path" <<<"$c4b_flag_out"; then
  echo "FAIL: case4b(removed-flag): the flag fell through and was read as a REPOSITORY PATH (the LVA-120 failure mode)"
  FAILURES=$((FAILURES + 1))
else
  echo "PASS: case4b(removed-flag): the flag was not mistaken for a repository path"
fi

echo "---"
# =========================================================================
# Case 5: a GOVERNANCE-DENIED submodule is never advanced, even when a newer
#         upstream commit exists and the rebuild-and-test step would pass.
#
# WHY (forensic anchor, 2026-08-21): this script defaulted to "every
# submodule" (its own LAVA_ADVANCE_SUBMODULES docs say so) and contained ZERO
# mention of `constitution`. Root CLAUDE.md line 299 states plainly: "The
# ./constitution/ submodule itself remains pinned + advanced only per
# CONST-049's 7-step pipeline." An unattended run would therefore have
# auto-advanced the project's OWN GOVERNANCE submodule — the document set that
# defines what this pipeline is allowed to do. Found while verifying a claim
# made in the T048/T049 constitutional-amendment drafts, before the script had
# ever been run against a real submodule.
#
# The deny is deliberately NOT silent. A denied submodule still gets a
# Submodule Advance Record, because a governance refusal that leaves no
# evidence is invisible at rest — the same "silently absent" failure mode as a
# test category with no dispatch line. It is recorded, not skipped.
# =========================================================================
_saved_sub_path="$SUB_PATH"
SUB_PATH="constitution"
c5_root="$(make_fixture "governance-deny" 1)"
FIXTURE_DIRS+=("$c5_root")
c5_records="${c5_root}/records"
c5_pin_before="$(parent_pin "${c5_root}/parent")"
c5_upstream_head="$(git -C "${c5_root}/upstream.git" rev-parse master)"

# verify-cmd is `true`: the rebuild-and-test step would PASS. Nothing except
# the governance deny may stop this advance, so if the pin moves, the deny
# did not happen.
c5_exit="$(run_script "${c5_root}/parent" "$c5_records" "true")"

expect_eq "case5(governance-deny)" "exit code" "0" "$c5_exit"
expect_ne "case5(governance-deny)" "upstream really is ahead of the pin" "$c5_pin_before" "$c5_upstream_head"

if c5_rec="$(find_record "$c5_records")"; then
  echo "PASS: case5(governance-deny): a Submodule Advance Record was written for ${SUB_PATH} (the refusal is recorded, not silent)"
  expect_eq "case5(governance-deny)" "outcome" "REFUSED_GOVERNANCE_DENY" "$(jq -r '.outcome' "$c5_rec")"
  expect_eq "case5(governance-deny)" "parent_pin_updated" "false" "$(jq -r '.parent_pin_updated' "$c5_rec")"
  expect_eq "case5(governance-deny)" "local_modifications_pushed" "false" "$(jq -r '.local_modifications_pushed' "$c5_rec")"
else
  echo "FAIL: case5(governance-deny): no Submodule Advance Record found under ${c5_records} — a governance refusal that leaves no evidence is invisible at rest; script output: ${LAST_OUTPUT}"
  FAILURES=$((FAILURES + 3))
fi

expect_eq "case5(governance-deny)" "parent pin NOT advanced" "$c5_pin_before" "$(parent_pin "${c5_root}/parent")"
expect_eq "case5(governance-deny)" "submodule HEAD NOT advanced" "$c5_pin_before" "$(git -C "${c5_root}/parent/${SUB_PATH}" rev-parse HEAD 2>/dev/null)"
SUB_PATH="$_saved_sub_path"

# (The over-correction guard for this case is Case 2 above: an ordinarily-named
# submodule with the identical setup DOES advance. If a "fix" denied everything,
# Case 2 would fail.)

echo "---"
# =========================================================================
# Case 6: a submodule that carries NO standing operator authorization is
#         refused — even though it is not `constitution`, and even though the
#         rebuild-and-test step would pass.
#
# WHY (forensic anchor, LVA-138, 2026-08-26): governance was a DENY-list with
# a single entry, `constitution`, while .gitmodules declares 25 submodules.
# Root CLAUDE.md's Decoupled Reusable Architecture pin policy names 16 as
# pins-frozen-by-default (each bump needs a deliberate per-submodule operator
# authorization) and carries the standing Q9 waiver for `helixqa` — leaving 7
# (doc_processor, llm_orchestrator, llm_provider, llms_verifier, panoptic,
# superspec, vision_engine) named by NO operator decision at all, yet
# mechanically advanceable by an unattended run. A deny-list can only ever be
# as correct as it is complete, and that one was 1/25 complete.
#
# The policy was inverted to default-DENY on explicit operator decision. This
# case is the regression proof: against the pre-inversion script BOTH fixtures
# below recorded outcome=ADVANCED with parent_pin_updated=true.
#
# Each fixture also asserts the refusal states its OWN true reason. A refusal
# that misstates its cause is a small bluff, and the reason table is the only
# part of this that can silently drift out of date with root CLAUDE.md.
#
# The `panoptic` expectation was updated 2026-08-26 (T054 SHOULD-FIX-6). The
# default arm used to assert a fact about the absence of operator decisions
# ANYWHERE on record — a claim that goes stale the moment an operator
# authorizes a submodule without that table being updated, i.e. exactly the
# confidently-wrong drift the function's own comment says the default exists
# to avoid. It now states what is true BY CONSTRUCTION: the submodule is not
# in GOVERNANCE_ALLOW, which is the only authorization this script can act on.
# =========================================================================
_saved_sub_path="$SUB_PATH"
C6_EXAMINED=0

# name | expected substring of the refusal reason | why this class exists
for c6_spec in \
  "panoptic|absent from GOVERNANCE_ALLOW|one of the 7 named by no decision at all" \
  "security|pins it frozen by default|one of the 16 requiring per-submodule authorization"
do
  c6_name="${c6_spec%%|*}"
  c6_rest="${c6_spec#*|}"
  c6_reason="${c6_rest%%|*}"
  c6_class="${c6_rest#*|}"

  SUB_PATH="$c6_name"
  c6_root="$(make_fixture "ungoverned-${c6_name}" 1)"
  FIXTURE_DIRS+=("$c6_root")
  c6_records="${c6_root}/records"
  c6_pin_before="$(parent_pin "${c6_root}/parent")"
  c6_upstream_head="$(git -C "${c6_root}/upstream.git" rev-parse master)"

  # verify-cmd is `true`: the rebuild-and-test step would PASS. Nothing except
  # the governance refusal may stop this advance.
  # Deliberately NOT `c6_exit="$(run_script ...)"`: run_script assigns the
  # script output to a global, but every caller invokes it inside a command
  # substitution, which is a SUBSHELL -- so that assignment never reaches the
  # caller and the global stays stale. Cases 1-5 only read it inside FAIL
  # branches that do not fire, so the staleness is invisible there; this case
  # asserts on the output on the SUCCESS path, so it must capture in the
  # current shell. (Harness bug recorded separately: every existing
  # FAIL-branch diagnostic in this file prints a stale value.)
  c6_exit=0
  c6_out="$(
    LAVA_ADVANCE_RECORD_DIR="$c6_records" \
    LAVA_ADVANCE_VERIFY_CMD="true" \
    "$SCRIPT_UNDER_TEST" "${ADV_FLAGS[@]}" "${c6_root}/parent" 2>&1
  )" || c6_exit=$?

  expect_eq "case6(${c6_name}: ${c6_class})" "exit code" "0" "$c6_exit"
  expect_ne "case6(${c6_name})" "upstream really is ahead of the pin" "$c6_pin_before" "$c6_upstream_head"

  if c6_rec="$(find_record "$c6_records")"; then
    expect_eq "case6(${c6_name})" "outcome" "REFUSED_GOVERNANCE_DENY" "$(jq -r '.outcome' "$c6_rec")"
    expect_eq "case6(${c6_name})" "parent_pin_updated" "false" "$(jq -r '.parent_pin_updated' "$c6_rec")"
  else
    echo "FAIL: case6(${c6_name}): no Submodule Advance Record found under ${c6_records} — a governance refusal that leaves no evidence is invisible at rest; script output: ${c6_out}"
    FAILURES=$((FAILURES + 2))
  fi

  expect_eq "case6(${c6_name})" "parent pin NOT advanced" "$c6_pin_before" "$(parent_pin "${c6_root}/parent")"
  expect_eq "case6(${c6_name})" "submodule HEAD NOT advanced" "$c6_pin_before" \
    "$(git -C "${c6_root}/parent/${SUB_PATH}" rev-parse HEAD 2>/dev/null)"

  # The refusal must state ITS OWN reason, not a borrowed one. Matching on a
  # herestring, never `printf | grep -q`: under `set -o pipefail` a short-
  # circuiting consumer SIGPIPEs the producer and turns a MATCH into a
  # NO-MATCH above the 64KB pipe buffer (LVA-135).
  if grep -qF "$c6_reason" <<<"$c6_out"; then
    echo "PASS: case6(${c6_name}): refusal states its own true reason ('${c6_reason}')"
  else
    echo "FAIL: case6(${c6_name}): refusal did not state the reason for its class."
    echo "      expected substring: ${c6_reason}"
    echo "      actual output: ${c6_out}"
    FAILURES=$((FAILURES + 1))
  fi

  C6_EXAMINED=$((C6_EXAMINED + 1))
done
SUB_PATH="$_saved_sub_path"

# A gate that passes having examined nothing is the vacuous-pass defect this
# repo has ~50 recorded instances of. If the loop body never ran — a typo in
# the spec list, a shell-word-splitting change — every assertion above is
# silently absent and this case would otherwise report success.
if [[ "$C6_EXAMINED" -eq 2 ]]; then
  echo "PASS: case6: examined ${C6_EXAMINED} ungoverned-submodule classes"
else
  echo "FAIL: case6: examined ${C6_EXAMINED} classes, expected 2 — the case list did not iterate, so its assertions never ran"
  FAILURES=$((FAILURES + 1))
fi

if [[ "$FAILURES" -eq 0 ]]; then
  echo "PASS: all advance-all-submodules test cases passed"
  exit 0
else
  echo "FAIL: ${FAILURES} advance-all-submodules test assertion(s) failed"
  exit 1
fi

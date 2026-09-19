#!/usr/bin/env bash
# Hermetic test suite for scripts/pipeline/phase-07-closure.sh (T055,
# FR-014/FR-015/FR-016/FR-017).
#
# This test constructs isolated, throwaway git fixture repositories under
# temp directories -- disposable "parent" repos with disposable submodules
# wired to disposable LOCAL bare repos standing in for those submodules' own
# upstreams, plus two more local bare repos standing in for the parent's own
# "github"/"gitlab" mirror remotes (scripts/commit_all.sh only ever pushes to
# remotes literally named "github" or "gitlab") -- and invokes the real
# production script against each fixture via its documented <run_id>
# [repo-path] usage plus its two test-only environment overrides.
#
# SAFETY: it never touches this repository's real submodules/ tree, never
# reaches any network upstream (every "upstream" and every "mirror" here is a
# bare repo inside a temp dir), and every fixture is removed by the EXIT
# trap.
#
# Fixture A ("main"): one parent repo with three top-level submodules --
#   subs/helixqa   -- matches GOVERNANCE_ALLOW by basename, upstream is one
#                     commit ahead -> ADVANCED. Itself carries its own nested
#                     submodule (toolsub/leaf), already initialized, so
#                     recursing into it is this suite's proof of Requirement
#                     5 (a level-2 submodule gets examined and gets its own
#                     Submodule Advance Record).
#   subs/auth      -- NOT governance-allowed, upstream is one commit ahead ->
#                     REFUSED_GOVERNANCE_DENY, and this suite proves it was
#                     never fetched (its origin/master tracking ref never
#                     moves, and the newer upstream commit object never lands
#                     in its local object store).
#   subs/dormant   -- added, pinned, then `git submodule deinit --force`d
#                     before the run so it starts UNINITIALIZED ('-' in
#                     `git submodule status`), upstream one commit ahead.
#                     This suite proves this phase's own step (a) initializes
#                     it AT ITS ALREADY-RECORDED PIN (`submodule update
#                     --init`, never `--remote`) -- not at the newer upstream
#                     commit that governance denial would refuse anyway.
#
# Fixture B ("constitution exclusion"): a separate, smaller parent repo whose
# ONLY submodule is literally named `constitution`, which itself carries a
# nested submodule (inner/thing, already initialized and clean, with its own
# upstream one commit ahead of what's pinned). This is a DIFFERENT assertion
# from "constitution is governance-denied AS A SUBMODULE" (already covered by
# tests/pipeline/test_advance_all_submodules.sh's case 5) -- it proves this
# phase's OWN separate exclusion never treats `constitution` as a LEVEL to
# recurse INTO: no per-level advance log or record directory is ever created
# for it, and its own nested submodule is never touched (its checked-out
# commit stays exactly the old pin; the newer upstream commit is never
# fetched).
#
# Exit 0 if every case passes; non-zero otherwise.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT_UNDER_TEST="${REPO_ROOT}/scripts/pipeline/phase-07-closure.sh"
RUN_REPORT_LIB="${REPO_ROOT}/scripts/pipeline/lib/run-report.sh"

for tool in jq python3 git; do
  command -v "$tool" >/dev/null 2>&1 || { echo "FAIL: '$tool' is required to run this test suite but was not found on PATH"; exit 1; }
done
[[ -f "$SCRIPT_UNDER_TEST" ]] || { echo "FAIL: script under test not found: $SCRIPT_UNDER_TEST"; exit 1; }
[[ -f "$RUN_REPORT_LIB" ]] || { echo "FAIL: required library not found: $RUN_REPORT_LIB"; exit 1; }

FIXTURE_DIRS=()
cleanup() {
  for d in "${FIXTURE_DIRS[@]:-}"; do
    if [[ -n "$d" && -d "$d" ]]; then
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
expect_true() {
  local label="$1" what="$2" cond="$3"
  if [[ "$cond" == "true" ]]; then
    echo "PASS: ${label}: ${what}"
  else
    echo "FAIL: ${label}: ${what}"
    FAILURES=$((FAILURES + 1))
  fi
}

# git_quiet_config <repo-dir> -- fixture identity, matching
# tests/pipeline/test_advance_all_submodules.sh's own convention.
git_quiet_config() {
  git -C "$1" config user.email "fixture@example.invalid"
  git -C "$1" config user.name "Fixture"
  git -C "$1" config commit.gpgsign false
}

# make_bare_seed <dir> <seed-file-content> -- inits a throwaway repo, commits
# one file, clones it --bare to "<dir>.git" alongside, removes the throwaway
# clone, and echoes the bare repo's path. Used for every submodule's own
# "upstream" and the parent's own two mirror remotes alike.
make_bare_seed() {
  local seed="$1" content="$2" bare="${1}.git"
  git init --quiet --initial-branch=master "$seed"
  git_quiet_config "$seed"
  echo "$content" > "${seed}/seed.txt"
  git -C "$seed" add seed.txt
  git -C "$seed" commit --quiet -m "initial commit"
  git clone --quiet --bare "$seed" "$bare"
  rm -rf -- "$seed"
  echo "$bare"
}

# push_extra_commit <bare-repo> <scratch-dir> <message> <filename> -- adds one
# commit to the bare repo's master branch via a throwaway clone, matching
# tests/pipeline/test_advance_all_submodules.sh's push_upstream_commit.
push_extra_commit() {
  local bare="$1" scratch="$2" message="$3" filename="$4"
  git clone --quiet "$bare" "$scratch"
  git_quiet_config "$scratch"
  echo "$message" > "${scratch}/${filename}"
  git -C "$scratch" add "$filename"
  git -C "$scratch" commit --quiet -m "$message"
  git -C "$scratch" push --quiet origin HEAD:master
  rm -rf -- "$scratch"
}

# add_submodule <parent> <path> <bare-upstream> -- wires <bare-upstream> into
# <parent> at <path> via `git submodule add`, configures fixture identity
# inside the new submodule, but does NOT commit (callers commit once after
# adding everything, or per-submodule as needed).
add_submodule() {
  local parent="$1" path="$2" bare="$3"
  git -c protocol.file.allow=always -C "$parent" \
    submodule add --quiet "$bare" "$path" >/dev/null 2>&1
  git_quiet_config "${parent}/${path}"
}

# upstream_head <bare-repo> -- the bare repo's master tip SHA.
upstream_head() { git -C "$1" rev-parse master; }

# parent_pin <repo> <submodule-path> -- the gitlink SHA <repo>'s index
# currently records for <submodule-path>.
parent_pin() { git -C "$1" ls-files -s -- "$2" | awk '{print $2}'; }

# sub_head <repo> <submodule-path> -- the submodule working tree's current
# HEAD SHA (empty if uninitialized).
sub_head() { git -C "$1/$2" rev-parse HEAD 2>/dev/null || true; }

# find_record_by_name <record-root> <submodule_name> -- path of the first
# Submodule Advance Record anywhere under <record-root> (searched
# recursively, across every per-level subdirectory) whose submodule_name
# field equals <submodule_name>. Located by CONTENT, never by filename, so
# this test does not couple itself to the production script's own
# filename-sanitizing scheme -- same discipline as
# tests/pipeline/test_advance_all_submodules.sh's find_record.
find_record_by_name() {
  local root="$1" name="$2" f
  [[ -d "$root" ]] || return 1
  while IFS= read -r f; do
    if [[ "$(jq -r '.submodule_name' "$f" 2>/dev/null)" == "$name" ]]; then
      echo "$f"
      return 0
    fi
  done < <(find "$root" -type f -name '*.json' 2>/dev/null)
  return 1
}

# production_sanitize <string> -- byte-for-byte reimplementation of
# phase-07-closure.sh's own _sanitize(), used ONLY to compute the filesystem
# path this suite expects to be ABSENT for a level that must never be
# entered (constitution). Kept in exact lockstep with the production
# function's two-line algorithm; if that function ever changes, this one
# must change with it, exactly the same coupling
# tests/pipeline/test_advance_all_submodules.sh already accepts for its own
# find_record-by-content helper's filename-independence guarantee.
production_sanitize() {
  local raw="$1" sanitized
  sanitized="$(printf '%s' "$raw" | tr -c 'A-Za-z0-9._-' '_')"
  sanitized="$(printf '%s' "$sanitized" | sed -E 's/_{2,}/_/g; s/^_+//; s/_+$//')"
  [[ -n "$sanitized" ]] || sanitized="unnamed"
  printf '%s' "$sanitized"
}

# run_report_paths <run_id> -- echoes the report.json path, relative, exactly
# as scripts/pipeline/lib/run-report.sh's own _run_report_path computes it
# (CWD-relative; the caller must already have `cd`d into the target repo).
run_report_json_path() { printf '.lava-ci-evidence/pipeline-runs/%s/report.json' "$1"; }

RUN_ID="2026-01-01T00-00-00Z"

# =========================================================================
# Fixture A ("main"): helixqa advances (+nested), auth is denied+unfetched,
# dormant is uninitialized-then-init'd-at-its-recorded-pin.
# =========================================================================
build_fixture_a() {
  local root
  root="$(mktemp -d "${TMPDIR:-/tmp}/phase07-fixtureA-XXXXXX")"

  # --- upstreams, each starting at exactly ONE commit ----------------------
  local up_auth up_dormant up_leaf
  up_auth="$(make_bare_seed "${root}/seed-auth" "auth seed")"
  up_dormant="$(make_bare_seed "${root}/seed-dormant" "dormant seed")"
  up_leaf="$(make_bare_seed "${root}/seed-leaf" "leaf seed")"

  # --- helixqa's own upstream is built by hand (not make_bare_seed) because
  # its FIRST commit must already declare the nested submodule toolsub/leaf
  # -- that first commit is what this fixture pins the PARENT's helixqa to,
  # so the nested submodule is part of the pinned state from the start,
  # never introduced by the "advance". A second, later commit (pushed AFTER
  # the parent pins helixqa, below) is the one upstream actually advances
  # to. ------------------------------------------------------------------
  local helixqa_seed="${root}/helixqa-standalone" up_helixqa="${root}/seed-helixqa.git"
  git init --quiet --initial-branch=master "$helixqa_seed"
  git_quiet_config "$helixqa_seed"
  echo "helixqa seed" > "${helixqa_seed}/seed.txt"
  git -C "$helixqa_seed" add seed.txt
  git -C "$helixqa_seed" commit --quiet -m "helixqa initial commit"
  add_submodule "$helixqa_seed" "toolsub/leaf" "$up_leaf"
  git -C "$helixqa_seed" add -A
  git -C "$helixqa_seed" commit --quiet -m "helixqa: add nested submodule toolsub/leaf"
  git clone --quiet --bare "$helixqa_seed" "$up_helixqa"
  rm -rf -- "$helixqa_seed"

  # --- the parent repo itself, its .gitignore (matching production's own
  # `.lava-ci-evidence/pipeline-runs/` entry -- WITHOUT it, this phase's own
  # end-of-run evidence writes would dirty the tree it just committed, and
  # this fixture would report a spurious FR-017 violation that never
  # happens in production because .lava-ci-evidence/pipeline-runs/ is never
  # tracked there in the first place), plus its two mirror remotes. --------
  local parent="${root}/parent"
  git init --quiet --initial-branch=master "$parent"
  git_quiet_config "$parent"
  echo "parent" > "${parent}/parent.txt"
  printf '.lava-ci-evidence/pipeline-runs/\n' > "${parent}/.gitignore"
  git -C "$parent" add parent.txt .gitignore
  git -C "$parent" commit --quiet -m "parent initial commit"

  local mirror_github="${root}/mirror-github.git" mirror_gitlab="${root}/mirror-gitlab.git"
  git init --quiet --bare --initial-branch=master "$mirror_github"
  git init --quiet --bare --initial-branch=master "$mirror_gitlab"
  git -C "$parent" remote add github "$mirror_github"
  git -C "$parent" remote add gitlab "$mirror_gitlab"
  git -C "$parent" push --quiet github master
  git -C "$parent" push --quiet gitlab master

  # --- the parent's OWN copy of commit_all.sh, per the fix this task
  # applied to phase-07-closure.sh: COMMIT_ALL_SCRIPT is resolved relative
  # to the TARGET repository, never relative to wherever phase-07-closure.sh
  # itself lives, because commit_all.sh derives its own repo root from its
  # own BASH_SOURCE and unconditionally `cd`s there -- so the target
  # repository must carry its own copy, exactly as a real disposable clone
  # (tasks.md T056) would. ---------------------------------------------------
  mkdir -p "${parent}/scripts"
  cp "${REPO_ROOT}/scripts/commit_all.sh" "${parent}/scripts/commit_all.sh"
  chmod +x "${parent}/scripts/commit_all.sh"

  # --- pin all three submodules at their CURRENT (one-commit / two-commit)
  # upstream state first ----------------------------------------------------
  add_submodule "$parent" "subs/helixqa" "$up_helixqa"
  git -c protocol.file.allow=always -C "${parent}/subs/helixqa" \
    submodule update --init -- toolsub/leaf >/dev/null 2>&1
  add_submodule "$parent" "subs/auth" "$up_auth"
  add_submodule "$parent" "subs/dormant" "$up_dormant"

  git -C "$parent" add -A
  git -C "$parent" commit --quiet -m "pin three submodules"
  git -C "$parent" push --quiet github master
  git -C "$parent" push --quiet gitlab master

  # --- ONLY NOW push the commits that put each upstream ahead of its pin --
  push_extra_commit "$up_helixqa" "${root}/pusher-helixqa" "helixqa third commit" "h3.txt"
  push_extra_commit "$up_auth"    "${root}/pusher-auth"    "auth second commit"    "a2.txt"
  push_extra_commit "$up_dormant" "${root}/pusher-dormant" "dormant second commit" "d2.txt"

  git -C "$parent" submodule deinit --force -- subs/dormant >/dev/null 2>&1

  echo "$root"
}

fa_root="$(build_fixture_a)"
FIXTURE_DIRS+=("$fa_root")
fa_parent="${fa_root}/parent"

fa_helixqa_pin_before="$(parent_pin "$fa_parent" "subs/helixqa")"
fa_helixqa_upstream_head="$(upstream_head "${fa_root}/seed-helixqa.git")"
fa_auth_pin_before="$(parent_pin "$fa_parent" "subs/auth")"
fa_auth_upstream_head="$(upstream_head "${fa_root}/seed-auth.git")"
fa_auth_origin_ref_before="$(git -C "${fa_parent}/subs/auth" rev-parse --verify -q refs/remotes/origin/master 2>/dev/null || true)"
fa_dormant_pin_before="$(parent_pin "$fa_parent" "subs/dormant")"
fa_dormant_upstream_head="$(upstream_head "${fa_root}/seed-dormant.git")"

expect_ne "fixtureA(setup-sanity)" "helixqa upstream really is ahead of its pin" "$fa_helixqa_pin_before" "$fa_helixqa_upstream_head"
expect_ne "fixtureA(setup-sanity)" "auth upstream really is ahead of its pin"    "$fa_auth_pin_before"    "$fa_auth_upstream_head"
expect_ne "fixtureA(setup-sanity)" "dormant upstream really is ahead of its pin" "$fa_dormant_pin_before" "$fa_dormant_upstream_head"
expect_eq "fixtureA(setup-sanity)" "dormant starts uninitialized ('-' prefix)" "-" \
  "$(git -C "$fa_parent" submodule status -- subs/dormant | cut -c1)"
expect_eq "fixtureA(setup-sanity)" "parent tree is clean before the run" "" \
  "$(git -C "$fa_parent" status --porcelain --ignore-submodules=none)"

FA_RECORD_ROOT="${fa_parent}/.lava-ci-evidence/pipeline-runs/${RUN_ID}/submodule-advances"

fa_exit=0
FA_OUTPUT="$(
  cd "$fa_parent" \
    && source "$RUN_REPORT_LIB" \
    && init_run_report "$RUN_ID" "$(git rev-parse HEAD)" >/dev/null \
    && LAVA_ADVANCE_VERIFY_CMD="true" LAVA_CLOSURE_ALLOW_LOCAL_PATH_REMOTES="true" \
       bash "$SCRIPT_UNDER_TEST" "$RUN_ID" "$fa_parent" 2>&1
)" || fa_exit=$?

echo "----- fixture A raw output (for diagnosis on failure) -----"
echo "$FA_OUTPUT"
echo "----- end fixture A raw output -----"

expect_eq "fixtureA" "exit code" "0" "$fa_exit"

# --- Requirement 1: helixqa advances, and the parent ends committed+pushed -
expect_eq "fixtureA(helixqa)" "parent pin advanced to helixqa's upstream HEAD" \
  "$fa_helixqa_upstream_head" "$(parent_pin "$fa_parent" "subs/helixqa")"
expect_eq "fixtureA(helixqa)" "submodule HEAD advanced to helixqa's upstream HEAD" \
  "$fa_helixqa_upstream_head" "$(sub_head "$fa_parent" "subs/helixqa")"
if fa_helixqa_rec="$(find_record_by_name "$FA_RECORD_ROOT" "subs/helixqa")"; then
  echo "PASS: fixtureA(helixqa): a Submodule Advance Record exists for subs/helixqa"
  expect_eq "fixtureA(helixqa)" "outcome" "ADVANCED" "$(jq -r '.outcome' "$fa_helixqa_rec")"
else
  echo "FAIL: fixtureA(helixqa): no Submodule Advance Record found under ${FA_RECORD_ROOT} for subs/helixqa"
  FAILURES=$((FAILURES + 1))
fi
fa_parent_head="$(git -C "$fa_parent" rev-parse HEAD)"
expect_eq "fixtureA(helixqa)" "github mirror tip == local HEAD" "$fa_parent_head" \
  "$(git -C "${fa_root}/mirror-github.git" rev-parse master)"
expect_eq "fixtureA(helixqa)" "gitlab mirror tip == local HEAD" "$fa_parent_head" \
  "$(git -C "${fa_root}/mirror-gitlab.git" rev-parse master)"
# --- Requirement 5: helixqa's own nested submodule was examined -----------
if fa_leaf_rec="$(find_record_by_name "$FA_RECORD_ROOT" "toolsub/leaf")"; then
  echo "PASS: fixtureA(nested): a Submodule Advance Record exists for helixqa's nested toolsub/leaf"
  expect_eq "fixtureA(nested)" "outcome" "REFUSED_GOVERNANCE_DENY" "$(jq -r '.outcome' "$fa_leaf_rec")"
else
  echo "FAIL: fixtureA(nested): no Submodule Advance Record found for toolsub/leaf -- the nested level was never examined"
  FAILURES=$((FAILURES + 1))
fi

# --- Requirement 2: auth is denied and never fetched -----------------------
expect_eq "fixtureA(auth)" "parent pin NOT advanced" "$fa_auth_pin_before" "$(parent_pin "$fa_parent" "subs/auth")"
expect_eq "fixtureA(auth)" "submodule HEAD NOT advanced" "$fa_auth_pin_before" "$(sub_head "$fa_parent" "subs/auth")"
expect_eq "fixtureA(auth)" "origin/master tracking ref never moved (proves no fetch ran)" \
  "$fa_auth_origin_ref_before" "$(git -C "${fa_parent}/subs/auth" rev-parse --verify -q refs/remotes/origin/master 2>/dev/null || true)"
if git -C "${fa_parent}/subs/auth" cat-file -e "$fa_auth_upstream_head" 2>/dev/null; then
  echo "FAIL: fixtureA(auth): the newer upstream commit object IS present in the submodule's local store -- something fetched it"
  FAILURES=$((FAILURES + 1))
else
  echo "PASS: fixtureA(auth): the newer upstream commit object was never fetched into the submodule's local store"
fi
if fa_auth_rec="$(find_record_by_name "$FA_RECORD_ROOT" "subs/auth")"; then
  expect_eq "fixtureA(auth)" "outcome" "REFUSED_GOVERNANCE_DENY" "$(jq -r '.outcome' "$fa_auth_rec")"
else
  echo "FAIL: fixtureA(auth): no Submodule Advance Record found for subs/auth"
  FAILURES=$((FAILURES + 1))
fi

# --- Requirement 4: dormant is initialized at its RECORDED pin, not upstream
expect_eq "fixtureA(dormant)" "no longer uninitialized" "true" \
  "$([[ "$(git -C "$fa_parent" submodule status -- subs/dormant | cut -c1)" != "-" ]] && echo true || echo false)"
expect_eq "fixtureA(dormant)" "checked-out commit == the pin RECORDED before the run" \
  "$fa_dormant_pin_before" "$(sub_head "$fa_parent" "subs/dormant")"
expect_ne "fixtureA(dormant)" "checked-out commit != the newer upstream HEAD" \
  "$fa_dormant_upstream_head" "$(sub_head "$fa_parent" "subs/dormant")"
expect_eq "fixtureA(dormant)" "parent pin unchanged (governance still denies it once initialized)" \
  "$fa_dormant_pin_before" "$(parent_pin "$fa_parent" "subs/dormant")"

# --- Requirement 6: report.json aggregates every examined submodule + the
# closure phase result --------------------------------------------------
FA_REPORT="${fa_parent}/$(run_report_json_path "$RUN_ID")"
if [[ -f "$FA_REPORT" ]]; then
  echo "PASS: fixtureA(report): report.json exists at ${FA_REPORT}"
  fa_advances_len="$(jq '.submodule_advances | length' "$FA_REPORT")"
  expect_true "fixtureA(report)" "submodule_advances[] has at least 4 entries (helixqa, toolsub/leaf, auth, dormant), got ${fa_advances_len}" \
    "$([[ "$fa_advances_len" -ge 4 ]] && echo true || echo false)"
  for expect_name in "subs/helixqa" "toolsub/leaf" "subs/auth" "subs/dormant"; do
    fa_present="$(jq --arg n "$expect_name" '[.submodule_advances[] | select(.submodule_name == $n)] | length' "$FA_REPORT")"
    expect_true "fixtureA(report)" "submodule_advances[] contains an entry for ${expect_name}" \
      "$([[ "$fa_present" -ge 1 ]] && echo true || echo false)"
  done
  fa_closure_phase_len="$(jq '[.phases[] | select(.name == "closure" and .result == "PASS")] | length' "$FA_REPORT")"
  expect_true "fixtureA(report)" "phases[] contains {name: closure, result: PASS}" \
    "$([[ "$fa_closure_phase_len" -ge 1 ]] && echo true || echo false)"
else
  echo "FAIL: fixtureA(report): report.json not found at ${FA_REPORT}"
  FAILURES=$((FAILURES + 5))
fi

# --- Requirement 7: FR-017 end state --------------------------------------
expect_eq "fixtureA(FR-017)" "git status --porcelain is empty" "" "$(git -C "$fa_parent" status --porcelain)"
fa_dirty_recursive="$(git -C "$fa_parent" submodule status --recursive 2>/dev/null | grep -c '^[+-]' || true)"
expect_eq "fixtureA(FR-017)" "0 entries in 'git submodule status --recursive' show '+' or '-'" "0" "${fa_dirty_recursive:-0}"

echo "---"

# =========================================================================
# Fixture B ("constitution exclusion"): a top-level submodule literally
# named `constitution`, itself carrying its own initialized nested
# submodule. Proves this phase's OWN exclusion never treats `constitution`
# as a level to recurse INTO -- distinct from advance-all-submodules.sh's
# own (already separately tested) governance denial of `constitution` AS A
# SUBMODULE NAME.
# =========================================================================
build_fixture_b() {
  local root
  root="$(mktemp -d "${TMPDIR:-/tmp}/phase07-fixtureB-XXXXXX")"

  local up_inner up_constitution
  up_inner="$(make_bare_seed "${root}/seed-inner" "inner seed")"

  local const_seed="${root}/constitution-standalone"
  git init --quiet --initial-branch=master "$const_seed"
  git_quiet_config "$const_seed"
  echo "constitution" > "${const_seed}/c.txt"
  git -C "$const_seed" add c.txt
  git -C "$const_seed" commit --quiet -m "constitution initial commit"
  add_submodule "$const_seed" "inner/thing" "$up_inner"
  git -C "$const_seed" add -A
  git -C "$const_seed" commit --quiet -m "constitution: add nested submodule inner/thing"
  up_constitution="${root}/seed-constitution.git"
  git clone --quiet --bare "$const_seed" "$up_constitution"
  rm -rf -- "$const_seed"

  local parent="${root}/parent"
  git init --quiet --initial-branch=master "$parent"
  git_quiet_config "$parent"
  echo "parent" > "${parent}/parent.txt"
  printf '.lava-ci-evidence/pipeline-runs/\n' > "${parent}/.gitignore"
  git -C "$parent" add parent.txt .gitignore
  git -C "$parent" commit --quiet -m "parent initial commit"

  local mirror_github="${root}/mirror-github.git" mirror_gitlab="${root}/mirror-gitlab.git"
  git init --quiet --bare --initial-branch=master "$mirror_github"
  git init --quiet --bare --initial-branch=master "$mirror_gitlab"
  git -C "$parent" remote add github "$mirror_github"
  git -C "$parent" remote add gitlab "$mirror_gitlab"
  git -C "$parent" push --quiet github master
  git -C "$parent" push --quiet gitlab master

  mkdir -p "${parent}/scripts"
  cp "${REPO_ROOT}/scripts/commit_all.sh" "${parent}/scripts/commit_all.sh"
  chmod +x "${parent}/scripts/commit_all.sh"

  add_submodule "$parent" "constitution" "$up_constitution"
  # Initialize constitution's OWN nested submodule at ITS recorded pin, so
  # the recursive tree is clean by construction and this fixture's only
  # variable is whether this phase enters constitution's tree at all --
  # never whether an unrelated uninitialized-nested-submodule trips FR-017.
  git -c protocol.file.allow=always -C "${parent}/constitution" \
    submodule update --init -- inner/thing >/dev/null 2>&1

  git -C "$parent" add -A
  git -C "$parent" commit --quiet -m "pin constitution"
  git -C "$parent" push --quiet github master
  git -C "$parent" push --quiet gitlab master

  # ONLY NOW push a second commit to inner/thing's own upstream -- AFTER its
  # local clone (created by the `submodule update --init` above) already
  # exists, so that clone's object store provably does NOT contain this
  # commit unless something fetches it afterward. Pushing this earlier (as
  # an initial draft of this fixture did) would be a bluff: an ordinary
  # `git clone` fetches a branch's entire history up to whatever the remote
  # tip is AT CLONE TIME, so if this commit already existed on the remote
  # before the clone, it would land in the local object store as a harmless
  # side effect of cloning itself -- proving nothing about whether this
  # phase later fetched anything.
  push_extra_commit "$up_inner" "${root}/pusher-inner" "inner second commit" "i2.txt"

  echo "$root"
}

fb_root="$(build_fixture_b)"
FIXTURE_DIRS+=("$fb_root")
fb_parent="${fb_root}/parent"

fb_inner_head_before="$(sub_head "${fb_parent}/constitution" "inner/thing")"
fb_inner_upstream_head="$(upstream_head "${fb_root}/seed-inner.git")"
expect_ne "fixtureB(setup-sanity)" "inner/thing upstream really is ahead of its pin" "$fb_inner_head_before" "$fb_inner_upstream_head"
expect_eq "fixtureB(setup-sanity)" "recursive submodule tree starts clean" "0" \
  "$(git -C "$fb_parent" submodule status --recursive 2>/dev/null | grep -c '^[+-]' || true)"
expect_eq "fixtureB(setup-sanity)" "parent tree is clean before the run" "" \
  "$(git -C "$fb_parent" status --porcelain --ignore-submodules=none)"

FB_RECORD_ROOT="${fb_parent}/.lava-ci-evidence/pipeline-runs/${RUN_ID}/submodule-advances"

fb_exit=0
FB_OUTPUT="$(
  cd "$fb_parent" \
    && source "$RUN_REPORT_LIB" \
    && init_run_report "$RUN_ID" "$(git rev-parse HEAD)" >/dev/null \
    && LAVA_ADVANCE_VERIFY_CMD="true" LAVA_CLOSURE_ALLOW_LOCAL_PATH_REMOTES="true" \
       bash "$SCRIPT_UNDER_TEST" "$RUN_ID" "$fb_parent" 2>&1
)" || fb_exit=$?

echo "----- fixture B raw output (for diagnosis on failure) -----"
echo "$FB_OUTPUT"
echo "----- end fixture B raw output -----"

expect_eq "fixtureB" "exit code" "0" "$fb_exit"

# --- Requirement 3: constitution is never entered as a LEVEL --------------
# (a) advance-all-submodules.sh's OWN governance deny still fires for
#     `constitution` AS A SUBMODULE NAME of the top level -- this is the
#     PRE-EXISTING, separately-tested behavior; asserting it here is the
#     control that proves the fixture is wired correctly, not a duplicate of
#     what this suite is actually here to prove.
if fb_const_rec="$(find_record_by_name "$FB_RECORD_ROOT" "constitution")"; then
  expect_eq "fixtureB(control)" "advance-all-submodules.sh's own governance denies 'constitution' as a submodule name" \
    "REFUSED_GOVERNANCE_DENY" "$(jq -r '.outcome' "$fb_const_rec")"
else
  echo "FAIL: fixtureB(control): expected a REFUSED_GOVERNANCE_DENY record for the submodule named 'constitution' -- fixture is not wired as intended"
  FAILURES=$((FAILURES + 1))
fi

# (b) no per-LEVEL record directory exists for constitution's OWN repo (the
#     directory phase-07-closure.sh would have created had it ever invoked
#     advance-all-submodules.sh WITH constitution's own path as the target).
fb_excluded_level_path="${fb_parent}/constitution"
fb_excluded_sanitized="$(production_sanitize "$fb_excluded_level_path")"
if [[ -d "${FB_RECORD_ROOT}/${fb_excluded_sanitized}" ]]; then
  echo "FAIL: fixtureB(exclusion): a per-level record directory exists for constitution's own repo (${FB_RECORD_ROOT}/${fb_excluded_sanitized}) -- this phase entered it as a level"
  FAILURES=$((FAILURES + 1))
else
  echo "PASS: fixtureB(exclusion): no per-level record directory exists for constitution's own repo -- it was never treated as a level"
fi

# (c) no per-level advance log exists for it either (belt-and-braces on the
#     same claim, from the raw combined log rather than the record tree).
if grep -qF "advancing level '${fb_excluded_level_path}'" <<<"$FB_OUTPUT"; then
  echo "FAIL: fixtureB(exclusion): the combined log shows this phase advancing constitution's own repo as a level"
  FAILURES=$((FAILURES + 1))
else
  echo "PASS: fixtureB(exclusion): the combined log never shows constitution's own repo being advanced as a level"
fi

# (d) constitution's OWN nested submodule was never touched: still at
#     exactly the pin constitution recorded for it, never the newer upstream
#     commit -- the direct, content-level proof that this phase's exclusion
#     is real and not just an absence of bookkeeping artifacts.
expect_eq "fixtureB(exclusion)" "constitution's nested inner/thing untouched (still at its recorded pin)" \
  "$fb_inner_head_before" "$(sub_head "${fb_parent}/constitution" "inner/thing")"
expect_ne "fixtureB(exclusion)" "constitution's nested inner/thing was NOT advanced to its upstream HEAD" \
  "$fb_inner_upstream_head" "$(sub_head "${fb_parent}/constitution" "inner/thing")"
if git -C "${fb_parent}/constitution/inner/thing" cat-file -e "$fb_inner_upstream_head" 2>/dev/null; then
  echo "FAIL: fixtureB(exclusion): the newer upstream commit for constitution's nested submodule IS present locally -- something fetched it"
  FAILURES=$((FAILURES + 1))
else
  echo "PASS: fixtureB(exclusion): the newer upstream commit for constitution's nested submodule was never fetched"
fi
if find_record_by_name "$FB_RECORD_ROOT" "inner/thing" >/dev/null 2>&1; then
  echo "FAIL: fixtureB(exclusion): a Submodule Advance Record exists for constitution's OWN nested submodule -- it was examined, which this phase must never do"
  FAILURES=$((FAILURES + 1))
else
  echo "PASS: fixtureB(exclusion): no Submodule Advance Record exists anywhere for constitution's own nested submodule -- it was never examined"
fi

# --- Requirement 7 (repeated for fixture B): FR-017 + exit 0 + mirrors ----
expect_eq "fixtureB(FR-017)" "git status --porcelain is empty" "" "$(git -C "$fb_parent" status --porcelain)"
fb_dirty_recursive="$(git -C "$fb_parent" submodule status --recursive 2>/dev/null | grep -c '^[+-]' || true)"
expect_eq "fixtureB(FR-017)" "0 entries in 'git submodule status --recursive' show '+' or '-'" "0" "${fb_dirty_recursive:-0}"
fb_parent_head="$(git -C "$fb_parent" rev-parse HEAD)"
expect_eq "fixtureB(mirrors)" "github mirror tip == local HEAD" "$fb_parent_head" \
  "$(git -C "${fb_root}/mirror-github.git" rev-parse master)"
expect_eq "fixtureB(mirrors)" "gitlab mirror tip == local HEAD" "$fb_parent_head" \
  "$(git -C "${fb_root}/mirror-gitlab.git" rev-parse master)"

FB_REPORT="${fb_parent}/$(run_report_json_path "$RUN_ID")"
if [[ -f "$FB_REPORT" ]]; then
  fb_closure_phase_len="$(jq '[.phases[] | select(.name == "closure" and .result == "PASS")] | length' "$FB_REPORT")"
  expect_true "fixtureB(report)" "phases[] contains {name: closure, result: PASS}" \
    "$([[ "$fb_closure_phase_len" -ge 1 ]] && echo true || echo false)"
else
  echo "FAIL: fixtureB(report): report.json not found at ${FB_REPORT}"
  FAILURES=$((FAILURES + 1))
fi

echo "---"

if [[ "$FAILURES" -eq 0 ]]; then
  echo "PASS: all phase-07-closure test cases passed"
  exit 0
else
  echo "FAIL: ${FAILURES} phase-07-closure test assertion(s) failed"
  exit 1
fi

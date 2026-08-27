#!/usr/bin/env bash
# Tests for .githooks/pre-push SIGPIPE-under-pipefail correctness (finding F1).
#
# THE DEFECT UNDER TEST
# ---------------------
# .githooks/pre-push sets `set -euo pipefail` (line 34) and then gates several
# constitutional checks on pipelines of the shape:
#
#     git diff-tree --no-commit-id --name-only -r "$sha" | grep -qE '<pattern>'
#
# Affected sites: line 63 (Check 1, Local-Only CI/CD hosted-CI config),
# line 135 (§6.Y version-bump-first), line 137 (§6.Y build.gradle.kts probe),
# line 167 (Check 7, §6.Z distribute test-evidence) and line 210 (Check 10,
# §6.AK cycle-coverage, which additionally uses `|| continue`).
#
# `grep -q` exits 0 on the FIRST match. When the commit's path list exceeds the
# kernel pipe buffer (F_GETPIPE_SZ, 65536 bytes on this host), `git` is still
# writing when `grep` exits, so `git` dies of SIGPIPE and reports 141.
# `pipefail` promotes that 141 to the pipeline's status, so the enclosing `if`
# evaluates FALSE. A MATCH is therefore reported as NO-MATCH and the check is
# silently skipped -- the hook allows a push it is required to reject.
#
# Below 65536 bytes the whole path list fits in the pipe buffer, `git` finishes
# writing before `grep` exits, no SIGPIPE occurs, and the check behaves
# correctly. That is why every pre-existing tests/pre-push/*.sh fixture (all of
# which produce commits of a few dozen bytes) passes while the defect is live.
#
# TEST STRATEGY (anti-bluff)
# --------------------------
# Each check is exercised TWICE against the real, unmodified hook:
#   * a SMALL commit  (< 100 bytes of paths)  -- the CONTROL. Must fire on
#     current code. If it ever fails, the harness or the hook wiring rotted and
#     the large-commit result below would be meaningless.
#   * a LARGE commit  (~260 KB of paths, 4x the pipe buffer) -- the REGRESSION.
#     Must fire, and does NOT on current code.
#
# The pairing is what makes this test honest: the two cases differ ONLY in the
# number of unrelated filler paths in the commit. Identical violating content,
# identical assertion, opposite verdict => the defect is isolated to the pipe
# buffer, not to the fixture or the harness.
#
# Each assertion is repeated TRIALS times because the underlying failure is a
# race. At 4x the pipe buffer the bluff is 100% reproducible on this host, but
# requiring ALL trials to fire keeps the test honest on faster/slower machines
# instead of passing on a lucky sample.
#
# EXPECTED RESULT AGAINST CURRENT (UNFIXED) CODE: the two *_large_* tests FAIL.
# EXPECTED RESULT AGAINST A CORRECT FIX:          all four tests PASS.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HOOK="$REPO_ROOT/.githooks/pre-push"

# Filler paths pushed into the violating commit to cross the pipe buffer.
# 1200 paths x ~215 bytes ~= 260 KB, i.e. ~4x F_GETPIPE_SZ (65536).
FILLER_COUNT="${LAVA_SIGPIPE_TEST_FILLER:-1200}"
TRIALS="${LAVA_SIGPIPE_TEST_TRIALS:-5}"

PTR_DIR=".lava-ci-evidence/distribute-changelog/firebase-app-distribution"

# Helper: run the real hook against a fixture, capture stdout+stderr.
# Mirrors the run_hook helper used by check4_test.sh / check7_test.sh.
run_hook() {
  local fixture_dir=$1 sha=$2
  cd "$fixture_dir"
  echo "refs/heads/master $sha refs/heads/master 0000000000000000000000000000000000000000" | \
    "$HOOK" origin "$fixture_dir" 2>&1
}

# Helper: create FILLER_COUNT unrelated files whose paths sort AFTER the
# violating path, so `grep -q` matches early and leaves `git` mid-write.
# That ordering is the realistic one: both violating paths under test are
# dotfiles ('.github/...', '.lava-ci-evidence/...') and sort first.
add_filler() {
  local n=$1 pad i
  [[ "$n" -gt 0 ]] || return 0
  mkdir -p filler
  pad=$(printf "%0.sx" $(seq 1 200))
  for i in $(seq 1 "$n"); do
    : > "filler/${pad}-$(printf '%05d' "$i").md"
  done
}

# Helper: assert the hook emits $needle on EVERY trial. Reports the observed
# path-list size so a failure carries its own forensic detail.
assert_check_fires() {
  local label=$1 fixture=$2 sha=$3 needle=$4
  local fired=0 skipped=0 i out bytes paths
  cd "$fixture"
  bytes=$(git diff-tree --no-commit-id --name-only -r "$sha" | wc -c | tr -d ' ')
  paths=$(git diff-tree --no-commit-id --name-only -r "$sha" | wc -l | tr -d ' ')
  for i in $(seq 1 "$TRIALS"); do
    out=$(run_hook "$fixture" "$sha" || true)
    if grep -qF "$needle" <<<"$out"; then
      fired=$((fired + 1))
    else
      skipped=$((skipped + 1))
    fi
  done
  if [[ "$fired" -eq "$TRIALS" ]]; then
    echo "PASS $label (${paths} paths / ${bytes} B; fired ${fired}/${TRIALS})"
  else
    echo "FAIL $label: expected \"$needle\" on all ${TRIALS} trials."
    echo "     commit path list = ${paths} paths / ${bytes} bytes (pipe buffer = 65536)"
    echo "     fired=${fired}  SILENTLY SKIPPED=${skipped}"
    echo "     The hook did not reject a commit it is required to reject."
    exit 1
  fi
}

# ---------------------------------------------------------------------------
# Check 1 (line 63) -- Local-Only CI/CD: hosted-CI config introduction.
# A commit adding .github/workflows/* MUST be rejected.
# ---------------------------------------------------------------------------
build_check1_fixture() {
  local f=$1 filler=$2
  cd "$f"
  git init -q
  git config user.email t@test
  git config user.name test
  echo "init" > .gitkeep && git add . && git commit -qm "init"
  mkdir -p .github/workflows
  echo "on: push" > .github/workflows/ci.yml
  add_filler "$filler"
  git add -A && git commit -qm "introduce hosted-CI config"
}

test_check1_small_commit_detected() {
  local f; f=$(mktemp -d)
  build_check1_fixture "$f" 0
  assert_check_fires "test_check1_small_commit_detected [CONTROL]" \
    "$f" "$(git -C "$f" rev-parse HEAD)" "introduces hosted-CI config"
}

test_check1_large_commit_detected() {
  local f; f=$(mktemp -d)
  build_check1_fixture "$f" "$FILLER_COUNT"
  assert_check_fires "test_check1_large_commit_detected [REGRESSION]" \
    "$f" "$(git -C "$f" rev-parse HEAD)" "introduces hosted-CI config"
}

# ---------------------------------------------------------------------------
# Check 7 (line 167) -- §6.Z: a commit advancing last-version-debug without a
# companion test-evidence file MUST be rejected.
# ---------------------------------------------------------------------------
build_check7_fixture() {
  local f=$1 filler=$2
  cd "$f"
  git init -q
  git config user.email t@test
  git config user.name test
  mkdir -p app "$PTR_DIR"
  cat > app/build.gradle.kts <<'GRADLE'
android {
    defaultConfig {
        versionCode = 1042
        versionName = "1.2.22"
    }
}
GRADLE
  echo "## Lava-Android-1.2.22-1042 — snapshot" > "$PTR_DIR/1.2.22-1042.md"
  echo "1041" > "$PTR_DIR/last-version-debug"
  git add -A && git commit -qm "init at vc=1042 / pointer=1041"
  # Advance the pointer WITHOUT shipping the required evidence file.
  echo "1042" > "$PTR_DIR/last-version-debug"
  add_filler "$filler"
  git add -A && git commit -qm "advance pointer 1041→1042 without evidence"
}

test_check7_small_commit_detected() {
  local f; f=$(mktemp -d)
  build_check7_fixture "$f" 0
  assert_check_fires "test_check7_small_commit_detected [CONTROL]" \
    "$f" "$(git -C "$f" rev-parse HEAD)" "§6.Z violation"
}

test_check7_large_commit_detected() {
  local f; f=$(mktemp -d)
  build_check7_fixture "$f" "$FILLER_COUNT"
  assert_check_fires "test_check7_large_commit_detected [REGRESSION]" \
    "$f" "$(git -C "$f" rev-parse HEAD)" "§6.Z violation"
}

test_check1_small_commit_detected
test_check1_large_commit_detected
test_check7_small_commit_detected
test_check7_large_commit_detected
echo "all pre-push SIGPIPE/pipefail tests passed"

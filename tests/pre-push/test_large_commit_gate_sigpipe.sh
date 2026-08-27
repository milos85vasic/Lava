#!/usr/bin/env bash
# tests/pre-push/test_large_commit_gate_sigpipe.sh
#
# Regression test for LVA-135 — pre-push gates going blind on large commits.
#
# THE DEFECT
# ----------
# .githooks/pre-push sets `set -euo pipefail`, then gated several
# constitutional checks on pipelines of the shape:
#
#     if git diff-tree --no-commit-id --name-only -r "$sha" | grep -q '<pat>'
#
# `grep -q` exits 0 at its FIRST match. When the commit's path list exceeds the
# kernel pipe buffer (F_GETPIPE_SZ = 65536 bytes) the producer is still writing
# when grep exits, so git dies of SIGPIPE and reports 141. `pipefail` promotes
# 141 to the pipeline status, so the enclosing `if` evaluates FALSE. A MATCH is
# reported as NO-MATCH and the gate is silently skipped — the hook allows a push
# it is required to reject. Measured on real commit f1a2c362 (1530 paths /
# 97783 bytes): status 141, PIPESTATUS=(141 0), match seen 0/20 trials.
#
# Below 65536 bytes the whole list fits in the buffer, git finishes writing
# before grep exits, no SIGPIPE occurs, and the gate behaves correctly. That is
# why every pre-existing tests/pre-push/*.sh fixture (all a few dozen bytes of
# paths) passed while the gates were blind on real-world commits.
#
# TEST STRATEGY (anti-bluff, §6.J / Seventh Law clause 1)
# ------------------------------------------------------
# Every gate is exercised against the REAL, unmodified .githooks/pre-push — the
# gate logic is never reimplemented here, because a test that reimplements the
# thing it claims to verify proves nothing about production.
#
# Each affected gate is driven TWICE:
#   * SMALL commit (< 65536 B of producer output) — the CONTROL. Fires even on
#     the broken code. If this ever fails, the harness rotted and the large-
#     commit verdict below would be meaningless.
#   * LARGE commit (> 65536 B) — the REGRESSION. Fires only once the pipe is
#     eliminated.
# The two differ ONLY in the number of unrelated filler paths. Identical
# violating content, identical assertion, opposite verdict on broken code.
#
# The producer's output size is MEASURED and ASSERTED (> 65536 for the large
# cases, < 65536 for the small ones) so this test can never silently degrade
# into a small-commit test that passes for the wrong reason.
#
# Anti-inversion cases assert the fix did not make gates fire spuriously:
# a huge clean commit, a small clean commit, and a large commit that DOES carry
# the required version bump must all be accepted.
#
# Every assertion increments an examined counter that is printed and enforced
# non-zero — a gate suite that passes having examined nothing is the vacuous
# pass this repository has recorded ~50 times.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HOOK="$REPO_ROOT/.githooks/pre-push"

# F_GETPIPE_SZ on Linux. The measured flip point of the defect.
PIPE_BUF=65536
# 1200 paths x ~216 B ~= 259 KB, i.e. ~4x the pipe buffer.
FILLER_COUNT="${LVA135_FILLER:-1200}"
# The underlying failure is a race; require the verdict on EVERY trial rather
# than passing on a lucky sample.
TRIALS="${LVA135_TRIALS:-3}"

PTR_DIR=".lava-ci-evidence/distribute-changelog/firebase-app-distribution"
ZERO=0000000000000000000000000000000000000000

EXAMINED=0
FAILURES=0
FIXTURES=()

cleanup() {
  local d
  for d in "${FIXTURES[@]+"${FIXTURES[@]}"}"; do
    [[ -n "$d" && -d "$d" ]] && rm -rf "$d"
  done
  return 0
}
trap cleanup EXIT

note_fail() { echo "$@"; FAILURES=$((FAILURES + 1)); }

# Sets the global FIXTURE. NOT a command substitution: `new_fixture; f="$FIXTURE"` would
# run the body in a subshell, so the FIXTURES cleanup list would stay empty in
# the parent and every temp dir would leak.
new_fixture() {
  FIXTURE=$(mktemp -d)
  FIXTURES+=("$FIXTURE")
  git -C "$FIXTURE" init -q
  git -C "$FIXTURE" config user.email t@test
  git -C "$FIXTURE" config user.name test
}

# Unrelated filler files whose paths sort AFTER every violating path, so the
# early-exiting consumer matches near the head of the stream and leaves the
# producer mid-write. That ordering is the realistic one: the violating paths
# under test are dotfiles or 'app/...', which sort before 'filler/'.
add_filler() {
  local dir=$1 n=$2 pad i
  [[ "$n" -gt 0 ]] || return 0
  mkdir -p "$dir/filler"
  pad=$(printf '%0.sx' $(seq 1 200))
  for ((i = 1; i <= n; i++)); do
    : > "$dir/filler/${pad}-$(printf '%05d' "$i").md"
  done
}

path_list_bytes() { git -C "$1" diff-tree --no-commit-id --name-only -r "$2" | wc -c | tr -d ' '; }
path_list_count() { git -C "$1" diff-tree --no-commit-id --name-only -r "$2" | wc -l | tr -d ' '; }
file_patch_bytes() { git -C "$1" diff-tree -p --no-commit-id "$2" -- "$3" | wc -c | tr -d ' '; }

# The producer output MUST cross the pipe buffer, or the "large" case is not
# actually large and the test would pass for the wrong reason.
assert_over_buffer() {
  local label=$1 bytes=$2
  if [[ "$bytes" -le "$PIPE_BUF" ]]; then
    note_fail "FAIL  $label: fixture DEGRADED — producer output ${bytes} B <= pipe buffer ${PIPE_BUF} B."
    echo "      This case cannot exercise the SIGPIPE path. Raise LVA135_FILLER."
    return 1
  fi
  return 0
}

assert_under_buffer() {
  local label=$1 bytes=$2
  if [[ "$bytes" -ge "$PIPE_BUF" ]]; then
    note_fail "FAIL  $label: control fixture is NOT small — ${bytes} B >= pipe buffer ${PIPE_BUF} B."
    return 1
  fi
  return 0
}

run_hook() {
  local fixture=$1 sha=$2
  ( cd "$fixture" && echo "refs/heads/master $sha refs/heads/master $ZERO" | "$HOOK" origin "$fixture" 2>&1 ) || true
}

# Assert a gate REJECTS the commit, on every trial.
assert_gate_rejects() {
  local gate=$1 label=$2 fixture=$3 sha=$4 needle=$5 bytes=$6
  EXAMINED=$((EXAMINED + 1))
  local fired=0 blind=0 i out
  for ((i = 1; i <= TRIALS; i++)); do
    out=$(run_hook "$fixture" "$sha")
    if grep -qF -- "$needle" <<<"$out"; then fired=$((fired + 1)); else blind=$((blind + 1)); fi
  done
  if [[ "$fired" -eq "$TRIALS" ]]; then
    echo "PASS  $label  [$gate]  (${bytes} B producer output; rejected ${fired}/${TRIALS})"
  else
    note_fail "FAIL  $label  [$gate]"
    echo "      GATE WENT BLIND: $gate did NOT reject a commit it is required to reject."
    echo "      expected the hook to report: \"$needle\""
    echo "      producer output = ${bytes} bytes (pipe buffer = ${PIPE_BUF})"
    echo "      rejected=${fired}  SILENTLY ALLOWED=${blind}  of ${TRIALS} trials"
    echo "      This is the LVA-135 SIGPIPE-under-pipefail defect: a \`producer | grep -q\`"
    echo "      pipeline in .githooks/pre-push kills the producer, pipefail promotes 141,"
    echo "      and the enclosing \`if\` reads the match as NO-match."
    echo "      ---- last hook output ----"
    echo "$out" | sed 's/^/      /'
    echo "      --------------------------"
  fi
}

# Assert the hook ACCEPTS the commit (anti-inversion).
assert_hook_accepts() {
  local gate=$1 label=$2 fixture=$3 sha=$4 bytes=$5
  EXAMINED=$((EXAMINED + 1))
  local out
  out=$(run_hook "$fixture" "$sha")
  if grep -qF "PRE-PUSH HOOK REJECTED" <<<"$out"; then
    note_fail "FAIL  $label  [$gate]"
    echo "      INVERTED: the hook rejected a commit that violates nothing."
    echo "      producer output = ${bytes} bytes"
    echo "$out" | sed 's/^/      /'
  else
    echo "PASS  $label  [$gate]  (${bytes} B producer output; accepted)"
  fi
}

# ---------------------------------------------------------------------------
# Check 1 — Local-Only CI/CD: a commit adding .github/workflows/* MUST reject.
# ---------------------------------------------------------------------------
build_check1() {
  local f=$1 filler=$2
  echo init > "$f/.gitkeep"
  git -C "$f" add -A && git -C "$f" commit -qm "init"
  mkdir -p "$f/.github/workflows"
  echo "on: push" > "$f/.github/workflows/ci.yml"
  add_filler "$f" "$filler"
  git -C "$f" add -A && git -C "$f" commit -qm "introduce hosted-CI config"
}

test_check1_small() {
  local f sha b; new_fixture; f="$FIXTURE"; build_check1 "$f" 0
  sha=$(git -C "$f" rev-parse HEAD); b=$(path_list_bytes "$f" "$sha")
  assert_under_buffer "check1_small [CONTROL]" "$b" || return 0
  assert_gate_rejects "Check 1 / Local-Only CI-CD" "check1_small_commit_rejected [CONTROL]" "$f" "$sha" "introduces hosted-CI config" "$b"
}

test_check1_large() {
  local f sha b; new_fixture; f="$FIXTURE"; build_check1 "$f" "$FILLER_COUNT"
  sha=$(git -C "$f" rev-parse HEAD); b=$(path_list_bytes "$f" "$sha")
  assert_over_buffer "check1_large [REGRESSION]" "$b" || return 0
  assert_gate_rejects "Check 1 / Local-Only CI-CD" "check1_large_commit_rejected [REGRESSION]" "$f" "$sha" "introduces hosted-CI config" "$b"
}

# ---------------------------------------------------------------------------
# Check 3a — a *Test.kt that mocks its own System Under Test MUST reject.
# The producer here is `git diff-tree -p` for ONE file, so the LARGE case needs
# a large PATCH, not a large path list.
# ---------------------------------------------------------------------------
SUT_TEST_PATH="core/foo/src/test/kotlin/lava/foo/FooTest.kt"

build_check3a() {
  local f=$1 body_lines=$2 i
  echo init > "$f/.gitkeep"
  git -C "$f" add -A && git -C "$f" commit -qm "init"
  mkdir -p "$f/$(dirname "$SUT_TEST_PATH")"
  {
    echo "package lava.foo"
    # The forbidden pattern sits at the head of the file so the consumer
    # matches immediately and leaves the producer mid-write.
    echo "    private val sut = mockk<Foo>()"
    for ((i = 1; i <= body_lines; i++)); do
      echo "    // padding line $i to grow the patch beyond the pipe buffer"
    done
  } > "$f/$SUT_TEST_PATH"
  git -C "$f" add -A
  git -C "$f" commit -qm "test: add FooTest

Bluff-Audit: $SUT_TEST_PATH
  Mutation: n/a (fixture)
  Observed-Failure: n/a (fixture)
  Reverted: yes"
}

test_check3a_small() {
  local f sha b; new_fixture; f="$FIXTURE"; build_check3a "$f" 5
  sha=$(git -C "$f" rev-parse HEAD); b=$(file_patch_bytes "$f" "$sha" "$SUT_TEST_PATH")
  assert_under_buffer "check3a_small [CONTROL]" "$b" || return 0
  assert_gate_rejects "Check 3a / mock-the-SUT" "check3a_small_patch_rejected [CONTROL]" "$f" "$sha" "mocks the System Under Test" "$b"
}

test_check3a_large() {
  local f sha b; new_fixture; f="$FIXTURE"; build_check3a "$f" 3000
  sha=$(git -C "$f" rev-parse HEAD); b=$(file_patch_bytes "$f" "$sha" "$SUT_TEST_PATH")
  assert_over_buffer "check3a_large [REGRESSION]" "$b" || return 0
  assert_gate_rejects "Check 3a / mock-the-SUT" "check3a_large_patch_rejected [REGRESSION]" "$f" "$sha" "mocks the System Under Test" "$b"
}

# ---------------------------------------------------------------------------
# Check 6 — §6.Y: code touched after a distribute without bumping versionCode.
# ---------------------------------------------------------------------------
write_gradle() {
  cat > "$1/app/build.gradle.kts" <<GRADLE
android {
    defaultConfig {
        versionCode = $2
        versionName = "$3"
    }
}
GRADLE
}

build_check6() {
  local f=$1 filler=$2 bump=$3
  mkdir -p "$f/app" "$f/$PTR_DIR"
  write_gradle "$f" 1042 "1.2.22"
  echo "1042" > "$f/$PTR_DIR/last-version-debug"
  git -C "$f" add -A && git -C "$f" commit -qm "init at vc=1042"
  echo "// new feature" > "$f/app/Foo.kt"
  [[ "$bump" == "bump" ]] && write_gradle "$f" 1043 "1.2.23"
  add_filler "$f" "$filler"
  git -C "$f" add -A && git -C "$f" commit -qm "feature: touch code"
}

test_check6_small() {
  local f sha b; new_fixture; f="$FIXTURE"; build_check6 "$f" 0 nobump
  sha=$(git -C "$f" rev-parse HEAD); b=$(path_list_bytes "$f" "$sha")
  assert_under_buffer "check6_small [CONTROL]" "$b" || return 0
  assert_gate_rejects "Check 6 / §6.Y bump-first" "check6_small_commit_rejected [CONTROL]" "$f" "$sha" "§6.Y violation" "$b"
}

test_check6_large() {
  local f sha b; new_fixture; f="$FIXTURE"; build_check6 "$f" "$FILLER_COUNT" nobump
  sha=$(git -C "$f" rev-parse HEAD); b=$(path_list_bytes "$f" "$sha")
  assert_over_buffer "check6_large [REGRESSION]" "$b" || return 0
  assert_gate_rejects "Check 6 / §6.Y bump-first" "check6_large_commit_rejected [REGRESSION]" "$f" "$sha" "§6.Y violation" "$b"
}

# Anti-inversion: a LARGE commit that DOES bump must still be accepted. This
# covers the second §6.Y probe (the 'app/build.gradle.kts in diff?' condition),
# whose SIGPIPE failure mode is a FALSE POSITIVE rather than blindness.
test_check6_large_with_bump_accepted() {
  local f sha b; new_fixture; f="$FIXTURE"; build_check6 "$f" "$FILLER_COUNT" bump
  sha=$(git -C "$f" rev-parse HEAD); b=$(path_list_bytes "$f" "$sha")
  assert_over_buffer "check6_large_bumped [ANTI-INVERSION]" "$b" || return 0
  assert_hook_accepts "Check 6 / §6.Y bump probe" "check6_large_commit_with_bump_accepted [ANTI-INVERSION]" "$f" "$sha" "$b"
}

# ---------------------------------------------------------------------------
# Check 7 — §6.Z: advancing last-version-debug with no test-evidence file.
# ---------------------------------------------------------------------------
build_check7() {
  local f=$1 filler=$2
  mkdir -p "$f/app" "$f/$PTR_DIR"
  write_gradle "$f" 1042 "1.2.22"
  echo "## Lava-Android-1.2.22-1042 — snapshot" > "$f/$PTR_DIR/1.2.22-1042.md"
  echo "1041" > "$f/$PTR_DIR/last-version-debug"
  git -C "$f" add -A && git -C "$f" commit -qm "init at vc=1042 / pointer=1041"
  echo "1042" > "$f/$PTR_DIR/last-version-debug"
  add_filler "$f" "$filler"
  git -C "$f" add -A && git -C "$f" commit -qm "advance pointer 1041 to 1042 without evidence"
}

test_check7_small() {
  local f sha b; new_fixture; f="$FIXTURE"; build_check7 "$f" 0
  sha=$(git -C "$f" rev-parse HEAD); b=$(path_list_bytes "$f" "$sha")
  assert_under_buffer "check7_small [CONTROL]" "$b" || return 0
  assert_gate_rejects "Check 7 / §6.Z evidence" "check7_small_commit_rejected [CONTROL]" "$f" "$sha" "§6.Z violation" "$b"
}

test_check7_large() {
  local f sha b; new_fixture; f="$FIXTURE"; build_check7 "$f" "$FILLER_COUNT"
  sha=$(git -C "$f" rev-parse HEAD); b=$(path_list_bytes "$f" "$sha")
  assert_over_buffer "check7_large [REGRESSION]" "$b" || return 0
  assert_gate_rejects "Check 7 / §6.Z evidence" "check7_large_commit_rejected [REGRESSION]" "$f" "$sha" "§6.Z violation" "$b"
}

# Check 7 variant — no per-version snapshot on disk, so the hook must fall back
# to parsing versionName out of app/build.gradle.kts. That parse was a
# `git show | grep -oE | head -1 | sed` chain (another early-exit pipeline) and
# is now a captured-text match; this case keeps the rewritten path covered.
build_check7_no_snapshot() {
  local f=$1 filler=$2
  mkdir -p "$f/app" "$f/$PTR_DIR"
  write_gradle "$f" 1042 "1.2.22"
  echo "1041" > "$f/$PTR_DIR/last-version-debug"
  git -C "$f" add -A && git -C "$f" commit -qm "init at vc=1042 / pointer=1041 / no snapshot"
  echo "1042" > "$f/$PTR_DIR/last-version-debug"
  add_filler "$f" "$filler"
  git -C "$f" add -A && git -C "$f" commit -qm "advance pointer 1041 to 1042 without evidence"
}

test_check7_large_versionname_fallback() {
  local f sha b; new_fixture; f="$FIXTURE"; build_check7_no_snapshot "$f" "$FILLER_COUNT"
  sha=$(git -C "$f" rev-parse HEAD); b=$(path_list_bytes "$f" "$sha")
  assert_over_buffer "check7_large_fallback [REGRESSION]" "$b" || return 0
  assert_gate_rejects "Check 7 / §6.Z versionName fallback" "check7_large_versionname_fallback_rejected [REGRESSION]" \
    "$f" "$sha" "1.2.22-1042-test-evidence.md" "$b"
}

# ---------------------------------------------------------------------------
# Check 10 — §6.AK: pointer advance whose cycle-coverage gate fails.
# This site used `... | grep -qF ... || continue`, so its SIGPIPE failure mode
# is a silent `continue` past the whole gate.
# ---------------------------------------------------------------------------
build_check10() {
  local f=$1 filler=$2
  mkdir -p "$f/app" "$f/$PTR_DIR" "$f/scripts"
  write_gradle "$f" 1042 "1.2.22"
  echo "## Lava-Android-1.2.22-1042 — snapshot" > "$f/$PTR_DIR/1.2.22-1042.md"
  # §6.Z evidence present, so Check 7 stays quiet and Check 10 is isolated.
  echo "BUILD SUCCESSFUL" > "$f/$PTR_DIR/1.2.22-1042-test-evidence.md"
  echo "1041" > "$f/$PTR_DIR/last-version-debug"
  printf '#!/usr/bin/env bash\nexit 1\n' > "$f/scripts/check-cycle-coverage.sh"
  chmod +x "$f/scripts/check-cycle-coverage.sh"
  git -C "$f" add -A && git -C "$f" commit -qm "init at vc=1042 / pointer=1041"
  echo "1042" > "$f/$PTR_DIR/last-version-debug"
  add_filler "$f" "$filler"
  git -C "$f" add -A && git -C "$f" commit -qm "advance pointer 1041 to 1042"
}

test_check10_small() {
  local f sha b; new_fixture; f="$FIXTURE"; build_check10 "$f" 0
  sha=$(git -C "$f" rev-parse HEAD); b=$(path_list_bytes "$f" "$sha")
  assert_under_buffer "check10_small [CONTROL]" "$b" || return 0
  assert_gate_rejects "Check 10 / §6.AK coverage" "check10_small_commit_rejected [CONTROL]" "$f" "$sha" "§6.AK violation" "$b"
}

test_check10_large() {
  local f sha b; new_fixture; f="$FIXTURE"; build_check10 "$f" "$FILLER_COUNT"
  sha=$(git -C "$f" rev-parse HEAD); b=$(path_list_bytes "$f" "$sha")
  assert_over_buffer "check10_large [REGRESSION]" "$b" || return 0
  assert_gate_rejects "Check 10 / §6.AK coverage" "check10_large_commit_rejected [REGRESSION]" "$f" "$sha" "§6.AK violation" "$b"
}

# ---------------------------------------------------------------------------
# Anti-inversion: clean commits must be accepted at both sizes.
# ---------------------------------------------------------------------------
build_clean() {
  local f=$1 filler=$2
  echo init > "$f/.gitkeep"
  git -C "$f" add -A && git -C "$f" commit -qm "init"
  mkdir -p "$f/docs"
  echo "notes" > "$f/docs/README.md"
  add_filler "$f" "$filler"
  git -C "$f" add -A && git -C "$f" commit -qm "docs: nothing constitutional here"
}

test_clean_small_accepted() {
  local f sha b; new_fixture; f="$FIXTURE"; build_clean "$f" 0
  sha=$(git -C "$f" rev-parse HEAD); b=$(path_list_bytes "$f" "$sha")
  assert_under_buffer "clean_small [ANTI-INVERSION]" "$b" || return 0
  assert_hook_accepts "all gates" "clean_small_commit_accepted [ANTI-INVERSION]" "$f" "$sha" "$b"
}

test_clean_large_accepted() {
  local f sha b; new_fixture; f="$FIXTURE"; build_clean "$f" "$FILLER_COUNT"
  sha=$(git -C "$f" rev-parse HEAD); b=$(path_list_bytes "$f" "$sha")
  assert_over_buffer "clean_large [ANTI-INVERSION]" "$b" || return 0
  assert_hook_accepts "all gates" "clean_large_commit_accepted [ANTI-INVERSION]" "$f" "$sha" "$b"
}

# ---------------------------------------------------------------------------
# Structural guard: no early-exiting consumer may be reintroduced downstream of
# a pipe in the hook. This is what stops the DEFECT CLASS, not just this
# instance, from coming back the next time a check is added.
# ---------------------------------------------------------------------------
test_no_pipe_into_early_exiting_consumer() {
  EXAMINED=$((EXAMINED + 1))
  local offenders
  offenders=$(grep -nE '\|[[:space:]]*\\?[[:space:]]*(grep -[a-zA-Z]*q|head )' "$HOOK" | grep -v '^[0-9]*:[[:space:]]*#' || true)
  if [[ -n "$offenders" ]]; then
    note_fail "FAIL  no_pipe_into_early_exiting_consumer [STRUCTURAL]"
    echo "      .githooks/pre-push pipes into an early-exiting consumer under \`set -o pipefail\`."
    echo "      That consumer SIGPIPEs its producer once output exceeds ${PIPE_BUF} B, and"
    echo "      pipefail turns the resulting 141 into a false verdict (LVA-135)."
    echo "      Capture the producer into a variable and match with a herestring instead."
    echo "$offenders" | sed 's/^/      /'
  else
    echo "PASS  no_pipe_into_early_exiting_consumer [STRUCTURAL]  (0 offending pipelines)"
  fi
}

echo "=== LVA-135 pre-push large-commit SIGPIPE regression suite ==="
echo "    hook        : $HOOK"
echo "    pipe buffer : ${PIPE_BUF} B"
echo "    filler      : ${FILLER_COUNT} paths    trials: ${TRIALS}"
echo

test_check1_small
test_check1_large
test_check3a_small
test_check3a_large
test_check6_small
test_check6_large
test_check6_large_with_bump_accepted
test_check7_small
test_check7_large
test_check7_large_versionname_fallback
test_check10_small
test_check10_large
test_clean_small_accepted
test_clean_large_accepted
test_no_pipe_into_early_exiting_consumer

echo
echo "gates examined: ${EXAMINED}"
if [[ "$EXAMINED" -eq 0 ]]; then
  echo "FAIL: examined ZERO gates — a suite that asserts nothing is a vacuous pass, not a green run."
  exit 1
fi
if [[ "$FAILURES" -gt 0 ]]; then
  echo "FAILED: ${FAILURES} of ${EXAMINED} assertions failed"
  exit 1
fi
echo "all ${EXAMINED} LVA-135 pre-push assertions passed"

#!/usr/bin/env bash
# Hermetic test for scripts/scan-no-removelast-seqcoll.sh (LVA-054 guard).
#
# The scanner enumerates tracked production Kotlin via `git ls-files`, so each
# fixture is a throwaway git repo with a copy of the scanner. We assert BOTH
# directions (the discipline §6.A / §6.AB.3 prescribes):
#   - positive: a clean repo (only safe removeAt/first/last) passes (exit 0)
#   - falsifiability: reintroducing the unsafe removeLast()/getFirst()/... in
#     production Kotlin makes the scanner FAIL (exit 1) and name the file
#   - test/androidTest sources are NOT flagged (JVM-safe, out of scope)
#   - the `// seqcoll-safe:` opt-out suppresses an intentional custom-type use
#   - a comment-ONLY mention of removeLast()/... (the LVA-053 fix's own
#     explanatory docstring class) does NOT trip the gate (comment-strip branch)
#
# A scanner that cannot be made to fail by reintroducing the exact crash it
# claims to prevent would be a bluff gate; the falsifiability case is the proof.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCANNER_SRC="$REPO_ROOT/scripts/scan-no-removelast-seqcoll.sh"

fail=0

# Build a throwaway git repo containing the scanner + the given fixture files.
# Usage: make_repo <dir>; then write files under <dir>; then `git add -A`.
make_repo() {
  local d=$1
  mkdir -p "$d/scripts"
  cp "$SCANNER_SRC" "$d/scripts/scan-no-removelast-seqcoll.sh"
  chmod +x "$d/scripts/scan-no-removelast-seqcoll.sh"
  git -C "$d" init -q
  git -C "$d" config user.email t@example.com
  git -C "$d" config user.name t
}

run_scanner() {
  local d=$1
  git -C "$d" add -A
  ( cd "$d" && bash scripts/scan-no-removelast-seqcoll.sh ) 2>&1
  return "${PIPESTATUS[0]:-0}"
}

# --- Test 1: clean production code (safe APIs) passes ---
test_safe_apis_pass() {
  local d; d=$(mktemp -d)
  make_repo "$d"
  mkdir -p "$d/core/data/src/main/kotlin/lava/data"
  cat > "$d/core/data/src/main/kotlin/lava/data/Safe.kt" <<'KT'
package lava.data
fun f(l: MutableList<Int>): Int {
    val x = l.removeAt(l.lastIndex)
    val y = l.first()
    val z = l.last()
    l.removeAt(0)
    return x + y + z
}
KT
  local out rc=0
  out=$( ( cd "$d" && git add -A && bash scripts/scan-no-removelast-seqcoll.sh ) 2>&1 ) || rc=$?
  if [[ $rc -eq 0 ]]; then
    echo "PASS test_safe_apis_pass"
  else
    echo "FAIL test_safe_apis_pass: expected exit 0, got $rc: $out" >&2
    fail=1
  fi
  rm -rf "$d"
}

# --- Test 2 (falsifiability): unsafe removeLast() in production Kotlin fails ---
test_unsafe_removelast_fails() {
  local d; d=$(mktemp -d)
  make_repo "$d"
  mkdir -p "$d/core/data/src/main/kotlin/lava/data"
  cat > "$d/core/data/src/main/kotlin/lava/data/Bad.kt" <<'KT'
package lava.data
fun f(l: MutableList<Int>): Int = l.removeLast()
KT
  local out rc=0
  out=$( ( cd "$d" && git add -A && bash scripts/scan-no-removelast-seqcoll.sh ) 2>&1 ) || rc=$?
  if [[ $rc -ne 0 ]] && echo "$out" | grep -q 'Bad.kt'; then
    echo "PASS test_unsafe_removelast_fails"
  else
    echo "FAIL test_unsafe_removelast_fails: expected non-zero + Bad.kt flagged, got rc=$rc: $out" >&2
    fail=1
  fi
  rm -rf "$d"
}

# --- Test 3 (falsifiability): unsafe getFirst()/getLast()/removeFirst() fail ---
test_unsafe_accessors_fail() {
  local d; d=$(mktemp -d)
  make_repo "$d"
  mkdir -p "$d/feature/x/src/main/kotlin/lava/x"
  cat > "$d/feature/x/src/main/kotlin/lava/x/Bad2.kt" <<'KT'
package lava.x
fun a(l: List<Int>) = l.getFirst()
fun b(l: List<Int>) = l.getLast()
fun c(l: MutableList<Int>) = l.removeFirst()
KT
  local out rc=0
  out=$( ( cd "$d" && git add -A && bash scripts/scan-no-removelast-seqcoll.sh ) 2>&1 ) || rc=$?
  if [[ $rc -ne 0 ]] && echo "$out" | grep -q 'Bad2.kt'; then
    echo "PASS test_unsafe_accessors_fail"
  else
    echo "FAIL test_unsafe_accessors_fail: expected non-zero + Bad2.kt flagged, got rc=$rc: $out" >&2
    fail=1
  fi
  rm -rf "$d"
}

# --- Test 4: test/androidTest sources are out of scope (NOT flagged) ---
test_test_sources_exempt() {
  local d; d=$(mktemp -d)
  make_repo "$d"
  mkdir -p "$d/core/data/src/test/kotlin/lava/data" \
           "$d/app/src/androidTest/kotlin/lava/app"
  cat > "$d/core/data/src/test/kotlin/lava/data/SomeTest.kt" <<'KT'
package lava.data
fun t(l: MutableList<Int>) = l.removeLast()
KT
  cat > "$d/app/src/androidTest/kotlin/lava/app/SomeChallenge.kt" <<'KT'
package lava.app
fun c(l: MutableList<Int>) = l.removeLast()
KT
  local out rc=0
  out=$( ( cd "$d" && git add -A && bash scripts/scan-no-removelast-seqcoll.sh ) 2>&1 ) || rc=$?
  if [[ $rc -eq 0 ]]; then
    echo "PASS test_test_sources_exempt"
  else
    echo "FAIL test_test_sources_exempt: expected exit 0 (test sources out of scope), got $rc: $out" >&2
    fail=1
  fi
  rm -rf "$d"
}

# --- Test 5: the // seqcoll-safe: opt-out suppresses a flagged line ---
test_optout_suppresses() {
  local d; d=$(mktemp -d)
  make_repo "$d"
  mkdir -p "$d/core/data/src/main/kotlin/lava/data"
  cat > "$d/core/data/src/main/kotlin/lava/data/Custom.kt" <<'KT'
package lava.data
class MyDeque { fun removeLast(): Int = 0 }
fun f(q: MyDeque) = q.removeLast() // seqcoll-safe: MyDeque is not a java.util List
KT
  local out rc=0
  out=$( ( cd "$d" && git add -A && bash scripts/scan-no-removelast-seqcoll.sh ) 2>&1 ) || rc=$?
  if [[ $rc -eq 0 ]]; then
    echo "PASS test_optout_suppresses"
  else
    echo "FAIL test_optout_suppresses: expected exit 0 with opt-out, got $rc: $out" >&2
    fail=1
  fi
  rm -rf "$d"
}

# --- Test 6 (comment-strip branch, LVA-062): a removeLast() that appears ONLY
#     inside a comment (the LVA-053 fix's own explanatory docstring) is NOT a
#     real call and MUST NOT be flagged. This exercises the sed comment-stripping
#     branch (line/block/KDoc) in the scanner — without it, the gate would raise
#     a false positive on its own documentation, breaking the build. Proven
#     falsifiable: stubbing `stripped="$body"` (skipping the strip) makes this
#     fixture FAIL with the comment line flagged. ---
test_comment_only_mention_passes() {
  local d; d=$(mktemp -d)
  make_repo "$d"
  mkdir -p "$d/core/data/src/main/kotlin/lava/data"
  cat > "$d/core/data/src/main/kotlin/lava/data/Doc.kt" <<'KT'
package lava.data
// The LVA-053 fix replaced l.removeLast() with l.removeAt(l.lastIndex).
/* getFirst()/getLast() also desugar to SequencedCollection accessors. */
fun f(l: MutableList<Int>): Int = l.removeAt(l.lastIndex)
KT
  local out rc=0
  out=$( ( cd "$d" && git add -A && bash scripts/scan-no-removelast-seqcoll.sh ) 2>&1 ) || rc=$?
  if [[ $rc -eq 0 ]]; then
    echo "PASS test_comment_only_mention_passes"
  else
    echo "FAIL test_comment_only_mention_passes: expected exit 0 (comment-only mention out of scope), got $rc: $out" >&2
    fail=1
  fi
  rm -rf "$d"
}

# --- Test 7 (comment-strip discrimination): a REAL call with a trailing
#     `// note` is STILL flagged — comment-stripping must drop only the note,
#     never the live call. Without this, a naive whole-line strip would bluff a
#     pass on `l.removeLast() // ok`. ---
test_real_call_with_trailing_comment_fails() {
  local d; d=$(mktemp -d)
  make_repo "$d"
  mkdir -p "$d/core/data/src/main/kotlin/lava/data"
  cat > "$d/core/data/src/main/kotlin/lava/data/Trail.kt" <<'KT'
package lava.data
fun f(l: MutableList<Int>): Int = l.removeLast() // intentional, reviewed
KT
  local out rc=0
  out=$( ( cd "$d" && git add -A && bash scripts/scan-no-removelast-seqcoll.sh ) 2>&1 ) || rc=$?
  if [[ $rc -ne 0 ]] && echo "$out" | grep -q 'Trail.kt'; then
    echo "PASS test_real_call_with_trailing_comment_fails"
  else
    echo "FAIL test_real_call_with_trailing_comment_fails: expected non-zero + Trail.kt flagged, got rc=$rc: $out" >&2
    fail=1
  fi
  rm -rf "$d"
}

test_safe_apis_pass
test_unsafe_removelast_fails
test_unsafe_accessors_fail
test_test_sources_exempt
test_optout_suppresses
test_comment_only_mention_passes
test_real_call_with_trailing_comment_fails

if [[ $fail -ne 0 ]]; then
  echo "FAIL test_no_removelast_seqcoll (one or more cases failed)" >&2
  exit 1
fi
echo "PASS test_no_removelast_seqcoll (all 7 cases)"
exit 0

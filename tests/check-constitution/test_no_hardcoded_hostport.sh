#!/usr/bin/env bash
# Tests for scripts/scan-no-hardcoded-hostport.sh — the §6.R host:port
# scanner, focused on the comment-stripping behavior added 2026-06-08.
#
# The scanner depends on `git ls-files`, so each fixture is a throwaway
# git repo into which the real scanner is copied (its `cd "$(dirname
# "$0")/.."` then resolves to the fixture root). This makes the test
# fully hermetic and falsifiable — exactly the §6.J discipline the sibling
# test_no_hardcoded_uuid.sh follows, extended with the comment-vs-code
# discrimination this scanner now performs.
#
# Coverage:
#   1. live repo passes (the standing tree must be clean)
#   2. POSITIVE: a host:port literal that appears ONLY inside a comment /
#      KDoc / example string is NOT flagged (the false-positive class this
#      fix eliminates)
#   3. NEGATIVE: a host:port literal on a real (non-comment) code line IS
#      flagged — proving comment-stripping did not weaken real detection
#   4. NEGATIVE: a real literal that has a trailing `// note` is STILL
#      flagged (the `://`-preservation rule — a naive strip-from-first-`//`
#      would truncate the URL and bluff a pass)
#   5. POSITIVE: shell parameter-expansion `${VAR#http://x:9000}` is real
#      code, not a comment, and is correctly preserved (and since it is a
#      genuine host:port literal it IS flagged — confirming the `#` rule
#      only strips whitespace-led comments)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCANNER="$REPO_ROOT/scripts/scan-no-hardcoded-hostport.sh"

fail=0

# Build a throwaway git repo containing the supplied file body, copy the
# real scanner in, run it, echo the exit code.
run_fixture() {
  local relpath="$1"; shift
  local body="$1"; shift
  local dir
  dir="$(mktemp -d)"
  (
    cd "$dir"
    git init -q
    git config user.email t@example.com
    git config user.name t
    mkdir -p "$(dirname "$relpath")" scripts
    printf '%s\n' "$body" > "$relpath"
    cp "$SCANNER" scripts/scan-no-hardcoded-hostport.sh
    git add -A
    git -c commit.gpgsign=false commit -qm fixture
    bash scripts/scan-no-hardcoded-hostport.sh >/dev/null 2>&1
    echo "$?"
  )
  rm -rf "$dir"
}

# Test 1: live repo passes.
if bash "$SCANNER" >/dev/null 2>&1; then
  echo "PASS test_live_repo_passes"
else
  echo "FAIL test_live_repo_passes: scanner flagged the live tree" >&2
  bash "$SCANNER" >&2 || true
  fail=1
fi

# Test 2: comment-only literal passes (exit 0 expected).
rc=$(run_fixture src/Foo.kt '// default cloud API is e.g. "https://lava.app:7777"')
if [[ "$rc" == "0" ]]; then
  echo "PASS test_comment_only_literal_passes"
else
  echo "FAIL test_comment_only_literal_passes: expected 0, got $rc" >&2
  fail=1
fi

# Test 2b: KDoc continuation-line literal passes.
rc=$(run_fixture src/Bar.kt ' *   `https://192.168.1.5:8443`), built from the first LAN IP')
if [[ "$rc" == "0" ]]; then
  echo "PASS test_kdoc_literal_passes"
else
  echo "FAIL test_kdoc_literal_passes: expected 0, got $rc" >&2
  fail=1
fi

# Test 2c: shell `# `-comment literal passes.
rc=$(run_fixture run.sh '  # override (e.g. http://example.com:9000 on rootless podman).')
if [[ "$rc" == "0" ]]; then
  echo "PASS test_shell_comment_literal_passes"
else
  echo "FAIL test_shell_comment_literal_passes: expected 0, got $rc" >&2
  fail=1
fi

# Test 3: real code literal is caught (exit 1 expected).
rc=$(run_fixture src/Real.go 'var x = "http://evil.example.com:9000"')
if [[ "$rc" == "1" ]]; then
  echo "PASS test_real_code_literal_caught"
else
  echo "FAIL test_real_code_literal_caught: expected 1, got $rc" >&2
  fail=1
fi

# Test 4: real literal with a trailing // note is STILL caught (exit 1).
rc=$(run_fixture src/Trail.kt 'val u = "http://evil.example.com:9000" // see docs')
if [[ "$rc" == "1" ]]; then
  echo "PASS test_real_literal_with_trailing_comment_caught"
else
  echo "FAIL test_real_literal_with_trailing_comment_caught: expected 1, got $rc" >&2
  fail=1
fi

# Test 5: loopback + container-runtime host aliases are exempt (exit 0).
rc=$(run_fixture src/Loop.go 'var x = "http://host.containers.internal:9000"')
if [[ "$rc" == "0" ]]; then
  echo "PASS test_container_host_alias_exempt"
else
  echo "FAIL test_container_host_alias_exempt: expected 0, got $rc" >&2
  fail=1
fi

if [[ "$fail" == "0" ]]; then
  echo "ALL PASS test_no_hardcoded_hostport"
  exit 0
fi
exit 1

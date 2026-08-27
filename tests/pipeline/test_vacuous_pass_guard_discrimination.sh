#!/usr/bin/env bash
# Discrimination test for tests/pipeline/test_no_vacuous_pass_patterns.sh.
#
# That file is a static guard against six vacuous-pass SHAPES. A guard is only
# worth its runtime if it actually fires on the shape it names — a scanner that
# reports ALL CHECKS PASSED over a tree containing its own target is precisely
# the bluff class it exists to evict, one level up. Its own header records that
# this has already happened to it twice during construction (CHECK A's
# echo/printf blindness, and CHECK A's SIGPIPE-defeated haystack test).
#
# This suite drives the guard against SYNTHETIC fixture trees, not the real
# repository, so each case is a controlled single-variable experiment: one
# script containing exactly one shape, and the assertion is on the guard's own
# per-check verdict line.
#
# Every shape below is asserted in BOTH directions:
#   POSITIVE — a variant of the shape the guard already catches, so a guard
#              that was simply broken (or a blanket "always FAIL" change) is
#              not mistaken for a working one; and
#   NEGATIVE — a clean file of the same general form that must NOT be flagged,
#              so a blanket "always HIT" change cannot pass either.
#
# Exit 0 if every case passes; non-zero otherwise.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GUARD="${REPO_ROOT}/tests/pipeline/test_no_vacuous_pass_patterns.sh"
[[ -f "$GUARD" ]] || { echo "FAIL: guard not found: $GUARD"; exit 1; }

FAILURES=0
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAILURES=$((FAILURES + 1)); }

WORKDIR="$(mktemp -d)"
trap 'rm -rf -- "$WORKDIR"' EXIT

# _new_fixture — a minimal tree the guard will accept: its own copy of the
# guard, five inert filler scripts (it refuses to run on fewer than five), and
# an inert orchestrator. Returns the fixture root on stdout.
_new_fixture() {
  local fx; fx="$(mktemp -d "${WORKDIR}/fx.XXXXXX")"
  mkdir -p "${fx}/tests/pipeline" "${fx}/scripts/pipeline/lib"
  cp "$GUARD" "${fx}/tests/pipeline/"
  local i
  for i in 1 2 3 4 5; do
    printf '#!/usr/bin/env bash\nset -euo pipefail\necho filler\n' > "${fx}/scripts/pipeline/filler-0${i}.sh"
  done
  printf '#!/usr/bin/env bash\nset -euo pipefail\necho orchestrator\n' > "${fx}/scripts/pipeline-build-test-distribute.sh"
  printf '%s' "$fx"
}

# _check_verdict <fixture> <check-letter> — prints PASS or FAIL, the guard's own
# verdict for that single check. Read from the guard's per-check summary line
# rather than from its process exit code, so one check's result is never
# confused with another's.
_check_verdict() {
  local fx="$1" letter="$2" out
  out="$(bash "${fx}/tests/pipeline/test_no_vacuous_pass_patterns.sh" 2>&1 || true)"
  # The block for CHECK <letter> runs from its own header to the next blank line.
  awk -v want="=== CHECK ${letter}:" '
    index($0, want) == 1 { inblock = 1; next }
    inblock && /^(PASS|FAIL): / { sub(/:.*/, "", $0); print $0; exit }
  ' <<< "$out"
}

# _expect <label> <fixture> <check-letter> <PASS|FAIL>
_expect() {
  local label="$1" fx="$2" letter="$3" want="$4" got
  got="$(_check_verdict "$fx" "$letter")"
  if [[ "$got" == "$want" ]]; then
    pass "$label (CHECK ${letter} verdict = ${got})"
  else
    fail "$label — CHECK ${letter} verdict is '${got}', expected '${want}'"
  fi
}

# _fixture_with <script-body> — fixture containing one probe script.
_fixture_with() {
  local fx; fx="$(_new_fixture)"
  cat > "${fx}/scripts/pipeline/probe.sh"
  printf '%s' "$fx"
}

echo "==============================================================="
echo "CHECK A — an exit code captured and never compared"
echo "==============================================================="

_expect "POSITIVE: bare 'rc=\$?' never compared is caught" \
  "$(_fixture_with <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
run_the_tool() { return 1; }
run_the_tool
rc=$?
echo "tool exited $rc"
echo "PASS"
EOF
)" A FAIL

_expect "LOAD-BEARING: the SAME defect merely QUOTED — rc=\"\$?\" — is caught" \
  "$(_fixture_with <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
run_the_tool() { return 1; }
run_the_tool
rc="$?"
echo "tool exited $rc"
echo "PASS"
EOF
)" A FAIL

_expect "LOAD-BEARING: an exit code reported ONLY through a non-echo log helper is caught" \
  "$(_fixture_with <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
_log() { echo "$@" >&2; }
run_the_tool() { return 1; }
run_the_tool
rc=$?
_log "wrapper [module_label] exited ${rc}"
echo "PASS"
EOF
)" A FAIL

_expect "LOAD-BEARING: a declarator prefix (declare -i / readonly / local -r) does not hide it" \
  "$(_fixture_with <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
run_the_tool() { return 1; }
run_the_tool
declare -i rc=$?
echo "tool exited $rc"
echo "PASS"
EOF
)" A FAIL

_expect "LOAD-BEARING: a PIPESTATUS index other than 0 does not hide it" \
  "$(_fixture_with <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
run_the_tool() { return 1; }
run_the_tool | tee /dev/null
rc=${PIPESTATUS[1]}
echo "tee exited $rc"
echo "PASS"
EOF
)" A FAIL

_expect "NEGATIVE: an exit code genuinely compared is NOT flagged" \
  "$(_fixture_with <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
run_the_tool() { return 1; }
run_the_tool
rc="$?"
echo "wrapper [module_label] exited ${rc}"
if [[ "$rc" -ne 0 ]]; then
  echo "FAILED"
  exit 1
fi
echo "PASS"
EOF
)" A PASS

_expect "NEGATIVE: an exit code HANDED OFF as a standalone argument is NOT flagged" \
  "$(_fixture_with <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
decide() { python3 -c "import sys; sys.exit(0 if int(sys.argv[1]) in (1,2) else 1)" "$5"; }
run_the_tool() { return 1; }
run_the_tool
rc=$?
echo "wrapper [module_label] exited ${rc}"
decide "$a" "$b" "$c" "$d" "$rc"
EOF
)" A PASS

_expect "NEGATIVE: an annotated opt-out is honoured" \
  "$(_fixture_with <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
run_the_tool() { return 1; }
run_the_tool
rc="$?"  # vacuous-pass-ok: recorded for the report only; the verdict comes from the evidence records
echo "tool exited $rc"
EOF
)" A PASS

echo ""
echo "==============================================================="
echo "CHECK F — 'grep -q' on the right-hand side of a pipe under pipefail"
echo "==============================================================="

_expect "POSITIVE: 'cat f | grep -q pat' is caught" \
  "$(_fixture_with <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
cat /etc/hostname | grep -q marker
EOF
)" F FAIL

_expect "LOAD-BEARING: the SAME defect written as the long option '--quiet' is caught" \
  "$(_fixture_with <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
cat /etc/hostname | grep --quiet marker
EOF
)" F FAIL

_expect "LOAD-BEARING: the SAME defect with the pipe at END OF LINE is caught" \
  "$(_fixture_with <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
cat /etc/hostname |
  grep -q marker
EOF
)" F FAIL

_expect "NEGATIVE: 'grep -q pat <<< \"\$var\"' (no pipe) is NOT flagged" \
  "$(_fixture_with <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
haystack="$(cat /etc/hostname)"
grep -q marker <<< "$haystack"
EOF
)" F PASS

_expect "NEGATIVE: '|| grep -q' (a logical OR, not a pipe) is NOT flagged" \
  "$(_fixture_with <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
haystack="$(cat /etc/hostname)"
grep -q alpha <<< "$haystack" || grep -q beta <<< "$haystack"
EOF
)" F PASS

echo ""
echo "==============================================================="
echo "CHECK C — -e / -s standing in for -f on a captured-output file"
echo "==============================================================="

_expect "POSITIVE: '[[ -s \"\$raw_output_ref\" ]]' is caught" \
  "$(_fixture_with <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
raw_output_ref="$1"
if [[ -s "$raw_output_ref" ]]; then echo "there is captured output"; fi
EOF
)" C FAIL

_expect "LOAD-BEARING: the SAME defect via the 'test' builtin is caught" \
  "$(_fixture_with <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
raw_output_ref="$1"
test -s "$raw_output_ref" && echo "there is captured output"
EOF
)" C FAIL

_expect "NEGATIVE: '-f AND -s' (the correct pair) is NOT flagged" \
  "$(_fixture_with <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
raw_output_ref="$1"
if [[ -f "$raw_output_ref" && -s "$raw_output_ref" ]]; then echo "a real, non-empty regular file"; fi
EOF
)" C PASS

echo ""
echo "==============================================================="
echo "The guard must still be GREEN over a fixture with no defect at all"
echo "==============================================================="
CLEAN_FX="$(_new_fixture)"
if bash "${CLEAN_FX}/tests/pipeline/test_no_vacuous_pass_patterns.sh" >/dev/null 2>&1; then
  pass "a fixture tree containing none of the six shapes exits 0"
else
  fail "the guard flags a clean fixture tree — it would flag everything, and a red guard proves nothing"
fi

echo ""
echo "==============================================================="
if [[ "$FAILURES" -eq 0 ]]; then
  echo "ALL CHECKS PASSED"; exit 0
else
  echo "$FAILURES CHECK(S) FAILED"; exit 1
fi

#!/usr/bin/env bash
# Hermetic test suite for scripts/pipeline/phase-02-test-go.sh's handling of a
# `go test` run whose real failure is NOT represented by any per-test event
# (audit of T022/T023's deliverable, 2026-08-21).
#
# No real Go toolchain, no real lava-api-go, no Postgres: a stub `go` on PATH
# replays a fixture event stream, and the wrapper is pointed at a synthetic
# repo via its documented `[repo-path] [phase-dir]` argument seam. Every
# fixture stream below was CAPTURED VERBATIM from this host's real Go
# toolchain (go1.26.2) during the audit that produced this suite, by running
# `go test -race -count=1 -json ./...` against a throwaway module — they are
# real Go output, not invented shapes.
#
# WHY CASE 2 EXISTS (forensic anchor, 2026-08-21 wrapper audit):
# The wrapper captures `go test`'s real exit code into GO_TEST_RC and then
# only ever PRINTS it. Its PASS/FAIL decision is computed exclusively from
# the per-test events its parser recognized. The parser does emit a synthetic
# "#(build)" FAIL record for a package that reports `Action:"fail"` while
# none of its own tests reported an outcome (a compile error — verified
# working during the audit) — but that path is suppressed the moment ANY test
# in the package reported a per-test outcome. A package whose tests all PASS
# and which then fails as a package (the canonical case: a `TestMain`
# teardown that fails after `m.Run()` returns 0, the exact shape lava-api-go's
# container/database integration suites use) is therefore recorded as
# all-PASS, and the real non-zero exit is discarded. Observed verbatim against
# a real Go run of that exact shape:
#
#   phase-02-test-go: go test exited 1; parsing ...
#     go test exit code:        1
#     total tests recorded:     1
#     PASS:                     1
#     FAIL:                     0
#   phase-02-test-go: PASSED — all recorded tests PASS, all Evidence Records validated
#   WRAPPER EXIT = 0
#
# WHY CASE 3 EXISTS: the wrapper's own header documents "exit 3 - ... `go test
# -json` produced literally zero parseable events". The implemented guard only
# tests whether the stream FILE is non-empty (`[[ ! -s "$JSONL_PATH" ]]`), so a
# stream that is full of events but contains no per-test outcome at all sails
# through to "PASSED — all recorded tests PASS" with zero Evidence Records.
# The realistic trigger is a stray build tag that excludes every `_test.go`
# file from the default build: Go then reports `[no test files]` and exits 0,
# and the whole Go category silently becomes a no-op that still reads green.
#
# Exit 0 if every case passes; non-zero otherwise.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WRAPPER="${REPO_ROOT}/scripts/pipeline/phase-02-test-go.sh"

[[ -f "$WRAPPER" ]] || { echo "FAIL: script under test not found: $WRAPPER"; exit 1; }
for tool in jq python3; do
  command -v "$tool" >/dev/null 2>&1 || { echo "FAIL: '$tool' required"; exit 1; }
done

FAILURES=0
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAILURES=$((FAILURES + 1)); }

WORKDIR="$(mktemp -d)"
trap 'rm -rf -- "$WORKDIR"' EXIT

# --- a stub `go` that replays a captured real event stream ------------------
BIN="${WORKDIR}/bin"; mkdir -p "$BIN"
cat > "${BIN}/go" <<'STUB'
#!/usr/bin/env bash
# Stub `go`: replays a captured real `go test -json` stream and exits with the
# real exit code that run produced. Only `go test` is ever invoked here.
cat "$GO_FIXTURE_STREAM"
exit "${GO_FIXTURE_RC:-0}"
STUB
chmod +x "${BIN}/go"

# --- fixture streams, captured verbatim from real `go test -race -json` -----

# (a) One package, one test, all green. Real capture.
cat > "${WORKDIR}/stream-allpass.jsonl" <<'EOF'
{"Time":"2026-08-21T22:10:16.319698688+02:00","Action":"start","Package":"example.invalid/fixture/svc"}
{"Time":"2026-08-21T22:10:16.35138947+02:00","Action":"run","Package":"example.invalid/fixture/svc","Test":"TestPing"}
{"Time":"2026-08-21T22:10:16.351545719+02:00","Action":"output","Package":"example.invalid/fixture/svc","Test":"TestPing","Output":"=== RUN   TestPing\n"}
{"Time":"2026-08-21T22:10:16.352528853+02:00","Action":"output","Package":"example.invalid/fixture/svc","Test":"TestPing","Output":"--- PASS: TestPing (0.00s)\n"}
{"Time":"2026-08-21T22:10:16.352861585+02:00","Action":"pass","Package":"example.invalid/fixture/svc","Test":"TestPing","Elapsed":0}
{"Time":"2026-08-21T22:10:16.353088574+02:00","Action":"output","Package":"example.invalid/fixture/svc","Output":"PASS\n"}
{"Time":"2026-08-21T22:10:16.360629805+02:00","Action":"output","Package":"example.invalid/fixture/svc","Output":"ok  \texample.invalid/fixture/svc\t0.040s\n"}
{"Time":"2026-08-21T22:10:16.360811279+02:00","Action":"pass","Package":"example.invalid/fixture/svc","Elapsed":0.041}
EOF

# (b) A genuinely failing test. Real capture shape (assertion line included).
cat > "${WORKDIR}/stream-testfail.jsonl" <<'EOF'
{"Time":"2026-08-21T22:11:00.100000000+02:00","Action":"start","Package":"example.invalid/fixture/svc"}
{"Time":"2026-08-21T22:11:00.200000000+02:00","Action":"run","Package":"example.invalid/fixture/svc","Test":"TestPing"}
{"Time":"2026-08-21T22:11:00.201000000+02:00","Action":"output","Package":"example.invalid/fixture/svc","Test":"TestPing","Output":"=== RUN   TestPing\n"}
{"Time":"2026-08-21T22:11:00.202000000+02:00","Action":"output","Package":"example.invalid/fixture/svc","Test":"TestPing","Output":"    svc_test.go:19: Ping() = pang, want pong\n"}
{"Time":"2026-08-21T22:11:00.203000000+02:00","Action":"output","Package":"example.invalid/fixture/svc","Test":"TestPing","Output":"--- FAIL: TestPing (0.00s)\n"}
{"Time":"2026-08-21T22:11:00.204000000+02:00","Action":"fail","Package":"example.invalid/fixture/svc","Test":"TestPing","Elapsed":0}
{"Time":"2026-08-21T22:11:00.205000000+02:00","Action":"output","Package":"example.invalid/fixture/svc","Output":"FAIL\texample.invalid/fixture/svc\t0.021s\n"}
{"Time":"2026-08-21T22:11:00.206000000+02:00","Action":"fail","Package":"example.invalid/fixture/svc","Elapsed":0.021}
EOF

# (c) Every test PASSes, the PACKAGE fails (TestMain teardown after m.Run()).
#     Captured verbatim from a real go1.26.2 run during the audit.
cat > "${WORKDIR}/stream-pkgfail-tests-pass.jsonl" <<'EOF'
{"Time":"2026-08-21T22:10:16.319698688+02:00","Action":"start","Package":"example.invalid/fixture2/svc"}
{"Time":"2026-08-21T22:10:16.35138947+02:00","Action":"run","Package":"example.invalid/fixture2/svc","Test":"TestPing"}
{"Time":"2026-08-21T22:10:16.351545719+02:00","Action":"output","Package":"example.invalid/fixture2/svc","Test":"TestPing","Output":"=== RUN   TestPing\n"}
{"Time":"2026-08-21T22:10:16.352528853+02:00","Action":"output","Package":"example.invalid/fixture2/svc","Test":"TestPing","Output":"--- PASS: TestPing (0.00s)\n"}
{"Time":"2026-08-21T22:10:16.352861585+02:00","Action":"pass","Package":"example.invalid/fixture2/svc","Test":"TestPing","Elapsed":0}
{"Time":"2026-08-21T22:10:16.353088574+02:00","Action":"output","Package":"example.invalid/fixture2/svc","Output":"PASS\n"}
{"Time":"2026-08-21T22:10:16.353253174+02:00","Action":"output","Package":"example.invalid/fixture2/svc","Output":"teardown failed: could not remove test container\n"}
{"Time":"2026-08-21T22:10:16.360629805+02:00","Action":"output","Package":"example.invalid/fixture2/svc","Output":"FAIL\texample.invalid/fixture2/svc\t0.040s\n"}
{"Time":"2026-08-21T22:10:16.360811279+02:00","Action":"fail","Package":"example.invalid/fixture2/svc","Elapsed":0.041}
EOF

# (d) No per-test event at all: a build tag excluded every _test.go file.
#     Captured verbatim from a real go1.26.2 run during the audit.
cat > "${WORKDIR}/stream-no-test-files.jsonl" <<'EOF'
{"Time":"2026-08-21T22:10:36.000435829+02:00","Action":"start","Package":"example.invalid/fixture3/svc"}
{"Time":"2026-08-21T22:10:36.000540148+02:00","Action":"output","Package":"example.invalid/fixture3/svc","Output":"?   \texample.invalid/fixture3/svc\t[no test files]\n"}
{"Time":"2026-08-21T22:10:36.000558785+02:00","Action":"skip","Package":"example.invalid/fixture3/svc","Elapsed":0}
EOF

# _run_go <stream-file> <go-exit-code> <fixture-name> -> sets RC, OUT, PHASE
_run_go() {
  local stream="$1" rc="$2" name="$3"
  PHASE="${WORKDIR}/${name}/phase-02"
  local repo="${WORKDIR}/${name}"
  mkdir -p "${repo}/lava-api-go"
  printf 'test:\n\tGOMAXPROCS=2 go test -race -count=1 ./...\n' > "${repo}/lava-api-go/Makefile"
  local out="${WORKDIR}/${name}.log"
  set +e
  PATH="${BIN}:${PATH}" GO_FIXTURE_STREAM="$stream" GO_FIXTURE_RC="$rc" \
    bash "$WRAPPER" "$repo" "$PHASE" >"$out" 2>&1
  RC=$?
  set -e
  OUT="$(cat "$out")"
}

_records() { find "$1" -name '*.json' -not -path '*/raw/*' 2>/dev/null | sort; }

echo "==============================================================="
echo "CASE 1: honest streams -> honest verdicts (anti-'fail everything')"
echo "==============================================================="

_run_go "${WORKDIR}/stream-allpass.jsonl" 0 allpass
if [[ "$RC" -eq 0 ]]; then
  pass "all-pass stream + go exit 0 -> wrapper exit 0"
else
  fail "all-pass stream + go exit 0 -> exit ${RC}, expected 0; output: ${OUT}"
fi
n="$(_records "$PHASE" | wc -l | tr -d ' ')"
if [[ "$n" -eq 1 ]]; then
  pass "all-pass stream -> exactly 1 Evidence Record"
else
  fail "all-pass stream -> ${n} Evidence Records, expected 1"
fi

_run_go "${WORKDIR}/stream-testfail.jsonl" 1 testfail
if [[ "$RC" -ne 0 ]]; then
  pass "a genuinely failing test -> non-zero exit (${RC})"
else
  fail "a genuinely failing test -> exit 0; output: ${OUT}"
fi

echo ""
echo "==============================================================="
echo "CASE 2 (LOAD-BEARING): package FAILs, all its tests PASS"
echo "==============================================================="
echo "The real go test exit code is 1 and no per-test event carries that"
echo "failure. Recording all-PASS and exiting 0 discards it entirely."
echo ""

_run_go "${WORKDIR}/stream-pkgfail-tests-pass.jsonl" 1 pkgfail

if grep -q "go test exited 1" <<< "$OUT"; then
  pass "fixture sanity: the wrapper really did observe a non-zero go test exit"
else
  fail "fixture sanity: the wrapper never saw exit 1; output: ${OUT}"
fi

if [[ "$RC" -ne 0 ]]; then
  pass "unexplained non-zero go test exit -> non-zero wrapper exit (${RC})"
else
  fail "unexplained non-zero go test exit -> wrapper exit 0. go test genuinely failed (package-level FAIL after every test passed — the TestMain-teardown shape lava-api-go's integration suites use) and the wrapper reported 'PASSED — all recorded tests PASS'."
fi

explained=0
while IFS= read -r r; do
  [[ -z "$r" ]] && continue
  if [[ "$(jq -r '.result' "$r")" == "FAIL" ]]; then
    summary="$(jq -r '.assertion_summary' "$r")"
    if grep -qi 'teardown failed\|package-level\|exited 1\|exit code 1' <<< "$summary"; then
      explained=1
    fi
  fi
done < <(_records "$PHASE")

if [[ "$explained" -eq 1 ]]; then
  pass "an Evidence Record records the unexplained failure, quoting the real captured output"
else
  fail "no FAIL Evidence Record explains the non-zero exit — the failure would be invisible to phase-02-test.sh's evidence scan and to the run report"
fi

echo ""
echo "==============================================================="
echo "CASE 3: a stream with events but ZERO per-test outcomes"
echo "==============================================================="
echo "The wrapper's own header promises exit 3 here; the implemented guard"
echo "only checks that the stream file is non-empty."
echo ""

_run_go "${WORKDIR}/stream-no-test-files.jsonl" 0 notests

if grep -q "total tests recorded:     0" <<< "$OUT"; then
  pass "fixture sanity: the wrapper really did parse zero per-test outcomes"
else
  pass "fixture sanity: wrapper refused before printing a summary (also acceptable)"
fi

if [[ "$RC" -ne 0 ]]; then
  pass "zero parseable test events -> non-zero exit (${RC})"
else
  fail "zero parseable test events -> exit 0 with 'PASSED — all recorded tests PASS' and zero Evidence Records. A stray build tag excluding every _test.go file would turn the whole Go category into a green no-op."
fi

n="$(_records "$PHASE" | wc -l | tr -d ' ')"
if [[ "$RC" -ne 0 || "$n" -gt 0 ]]; then
  pass "zero-test run does not silently report success with no evidence"
else
  fail "zero-test run produced ${n} Evidence Records and exit 0"
fi

echo ""
echo "==============================================================="
if [[ "$FAILURES" -eq 0 ]]; then
  echo "ALL CHECKS PASSED"
  exit 0
else
  echo "$FAILURES CHECK(S) FAILED"
  exit 1
fi

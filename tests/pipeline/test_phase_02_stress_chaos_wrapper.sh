#!/usr/bin/env bash
# Hermetic test suite for scripts/pipeline/phase-02-test-stress-chaos.sh's
# handling of evidence it could not read or did not recognize (audit of T026's
# deliverable, 2026-08-21).
#
# No real Go harness, no podman, no Postgres: the wrapper is pointed at a
# synthetic repo via its documented `[repo-path] [phase-dir]` argument seam,
# and that repo's `scripts/run-chaos-stress.sh` is a stub that writes an
# evidence directory in exactly the shape the real harness writes
# (`lava-api-go/tests/stress/evidence/<UTC-ts>/stress-chaos.json`, a document
# with a `dimensions[]` array) and then exits with a chosen code. A stub `go`
# is put on PATH only to satisfy the wrapper's own tool precondition.
#
# WHY CASES 2 + 3 EXIST (forensic anchor, 2026-08-21 wrapper audit):
# The dimension loop is driven by `mapfile -t DIM_LINES < <(jq -c
# '.dimensions[]' ...)`. When that jq produces nothing — because the array is
# empty, or because the document is truncated and jq errors out — the loop
# body never executes, so TOTAL_RECORDED=0, FAIL_COUNT=0, and the wrapper
# prints its success line. Observed verbatim against a truncated
# stress-chaos.json whose own readable content said `"verdict":"FAIL"`:
#
#   phase-02-test-stress-chaos: run-chaos-stress.sh exited 0
#     dimensions recorded (Evidence Records): 0
#     PASS: 0 / FAIL: 0 / SKIPPED: 0
#   phase-02-test-stress-chaos: PASSED — all recorded dimensions PASS, all Evidence Records validated
#   WRAPPER EXIT = 0
#
# The wrapper's own header is emphatic that a missing evidence directory must
# be "never silently swallowed, and never a bluffed PASS" — an evidence
# directory that IS there but unreadable had no such guard.
#
# WHY CASE 4 EXISTS: RUN_RC is captured and only printed. The header itself
# states exit 0 means "every dimension that ran reported PASS AND no
# goroutine/FD leak" — i.e. the harness can fail for reasons that are not any
# one dimension's status. All-PASS dimensions + a non-zero harness exit was
# reported as PASSED, exit 0.
#
# WHY CASE 5 EXISTS: `ran` is read with `jq -r '.ran'` and compared to the
# string "true". A dimension object that does not carry that key at all
# yields "null", which is not "true", so it silently takes the OPERATOR_GATED
# path — a non-blocking SKIPPED. A harness-side field rename therefore
# converts real FAILs into honest-looking skips. Observed verbatim with
# `ran`/`status` renamed to `executed`/`result` on a genuinely failing
# dimension:
#
#   OPERATOR_GATED dimensions (ran=false; ...):
#     - S1 sustained: null — (no notes captured)
#   phase-02-test-stress-chaos: PASSED — all recorded dimensions PASS ...
#
# This is the same defect class as the `jq -r '.all_passed // "unknown"'` bug
# already fixed in phase-02-test-constitutional-gate-sweep.sh: a real,
# meaningful value that the parse does not recognize is quietly downgraded
# into a non-blocking one.
#
# Exit 0 if every case passes; non-zero otherwise.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WRAPPER="${REPO_ROOT}/scripts/pipeline/phase-02-test-stress-chaos.sh"

[[ -f "$WRAPPER" ]] || { echo "FAIL: script under test not found: $WRAPPER"; exit 1; }
command -v jq >/dev/null 2>&1 || { echo "FAIL: 'jq' required"; exit 1; }

FAILURES=0
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAILURES=$((FAILURES + 1)); }

WORKDIR="$(mktemp -d)"
trap 'rm -rf -- "$WORKDIR"' EXIT

BIN="${WORKDIR}/bin"; mkdir -p "$BIN"
printf '#!/usr/bin/env bash\nexit 0\n' > "${BIN}/go"   # tool-precondition stub only
chmod +x "${BIN}/go"

_new_repo() {
  local repo="${WORKDIR}/$1"
  mkdir -p "${repo}/scripts" "${repo}/lava-api-go/tests/stress"
  cat > "${repo}/scripts/run-chaos-stress.sh" <<'STUB'
#!/usr/bin/env bash
# Stub harness: writes one evidence dir in the real harness's shape, with
# fixture-chosen content, then exits with FIXTURE_RC.
set -u
d="lava-api-go/tests/stress/evidence/$(date -u +%Y-%m-%dT%H-%M-%S)-$$"
mkdir -p "$d"
case "${FIXTURE_SHAPE:-allpass}" in
  allpass)
    cat > "$d/stress-chaos.json" <<'JSON'
{"git_sha":"0f1e2d3","go_version":"go1.26.2","goos":"linux","verdict":"PASS",
 "dimensions":[{"id":"S1","name":"sustained","ran":true,"status":"PASS","requests":500,
 "status_2xx":500,"status_4xx":0,"status_5xx":0,"error_rate":0.0,
 "latency":{"p50_ms":1.2,"p95_ms":3.4,"p99_ms":5.6,"max_ms":9.9}}]}
JSON
    ;;
  onefail)
    cat > "$d/stress-chaos.json" <<'JSON'
{"git_sha":"0f1e2d3","go_version":"go1.26.2","goos":"linux","verdict":"FAIL",
 "dimensions":[{"id":"S1","name":"sustained","ran":true,"status":"FAIL","requests":500,
 "status_2xx":100,"status_4xx":0,"status_5xx":400,"error_rate":0.8,
 "latency":{"p50_ms":1.2,"p95_ms":3.4,"p99_ms":5.6,"max_ms":9.9}}]}
JSON
    ;;
  gated)
    cat > "$d/stress-chaos.json" <<'JSON'
{"git_sha":"0f1e2d3","go_version":"go1.26.2","goos":"linux","verdict":"PASS",
 "dimensions":[{"id":"S1","name":"sustained","ran":true,"status":"PASS","requests":500,
 "status_2xx":500,"status_4xx":0,"status_5xx":0,"error_rate":0.0,
 "latency":{"p50_ms":1.2,"p95_ms":3.4,"p99_ms":5.6,"max_ms":9.9}},
 {"id":"C3","name":"dependency-kill-postgres","ran":false,"status":"OPERATOR_GATED",
 "notes":"needs a real Postgres under podman plus the stresspg driver"}]}
JSON
    ;;
  emptydims)
    printf '{"git_sha":"0f1e2d3","verdict":"PASS","dimensions":[]}\n' > "$d/stress-chaos.json"
    ;;
  malformed)
    printf '{"git_sha":"0f1e2d3","verdict":"FAIL","dimensions":[{"id":"S1","status":"FAIL"' \
      > "$d/stress-chaos.json"
    ;;
  schemachange)
    cat > "$d/stress-chaos.json" <<'JSON'
{"git_sha":"0f1e2d3","verdict":"FAIL",
 "dimensions":[{"id":"S1","name":"sustained","executed":true,"result":"FAIL","requests":500}]}
JSON
    ;;
esac
echo "chaos harness stub: shape=${FIXTURE_SHAPE:-allpass} rc=${FIXTURE_RC:-0}"
exit "${FIXTURE_RC:-0}"
STUB
  chmod +x "${repo}/scripts/run-chaos-stress.sh"
  printf '%s' "$repo"
}

# _run_sc <repo> <shape> <rc> -> sets RC, OUT, PHASE
_run_sc() {
  local repo="$1" shape="$2" rc="$3"
  PHASE="${repo}/phase-02"
  rm -rf "$PHASE" "${repo}/lava-api-go/tests/stress/evidence"
  local out="${WORKDIR}/sc-out.log"
  set +e
  PATH="${BIN}:${PATH}" FIXTURE_SHAPE="$shape" FIXTURE_RC="$rc" \
    bash "$WRAPPER" "$repo" "$PHASE" >"$out" 2>&1
  RC=$?
  set -e
  OUT="$(cat "$out")"
}

_records() { find "$1" -name '*.json' -not -path '*/raw/*' 2>/dev/null | sort; }

REPO="$(_new_repo fixture)"

echo "==============================================================="
echo "CASE 1: real dimensions -> honest verdicts (anti-'fail everything')"
echo "==============================================================="

_run_sc "$REPO" allpass 0
if [[ "$RC" -eq 0 ]]; then
  pass "all dimensions PASS + harness exit 0 -> wrapper exit 0"
else
  fail "all dimensions PASS + harness exit 0 -> exit ${RC}; output: ${OUT}"
fi
n="$(_records "$PHASE" | wc -l | tr -d ' ')"
if [[ "$n" -eq 1 ]]; then
  pass "one PASS dimension -> exactly 1 Evidence Record"
else
  fail "one PASS dimension -> ${n} Evidence Records, expected 1"
fi

_run_sc "$REPO" onefail 1
if [[ "$RC" -ne 0 ]]; then
  pass "a genuinely FAILing dimension -> non-zero exit (${RC})"
else
  fail "a genuinely FAILing dimension -> exit 0; output: ${OUT}"
fi

_run_sc "$REPO" gated 0
if [[ "$RC" -eq 0 ]]; then
  pass "a real ran=false OPERATOR_GATED dimension is a skip, not a failure -> exit 0"
else
  fail "an honest OPERATOR_GATED dimension -> exit ${RC}, expected 0; output: ${OUT}"
fi
gated_skip=0
while IFS= read -r r; do
  [[ -z "$r" ]] && continue
  if [[ "$(jq -r '.result' "$r")" == "SKIPPED" ]] \
     && grep -q 'OPERATOR_GATED' <<< "$(jq -r '.assertion_summary' "$r")"; then
    gated_skip=1
  fi
done < <(_records "$PHASE")
if [[ "$gated_skip" -eq 1 ]]; then
  pass "the OPERATOR_GATED dimension produced a SKIPPED record quoting its real status"
else
  fail "no SKIPPED record quoting OPERATOR_GATED was written"
fi

echo ""
echo "==============================================================="
echo "CASE 2 (LOAD-BEARING): evidence JSON with an EMPTY dimensions[]"
echo "==============================================================="

_run_sc "$REPO" emptydims 0
if [[ "$RC" -ne 0 ]]; then
  pass "zero dimensions -> non-zero exit (${RC})"
else
  fail "zero dimensions -> exit 0 with 'PASSED — all recorded dimensions PASS' and zero Evidence Records. The stress-chaos category proved nothing and reported success."
fi

echo ""
echo "==============================================================="
echo "CASE 3 (LOAD-BEARING): truncated / unreadable evidence JSON"
echo "==============================================================="

_run_sc "$REPO" malformed 0
if [[ "$RC" -ne 0 ]]; then
  pass "unreadable evidence JSON -> non-zero exit (${RC})"
else
  fail "unreadable evidence JSON -> exit 0 reporting PASSED, even though the readable part of that very file says \"verdict\":\"FAIL\"."
fi
explained=0
while IFS= read -r r; do
  [[ -z "$r" ]] && continue
  [[ "$(jq -r '.result' "$r")" == "FAIL" ]] || continue
  if grep -qi 'could not be parsed\|unparseable\|invalid json\|malformed' <<< "$(jq -r '.assertion_summary' "$r")"; then
    explained=1
    if [[ "$(jq -r '.anti_bluff_status' "$r")" == "validated" ]]; then
      pass "the unreadable-evidence record survives anti-bluff validation"
    else
      fail "the unreadable-evidence record was rejected: $(jq -r '.anti_bluff_status' "$r")"
    fi
  fi
done < <(_records "$PHASE")
if [[ "$explained" -eq 1 ]]; then
  pass "a FAIL Evidence Record says the harness's own evidence could not be read"
else
  fail "no FAIL Evidence Record records that the evidence JSON was unreadable"
fi

echo ""
echo "==============================================================="
echo "CASE 4 (LOAD-BEARING): harness exits non-zero, every dimension PASSes"
echo "==============================================================="

_run_sc "$REPO" allpass 1
if grep -q "run-chaos-stress.sh exited 1" <<< "$OUT"; then
  pass "fixture sanity: the wrapper really did observe a non-zero harness exit"
else
  fail "fixture sanity: the wrapper never saw a non-zero harness exit; output: ${OUT}"
fi
if [[ "$RC" -ne 0 ]]; then
  pass "unexplained non-zero harness exit -> non-zero exit (${RC})"
else
  fail "unexplained non-zero harness exit -> exit 0. The wrapper's own header says harness exit 0 additionally means 'no goroutine/FD leak', so a non-zero exit with all-PASS dimensions is a real failure it discarded."
fi

echo ""
echo "==============================================================="
echo "CASE 5: a dimension whose shape the parse does not recognize"
echo "==============================================================="

_run_sc "$REPO" schemachange 1
if [[ "$RC" -ne 0 ]]; then
  pass "unrecognized dimension shape -> non-zero exit (${RC})"
else
  fail "unrecognized dimension shape -> exit 0. A renamed harness field turned a genuinely FAILing dimension into a non-blocking SKIPPED ('S1 sustained: null'), the same downgrade class as the '.all_passed // \"unknown\"' bug."
fi
downgraded=0
while IFS= read -r r; do
  [[ -z "$r" ]] && continue
  if [[ "$(jq -r '.result' "$r")" == "SKIPPED" ]] \
     && grep -q 'is null by the harness' <<< "$(jq -r '.assertion_summary' "$r")"; then
    downgraded=1
  fi
done < <(_records "$PHASE")
if [[ "$downgraded" -eq 0 ]]; then
  pass "the unrecognized dimension was NOT recorded as an honest-looking operator-gated skip"
else
  fail "the unrecognized dimension was recorded as SKIPPED with a fabricated 'gated by the harness's own real precondition check' explanation"
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

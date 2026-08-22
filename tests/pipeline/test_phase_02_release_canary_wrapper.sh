#!/usr/bin/env bash
# Hermetic test suite for scripts/pipeline/phase-02-test-release-canary.sh's
# exit-code classification (audit of T027's deliverable, 2026-08-21).
#
# No Genymotion VM, no adb, no real APK: the wrapper is driven through its
# documented `<apk-path> <package-id> [repo-path] [phase-dir]` argument seam
# against a synthetic repo whose `scripts/run-release-canary.sh` is a stub
# that reproduces each of that script's own documented outcomes, plus the
# undocumented ones a `set -euo pipefail` script actually produces when it
# dies partway through.
#
# WHY CASE 2 EXISTS (forensic anchor, 2026-08-21 wrapper audit):
# run-release-canary.sh's own contract is "Exit: 0 cold-start survived (PASS);
# 1 crash/fatal observed (FAIL); 2 config error" — and every one of its own
# `exit 2` sites is a real config error (missing --apk/--package, APK not
# found, no running Genymotion VM serial). But the wrapper's classification
# is `0 -> PASS`, `1 -> FAIL`, *everything else* -> SKIPPED, and it writes
# that SKIPPED record with a summary asserting a characterization it has no
# basis for. Because run-release-canary.sh runs under `set -euo pipefail`,
# any command that dies inside it propagates ITS OWN status: 127 for a
# missing `adb`/`gmtool`, 126 for a non-executable one, 137 for an
# OOM/SIGKILL. Observed verbatim:
#
#   phase-02-test-release-canary: run-release-canary.sh exit code = 127
#   ... assertion_summary: Genuinely did not execute: run-release-canary.sh
#       exit 127 (real host/config precondition gap, not a feature defect) —
#       real diagnostic: "...: line 71: adb: command not found"
#     result:       SKIPPED
#   WRAPPER EXIT = 0
#
#   phase-02-test-release-canary: run-release-canary.sh exit code = 137
#   ... (real host/config precondition gap, not a feature defect) ...
#     result:       SKIPPED
#   WRAPPER EXIT = 0
#
# SKIPPED does not block the phase (by design — an honest non-execution is not
# a pipeline failure), so a canary harness that crashed, was killed, or could
# not find its tools silences the §6.Z cold-start gate while the record claims
# to know it was "not a feature defect". This is the load-bearing gate that
# exists because 1.2.19-1039 shipped an APK that crashed on every cold launch.
#
# Exit 0 if every case passes; non-zero otherwise.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WRAPPER="${REPO_ROOT}/scripts/pipeline/phase-02-test-release-canary.sh"

[[ -f "$WRAPPER" ]] || { echo "FAIL: script under test not found: $WRAPPER"; exit 1; }
command -v jq >/dev/null 2>&1 || { echo "FAIL: 'jq' required"; exit 1; }

FAILURES=0
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAILURES=$((FAILURES + 1)); }

WORKDIR="$(mktemp -d)"
trap 'rm -rf -- "$WORKDIR"' EXIT

REPO="${WORKDIR}/repo"
mkdir -p "${REPO}/scripts" "${REPO}/releases"
printf 'not-a-real-apk\n' > "${REPO}/releases/app-release.apk"

cat > "${REPO}/scripts/run-release-canary.sh" <<'STUB'
#!/usr/bin/env bash
# Stub canary: reproduces run-release-canary.sh's own documented outcomes
# (0 PASS / 1 FAIL / 2 config error) plus the exit codes a `set -euo pipefail`
# script really produces when it dies partway through.
set -uo pipefail
EVID=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --evidence-dir) EVID="$2"; shift 2 ;;
    *) shift ;;
  esac
done
mkdir -p "$EVID"
case "${FIXTURE_CASE:-pass}" in
  pass)
    printf 'pid=4711 activity=digital.vasic.lava.client/.MainActivity resumed=true fatal_lines=0\n' > "$EVID/verdict.txt"
    echo "RELEASE CANARY PASS: cold start survived the 25s watch window"
    exit 0 ;;
  crash)
    printf 'pid=4711 resumed=false fatal_lines=1 FATAL EXCEPTION: main\n' > "$EVID/verdict.txt"
    echo "RELEASE CANARY FAIL: FATAL EXCEPTION observed during cold start"
    exit 1 ;;
  novm)
    echo "ERROR: no running Genymotion VM serial." >&2
    exit 2 ;;
  toolmissing)
    echo "run-release-canary.sh: line 71: adb: command not found" >&2
    exit 127 ;;
  killed)
    echo "harness killed mid-run" >&2
    exit 137 ;;
esac
STUB
chmod +x "${REPO}/scripts/run-release-canary.sh"

# _run_canary <fixture-case> -> sets RC, OUT, PHASE
_run_canary() {
  PHASE="${WORKDIR}/phase-02-$1"
  local out="${WORKDIR}/canary-$1.log"
  set +e
  FIXTURE_CASE="$1" bash "$WRAPPER" \
    "${REPO}/releases/app-release.apk" "digital.vasic.lava.client" "$REPO" "$PHASE" >"$out" 2>&1
  RC=$?
  set -e
  OUT="$(cat "$out")"
  RECORD="$(find "$PHASE" -name '*.json' -not -path '*/raw/*' 2>/dev/null | head -1)"
}

echo "==============================================================="
echo "CASE 1: the canary's own documented outcomes (0 / 1 / 2)"
echo "==============================================================="

_run_canary pass
if [[ "$RC" -eq 0 && "$(jq -r '.result' "$RECORD")" == "PASS" ]]; then
  pass "canary exit 0 -> PASS record, wrapper exit 0"
else
  fail "canary exit 0 -> exit ${RC}, result $(jq -r '.result' "$RECORD" 2>/dev/null); output: ${OUT}"
fi

_run_canary crash
if [[ "$RC" -ne 0 && "$(jq -r '.result' "$RECORD")" == "FAIL" ]]; then
  pass "canary exit 1 (real cold-start crash) -> FAIL record, non-zero exit (${RC})"
else
  fail "canary exit 1 -> exit ${RC}, result $(jq -r '.result' "$RECORD" 2>/dev/null); output: ${OUT}"
fi

_run_canary novm
if [[ "$RC" -eq 0 && "$(jq -r '.result' "$RECORD")" == "SKIPPED" ]]; then
  pass "canary exit 2 (its own documented config error) -> SKIPPED record, wrapper exit 0"
else
  fail "canary exit 2 -> exit ${RC}, result $(jq -r '.result' "$RECORD" 2>/dev/null). Exit 2 is the canary's own documented 'config error' and IS an honest non-execution; forcing it to FAIL would misreport a missing host precondition as an artifact defect."
fi
if grep -q 'no running Genymotion VM serial' <<< "$(jq -r '.assertion_summary' "$RECORD")"; then
  pass "the SKIPPED record quotes the canary's own real diagnostic"
else
  fail "the SKIPPED record does not quote the real diagnostic: $(jq -r '.assertion_summary' "$RECORD")"
fi

echo ""
echo "==============================================================="
echo "CASE 2 (LOAD-BEARING): exit codes the canary does NOT document"
echo "==============================================================="
echo "run-release-canary.sh runs under 'set -euo pipefail', so a dead tool"
echo "or a killed process propagates 127 / 126 / 137 — none of which is the"
echo "documented config-error code 2."
echo ""

for c in toolmissing killed; do
  _run_canary "$c"
  code=$( [[ "$c" == "toolmissing" ]] && echo 127 || echo 137 )
  result="$(jq -r '.result' "$RECORD" 2>/dev/null)"
  summary="$(jq -r '.assertion_summary' "$RECORD" 2>/dev/null)"

  if grep -q "exit code = ${code}" <<< "$OUT"; then
    pass "fixture sanity (${c}): the wrapper really did observe exit ${code}"
  else
    fail "fixture sanity (${c}): expected 'exit code = ${code}'; output: ${OUT}"
  fi

  if [[ "$RC" -ne 0 ]]; then
    pass "undocumented canary exit ${code} -> non-zero wrapper exit (${RC})"
  else
    fail "undocumented canary exit ${code} -> wrapper exit 0 with a non-blocking SKIPPED record. The §6.Z cold-start gate went silent because its harness died, and the record claimed to know the cause was a host/config gap."
  fi

  if [[ "$result" == "SKIPPED" ]] && grep -q 'not a feature defect' <<< "$summary"; then
    fail "the record for exit ${code} asserts '(real host/config precondition gap, not a feature defect)' — a characterization the wrapper cannot support for an exit code the canary never documents"
  else
    pass "the record for exit ${code} does not claim an unsupported host/config-gap characterization"
  fi

  if grep -qE 'command not found|harness killed' <<< "$summary"; then
    pass "the record for exit ${code} still quotes the canary's own real diagnostic"
  else
    fail "the record for exit ${code} lost the real diagnostic: ${summary}"
  fi
done

echo ""
echo "==============================================================="
if [[ "$FAILURES" -eq 0 ]]; then
  echo "ALL CHECKS PASSED"
  exit 0
else
  echo "$FAILURES CHECK(S) FAILED"
  exit 1
fi

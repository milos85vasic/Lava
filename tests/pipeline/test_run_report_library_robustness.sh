#!/usr/bin/env bash
# Hermetic test: the run-report library must not turn "I could not tell" into
# "nothing was wrong".
#
# FORENSIC ANCHOR (2026-08-25, independent audit of the orchestrator's own code):
# two places in scripts/pipeline/lib/run-report.sh read something, fail to
# interpret it, and continue as though the reading had come back clean. Both are
# the parse-with-no-match shape this feature has already been bitten by
# repeatedly: the failure of a MEASUREMENT is reported as the ABSENCE of a
# problem.
#
#   (1) append_interrupted_phase_if_any. The EXISTENCE of the marker file is
#       what proves a phase was in flight; its contents only NAME that phase.
#       The function inverts that: any marker it cannot interpret — empty,
#       whitespace-only, unreadable, or carrying a name the schema's enum
#       rejects — takes the `return 0` path, which its caller reads as "this run
#       was not interrupted". Measured on all four: outcome PASS, phases[] a
#       truncated all-PASS list. That is the exact bluff the in-flight block was
#       written to close, reachable through the block's own error handling.
#
#   (2) recompute_evidence_summary. Its own comment states that "a record whose
#       `result` cannot be interpreted counts as FAILED, never silently
#       ignored". Under `set -e` — which this library's header explicitly tells
#       callers to run under — that is not what happens: the bare assignment
#       `result="$(jq ... )"` carries jq's non-zero status, so the function
#       ABORTS mid-scan on the first malformed record and never writes
#       evidence_summary at all. It stays at init's all-zeros, and
#       rejected_by_anti_bluff == 0 is precisely the condition finalize reads to
#       allow "PASS". The orchestrator happens to call it on the left of `||`,
#       where `set -e` is suppressed, so today the defect is masked rather than
#       absent — a library whose documented calling convention breaks it is one
#       caller away from live.
#
# Exit 0 if every case passes; non-zero otherwise.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LIB="${REPO_ROOT}/scripts/pipeline/lib/run-report.sh"
ORCH="${REPO_ROOT}/scripts/pipeline-build-test-distribute.sh"
[[ -f "$LIB" ]] || { echo "FAIL: library not found: $LIB"; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "FAIL: python3 required"; exit 1; }

FAILURES=0
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAILURES=$((FAILURES + 1)); }

WORKDIR="$(mktemp -d)"
trap 'chmod -R u+rwX -- "$WORKDIR" 2>/dev/null; rm -rf -- "$WORKDIR"' EXIT
SHA="0123456789abcdef0123456789abcdef01234567"
RID="2026-08-25T09-00-00Z"

# _fresh_run <setup-shell> — a pristine cwd holding a run with one PASS phase,
# then <setup-shell> (which may create/mangle the marker via $M). Prints the cwd.
_fresh_run() {
  local cwd; cwd="$(mktemp -d "${WORKDIR}/run.XXXXXX")"
  ( cd "$cwd"
    # shellcheck source=scripts/pipeline/lib/run-report.sh
    source "$LIB"
    init_run_report "$RID" "$SHA" >/dev/null
    append_phase_result "$RID" "precondition" "PASS" 0 "d" >/dev/null
    M=".lava-ci-evidence/pipeline-runs/${RID}/.phase-in-flight"
    eval "$1"
  )
  printf '%s' "$cwd"
}

# _close <cwd> [fallback] — run the close path exactly as the orchestrator does.
# Prints "<rc>|<outcome>|<phases>".
_close() {
  local cwd="$1" fallback="${2-}"
  ( cd "$cwd"
    # shellcheck source=scripts/pipeline/lib/run-report.sh
    source "$LIB"
    set +e
    if [[ "$#" -ge 1 ]]; then :; fi
    if [[ -n "$fallback" ]]; then
      append_interrupted_phase_if_any "$RID" "$fallback" >/dev/null 2>&1
    else
      append_interrupted_phase_if_any "$RID" >/dev/null 2>&1
    fi
    rc=$?
    recompute_evidence_summary "$RID" >/dev/null 2>&1
    finalize_run_report "$RID" >/dev/null 2>&1
    set -e
    python3 -c '
import json, sys
r = json.load(open(".lava-ci-evidence/pipeline-runs/" + sys.argv[1] + "/report.json"))
print("%s|%s|%s" % (sys.argv[2], r["outcome"], [(p["name"], p["result"]) for p in r["phases"]]))
' "$RID" "$rc"
  )
}

echo "==============================================================="
echo "PART 1 — a marker the function cannot interpret is still a marker"
echo "==============================================================="
echo "The marker's EXISTENCE proves a phase was in flight. Only its CONTENT"
echo "names which. A run must never finalize PASS because the name was lost."
echo ""

# POSITIVE first, so a blanket 'always report an interruption' change is caught.
res="$(_close "$(_fresh_run ':')")"
if [[ "$res" == "0|PASS|"* ]]; then
  pass "POSITIVE: no marker at all — rc 0, run still finalizes PASS (${res})"
else
  fail "POSITIVE: a run with no marker did not finalize PASS: ${res}"
fi

res="$(_close "$(_fresh_run 'printf "build\n" > "$M"')")"
if [[ "$res" == "0|FAIL|"*"('build', 'FAIL')"* ]]; then
  pass "POSITIVE: a well-formed marker names the phase and yields FAIL (${res})"
else
  fail "POSITIVE: a well-formed 'build' marker did not yield build/FAIL: ${res}"
fi

# LOAD-BEARING: each of these leaves a marker on disk that cannot be interpreted.
for probe in \
  'EMPTY:: > "$M"' \
  'WHITESPACE-ONLY:printf "  \n\n" > "$M"' \
  'NAME-NOT-IN-ENUM:printf "buildX\n" > "$M"' \
  'TWO-NAMES:printf "build\ntest\n" > "$M"' \
  'UNREADABLE:printf "build\n" > "$M"; chmod 000 "$M"' \
  ; do
  label="${probe%%:*}"; setup="${probe#*:}"
  res="$(_close "$(_fresh_run "$setup")")"
  rc="${res%%|*}"
  if [[ "$rc" != "0" ]]; then
    pass "LOAD-BEARING ${label}: a present-but-unusable marker returns non-zero (${res})"
  else
    fail "LOAD-BEARING ${label}: returned 0 — the caller reads that as 'this run was not interrupted', and the report says: ${res}"
  fi
done

echo ""
echo "The orchestrator knows which phase it is in, so it can supply the name"
echo "the marker lost. With that fallback, an unusable marker must still FAIL."
echo ""
for probe in \
  'EMPTY:: > "$M"' \
  'NAME-NOT-IN-ENUM:printf "buildX\n" > "$M"' \
  'UNREADABLE:printf "build\n" > "$M"; chmod 000 "$M"' \
  ; do
  label="${probe%%:*}"; setup="${probe#*:}"
  res="$(_close "$(_fresh_run "$setup")" "build")"
  if [[ "$res" == "0|FAIL|"*"('build', 'FAIL')"* ]]; then
    pass "LOAD-BEARING ${label} + fallback: recorded as build/FAIL (${res})"
  else
    fail "LOAD-BEARING ${label} + fallback: expected build/FAIL and outcome FAIL, got ${res}"
  fi
done

# NEGATIVE: a fallback must never manufacture an interruption out of nothing.
res="$(_close "$(_fresh_run ':')" "build")"
if [[ "$res" == "0|PASS|"* ]]; then
  pass "NEGATIVE: a fallback name with NO marker present invents nothing (${res})"
else
  fail "NEGATIVE: supplying a fallback fabricated an interruption on a completed run: ${res}"
fi

echo ""
echo "==============================================================="
echo "PART 2 — recompute_evidence_summary under the library's own"
echo "documented calling convention (set -euo pipefail)"
echo "==============================================================="

_recompute_under_set_e() {
  local cwd="$1"
  # A separate process, so `set -e` is genuinely in force: calling the function
  # from the left of `||` inside THIS shell would suppress -e and hide the bug.
  set +e
  bash -c '
set -euo pipefail
source "$1"
cd "$2"
recompute_evidence_summary "$3" >/dev/null
' _ "$LIB" "$cwd" "$RID" >/dev/null 2>&1
  local rc=$?
  set -e
  printf '%s' "$rc"
}

_summary() {
  python3 -c '
import json, sys
s = json.load(open(sys.argv[1]))["evidence_summary"]
print("total=%d passed=%d failed=%d skipped=%d rejected=%d" % (
    s["total"], s["passed"], s["failed"], s["skipped"], s["rejected_by_anti_bluff"]))
' "$1"
}

# POSITIVE: well-formed records are counted correctly under set -e.
CWD_OK="$(_fresh_run ':')"
D_OK="${CWD_OK}/.lava-ci-evidence/pipeline-runs/${RID}"
mkdir -p "${D_OK}/phase-02/go-unit-integration"
printf '{"result":"PASS","anti_bluff_status":"VALIDATED"}'  > "${D_OK}/phase-02/go-unit-integration/a.json"
printf '{"result":"SKIPPED","anti_bluff_status":"VALIDATED"}' > "${D_OK}/phase-02/go-unit-integration/b.json"
printf '{"result":"PASS","anti_bluff_status":"REJECTED: no raw output"}' > "${D_OK}/phase-02/go-unit-integration/c.json"
rc="$(_recompute_under_set_e "$CWD_OK")"
got="$(_summary "${D_OK}/report.json")"
if [[ "$rc" == "0" && "$got" == "total=3 passed=2 failed=0 skipped=1 rejected=1" ]]; then
  pass "POSITIVE: valid records are counted correctly under set -e (${got})"
else
  fail "POSITIVE: rc=${rc} summary='${got}' — expected rc 0 and total=3 passed=2 failed=0 skipped=1 rejected=1"
fi

# LOAD-BEARING: an uninterpretable record must be COUNTED as failed, per the
# function's own documented rule — not abort the scan.
CWD_BAD="$(_fresh_run ':')"
D_BAD="${CWD_BAD}/.lava-ci-evidence/pipeline-runs/${RID}"
mkdir -p "${D_BAD}/phase-02/go-unit-integration"
printf '{ this is not json'                               > "${D_BAD}/phase-02/go-unit-integration/aaa-broken.json"
printf '{"result":"PASS","anti_bluff_status":"VALIDATED"}' > "${D_BAD}/phase-02/go-unit-integration/zzz-good.json"
rc="$(_recompute_under_set_e "$CWD_BAD")"
got="$(_summary "${D_BAD}/report.json")"
if [[ "$rc" == "0" ]]; then
  pass "LOAD-BEARING: recompute does not abort on a malformed record under set -e"
else
  fail "LOAD-BEARING: recompute exited ${rc} under set -e — it aborted mid-scan, so evidence_summary was never written and stays at init's all-zeros (rejected_by_anti_bluff == 0 is exactly what lets finalize say PASS). summary='${got}'"
fi
if [[ "$got" == "total=2 passed=1 failed=1 skipped=0 rejected=0" ]]; then
  pass "LOAD-BEARING: the malformed record is COUNTED AS FAILED, as documented (${got})"
else
  fail "LOAD-BEARING: summary is '${got}', expected total=2 passed=1 failed=1 skipped=0 rejected=0"
fi

# NEGATIVE: the selector must still ignore non-records at other depths.
CWD_SEL="$(_fresh_run ':')"
D_SEL="${CWD_SEL}/.lava-ci-evidence/pipeline-runs/${RID}"
mkdir -p "${D_SEL}/phase-02/go-unit-integration/raw" "${D_SEL}/phase-02/raw"
printf '{"result":"PASS","anti_bluff_status":"VALIDATED"}' > "${D_SEL}/phase-02/go-unit-integration/only-record.json"
printf '{"not":"a record"}' > "${D_SEL}/phase-02/depth-one.json"
printf '{"not":"a record"}' > "${D_SEL}/phase-02/go-unit-integration/raw/depth-three.json"
printf '{"not":"a record"}' > "${D_SEL}/phase-02/raw/companion.json"
rc="$(_recompute_under_set_e "$CWD_SEL")"
got="$(_summary "${D_SEL}/report.json")"
if [[ "$got" == "total=1 passed=1 failed=0 skipped=0 rejected=0" ]]; then
  pass "NEGATIVE: only the depth-2 record is counted; depth-1/3 companions are not (${got})"
else
  fail "NEGATIVE: selector counted the wrong set — '${got}', expected total=1 passed=1"
fi

echo ""
echo "==============================================================="
if [[ "$FAILURES" -eq 0 ]]; then
  echo "ALL CHECKS PASSED"; exit 0
else
  echo "$FAILURES CHECK(S) FAILED"; exit 1
fi

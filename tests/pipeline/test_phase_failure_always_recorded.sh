#!/usr/bin/env bash
# Hermetic end-to-end test: a run that FAILED must never leave a report.json
# saying "outcome": "PASS".
#
# FORENSIC ANCHOR (2026-08-25, independent audit of the orchestrator itself):
# the in-flight-marker work landed to close the TRUNCATED-PREFIX bluff — a run
# killed mid-phase whose phases[] was a short, perfectly valid all-PASS list. It
# closes that case for an INTERRUPTION. It does not close the same shape arising
# with no interruption at all:
#
#     $ pipeline-build-test-distribute.sh          # phase-02-test.sh exits 1
#     ...
#       outcome:     PASS
#       halted at:   test                          <- in the SAME summary box
#     $ jq -c '{outcome, phases:[.phases[].name]}' report.json
#     {"outcome":"PASS","phases":["precondition","build"]}
#
# The orchestrator's process exit code was honest (1). report.json — the
# artifact SC-008 tells an auditor to read FIRST — was not.
#
# ROOT CAUSE: the PHASES registry's third field, "self-appends-result", is
# TRUSTED and never VERIFIED. For a `yes` phase the orchestrator deliberately
# appends nothing, so that a phase script's own entry is not duplicated. But a
# script that dies BEFORE it reaches its own append_phase_result — an early
# `set -e` abort, a usage error, a missing dependency, a crash, a kill of the
# child alone — appends nothing either. Nobody then writes the phase's result,
# and finalize_run_report's rule is satisfied vacuously by the phases that DID
# report. The in-flight marker cannot cover it: the script RETURNED, so the
# marker was cleared by design, which is correct — the run was not interrupted,
# it genuinely failed.
#
# The worst variant is the multi-script phase, where the report is not even
# visibly truncated: live_verify's first script appends live_verify/PASS, the
# second dies before appending, and phases[] is a FULL-LENGTH all-PASS list
# indistinguishable from a completely successful run.
#
# THE FIX UNDER TEST: when a phase fails, the orchestrator VERIFIES that the
# report actually gained a non-PASS entry for that phase, and appends one itself
# when it did not. It states a fact the report was missing — the phase did not
# pass — rather than trusting a contract it can cheaply check.
#
# Exit 0 if every case passes; non-zero otherwise.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ORCH="${REPO_ROOT}/scripts/pipeline-build-test-distribute.sh"
LIBDIR="${REPO_ROOT}/scripts/pipeline/lib"
[[ -f "$ORCH" ]] || { echo "FAIL: orchestrator not found: $ORCH"; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "FAIL: python3 required"; exit 1; }

FAILURES=0
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAILURES=$((FAILURES + 1)); }

WORKDIR="$(mktemp -d)"
trap 'rm -rf -- "$WORKDIR"' EXIT

# ---------------------------------------------------------------------------
# Harness. A fixture tree holds the REAL orchestrator and the REAL run-report
# library, synthetic phase scripts whose behaviour each case chooses, and a
# `git` shim so no real repository, branch state or commit is involved.
# ---------------------------------------------------------------------------
HARNESS="${WORKDIR}/harness"
mkdir -p "${HARNESS}/scripts/pipeline/lib" "${HARNESS}/bin"
cp "$ORCH" "${HARNESS}/scripts/"
cp "${LIBDIR}/run-report.sh" "${LIBDIR}/evidence.sh" "${HARNESS}/scripts/pipeline/lib/"

cat > "${HARNESS}/bin/git" <<'GITEOF'
#!/usr/bin/env bash
# Minimal shim: the orchestrator asks for exactly these.
# A leading `-C <path>` is stripped before matching, exactly as real git treats
# it: a prefix that selects the repository, not part of the subcommand. Without
# this the shim matched only bare invocations, so `git -C "$REPO_ROOT" rev-parse
# HEAD` fell through to the catch-all and returned an EMPTY commit_sha, which
# init_run_report then rejects ("commit_sha '' is not a full 40-hex-char SHA").
# The orchestrator names the repository explicitly so that a report's commit_sha
# cannot silently describe whatever directory the process happened to be
# standing in -- the wrong-repo attribution defect covered by
# tests/pipeline/test_wrong_repo_attribution.sh. A stub that understands only
# one spelling of a call quietly dictates how production code may be written,
# which is the wrong way round.
if [[ "${1:-}" == "-C" ]]; then
  shift 2
fi
case "$*" in
  "rev-parse HEAD")            echo "0123456789abcdef0123456789abcdef01234567" ;;
  "rev-parse --show-toplevel") pwd ;;
  *)                           : ;;
esac
GITEOF
chmod +x "${HARNESS}/bin/git"

# _phase <basename> <body> — write a synthetic phase script.
_phase() {
  local name="$1"
  cat > "${HARNESS}/scripts/pipeline/${name}"
  chmod +x "${HARNESS}/scripts/pipeline/${name}"
}

# A phase script that appends its own PASS and succeeds (the normal contract).
_phase_ok() {
  local name="$1" phase="$2"
  _phase "$name" <<EOF
#!/usr/bin/env bash
set -euo pipefail
source "\$(dirname "\${BASH_SOURCE[0]}")/lib/run-report.sh"
append_phase_result "\$1" "${phase}" PASS 1 "d" >/dev/null
echo "${name}: ok"
EOF
}

# A phase script that dies BEFORE it can append anything.
_phase_dies_early() {
  local name="$1"
  _phase "$name" <<EOF
#!/usr/bin/env bash
set -euo pipefail
echo "${name}: dying before it could append its own result"
exit 1
EOF
}

# The distribute phase is a GATE: it appends nothing to report.json ever, and
# its exit 3 means "QUALIFIED, and there is no distribute step to run". That
# is the code the real scripts/pipeline/phase-05-distribute.sh returns on a
# run that gets that far, so it is what the happy path here uses. The
# orchestrator's handling of it is proved in
# tests/pipeline/test_pipeline_full_sequence_wiring.sh; this suite only needs
# the phase present so the preflight passes and the run reaches its own cases.
_phase_gate_qualified() {
  local name="$1"
  _phase "$name" <<EOF
#!/usr/bin/env bash
set -uo pipefail
echo "${name}: gate qualified; nothing to distribute"
exit 3
EOF
}

_reset_phases() {
  _phase phase-00-precondition.sh <<'EOF'
#!/usr/bin/env bash
echo "precondition: ok"
exit 0
EOF
  _phase_ok phase-01-build.sh build
  _phase_ok phase-02-test.sh test
  _phase_ok phase-03-install-boot.sh install_boot
  _phase_ok phase-04-live-verify-api.sh live_verify
  _phase_ok phase-04-live-verify-api-app.sh live_verify
  _phase_ok phase-05a-changelog-entry.sh changelog_entry
  _phase_gate_qualified phase-05-distribute.sh
  _phase_ok phase-06-docs.sh docs_refresh
}

RUN_EXIT=0; RUN_OUTCOME=""; RUN_PHASES=""
# _run [args...] — run the orchestrator in a pristine cwd; sets RUN_* globals.
_run() {
  local cwd; cwd="$(mktemp -d "${WORKDIR}/run.XXXXXX")"
  set +e
  ( cd "$cwd" && PATH="${HARNESS}/bin:$PATH" bash "${HARNESS}/scripts/pipeline-build-test-distribute.sh" "$@" ) \
    > "${cwd}/console.txt" 2>&1
  RUN_EXIT=$?
  set -e
  local rp
  rp="$(find "${cwd}/.lava-ci-evidence/pipeline-runs" -mindepth 2 -maxdepth 2 -name report.json 2>/dev/null | head -1)"
  if [[ -z "$rp" ]]; then RUN_OUTCOME="(no report)"; RUN_PHASES="[]"; return 0; fi
  RUN_OUTCOME="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["outcome"])' "$rp")"
  RUN_PHASES="$(python3 -c 'import json,sys; print([(p["name"],p["result"]) for p in json.load(open(sys.argv[1]))["phases"]])' "$rp")"
}

echo "==============================================================="
echo "CASE 1 (LOAD-BEARING): a single-script phase that dies before"
echo "appending must not leave report.json saying PASS"
echo "==============================================================="
_reset_phases
_phase_dies_early phase-02-test.sh
_run
if [[ "$RUN_EXIT" -ne 0 ]]; then
  pass "the orchestrator's own exit code is honest (${RUN_EXIT})"
else
  fail "orchestrator exited 0 for a run whose 'test' phase failed"
fi
if [[ "$RUN_OUTCOME" == "FAIL" ]]; then
  pass "report.json outcome is FAIL"
else
  fail "report.json outcome is '${RUN_OUTCOME}', expected FAIL — the run halted at 'test' and the report says success. phases=${RUN_PHASES}"
fi
if [[ "$RUN_PHASES" == *"('test', 'FAIL')"* ]]; then
  pass "the failed phase is named in phases[] as FAIL"
else
  fail "phases[] does not record test/FAIL: ${RUN_PHASES}"
fi

echo ""
echo "==============================================================="
echo "CASE 2 (LOAD-BEARING): a MULTI-SCRIPT phase whose second script"
echo "dies before appending — phases[] is full-length and all-PASS"
echo "==============================================================="
_reset_phases
_phase_dies_early phase-04-live-verify-api-app.sh
_run
if [[ "$RUN_EXIT" -ne 0 ]]; then
  pass "the orchestrator's own exit code is honest (${RUN_EXIT})"
else
  fail "orchestrator exited 0 for a run whose live_verify phase failed"
fi
if [[ "$RUN_OUTCOME" == "FAIL" ]]; then
  pass "report.json outcome is FAIL"
else
  fail "report.json outcome is '${RUN_OUTCOME}', expected FAIL — every phase name is present and all-PASS, so the report is indistinguishable from a fully successful run. phases=${RUN_PHASES}"
fi
if [[ "$RUN_PHASES" == *"('live_verify', 'FAIL')"* ]]; then
  pass "the failed phase is named in phases[] as FAIL"
else
  fail "phases[] does not record live_verify/FAIL: ${RUN_PHASES}"
fi

echo ""
echo "==============================================================="
echo "CASE 3 (POSITIVE): a run in which everything genuinely passes"
echo "must still be PASS, exit 0, and gain no FAIL entry"
echo "==============================================================="
_reset_phases
_run
if [[ "$RUN_EXIT" -eq 0 && "$RUN_OUTCOME" == "PASS" ]]; then
  pass "a genuinely successful run exits 0 with outcome PASS"
else
  fail "a successful run gave exit=${RUN_EXIT} outcome='${RUN_OUTCOME}' — the fix is failing runs that passed. phases=${RUN_PHASES}"
fi
if [[ "$RUN_PHASES" != *"'FAIL'"* ]]; then
  pass "no phantom FAIL entry was added to a successful run"
else
  fail "a successful run gained a FAIL entry: ${RUN_PHASES}"
fi

echo ""
echo "==============================================================="
echo "CASE 4 (POSITIVE, no double-recording): a phase that reports its"
echo "OWN failure must appear exactly once, not twice"
echo "==============================================================="
_reset_phases
_phase phase-02-test.sh <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/run-report.sh"
append_phase_result "$1" test FAIL 1 "d" >/dev/null
echo "test: failed, and said so itself"
exit 1
EOF
_run
if [[ "$RUN_OUTCOME" == "FAIL" ]]; then
  pass "a self-reported phase failure still yields outcome FAIL"
else
  fail "outcome is '${RUN_OUTCOME}', expected FAIL. phases=${RUN_PHASES}"
fi
_n_test_fail="$(grep -o "('test', 'FAIL')" <<< "$RUN_PHASES" | wc -l)"
if [[ "$_n_test_fail" -eq 1 ]]; then
  pass "the phase's own FAIL entry is not duplicated by the orchestrator"
else
  fail "test/FAIL appears ${_n_test_fail} time(s), expected exactly 1: ${RUN_PHASES}"
fi

echo ""
echo "==============================================================="
echo "CASE 5 (POSITIVE, unchanged contract): an FR-000 precondition"
echo "refusal keeps exit 2 and is recorded as precondition/FAIL"
echo "==============================================================="
_reset_phases
_phase phase-00-precondition.sh <<'EOF'
#!/usr/bin/env bash
echo "precondition: refusing — working tree is not clean"
exit 2
EOF
_run
if [[ "$RUN_EXIT" -eq 2 ]]; then
  pass "a precondition refusal still propagates exit 2 verbatim"
else
  fail "precondition refusal exited ${RUN_EXIT}, expected 2"
fi
if [[ "$RUN_OUTCOME" == "FAIL" && "$RUN_PHASES" == *"('precondition', 'FAIL')"* ]]; then
  pass "the refusal is recorded as precondition/FAIL with outcome FAIL"
else
  fail "outcome='${RUN_OUTCOME}' phases=${RUN_PHASES}"
fi

echo ""
echo "==============================================================="
echo "CASE 6 (LOAD-BEARING): a genuine INTERRUPT whose in-flight marker"
echo "cannot be interpreted must still not finalize to PASS"
echo "==============================================================="
echo "The library signals this by returning non-zero. _close_report used to"
echo "discard that with '|| true', which is indistinguishable from 'the run"
echo "was not interrupted' — the same swallow, one layer up."
echo ""
_reset_phases
# Corrupt this run's own marker, then TERM the orchestrator. bash defers the
# trap until the foreground phase pipeline finishes, so _close_report runs with
# the marker still present and unusable — the real interrupt path.
_phase phase-01-build.sh <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/run-report.sh"
append_phase_result "$1" build PASS 1 "d" >/dev/null
marker=".lava-ci-evidence/pipeline-runs/$1/.phase-in-flight"
[[ -f "$marker" ]] || { echo "build: no marker present — harness assumption broken"; exit 3; }
printf 'not-a-phase-name
' > "$marker"
kill -TERM "$PPID"
echo "build: TERM sent to the orchestrator"
EOF
_run
if [[ "$RUN_OUTCOME" != "PASS" ]]; then
  pass "an uninterpretable in-flight marker does not yield outcome PASS (outcome=${RUN_OUTCOME} phases=${RUN_PHASES})"
else
  fail "outcome is PASS — a run interrupted with an unusable marker reported success. phases=${RUN_PHASES}"
fi
if [[ "$RUN_EXIT" -ne 0 ]]; then
  pass "the orchestrator's exit code is non-zero for that run (${RUN_EXIT})"
else
  fail "the orchestrator exited 0 for an interrupted run"
fi

echo ""
echo "==============================================================="
if [[ "$FAILURES" -eq 0 ]]; then
  echo "ALL CHECKS PASSED"; exit 0
else
  echo "$FAILURES CHECK(S) FAILED"; exit 1
fi

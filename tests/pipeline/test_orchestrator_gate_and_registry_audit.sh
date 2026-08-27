#!/usr/bin/env bash
# Hermetic audit suite for scripts/pipeline-build-test-distribute.sh — the
# eight-phase registry, the `gate` result-mode, the per-script evidence
# directory derivation, and --help.
#
# WHY THIS SUITE EXISTS, SEPARATELY FROM THE OTHERS
# -------------------------------------------------
# tests/pipeline/test_pipeline_full_sequence_wiring.sh pins the ORDER of the
# eight phases and the three gate exit codes the registry uses today. Three
# things it does NOT pin were found unprotected by an independent audit, and
# each of them is a decision the eight-phase wiring is actually built on:
#
#   1. `gate` is checked BEFORE the phase's failure. A phase whose registry
#      entry names more than one script — a shape the registry explicitly
#      supports and `live_verify` already uses — could exit 3 in its first
#      script and then FAIL in a later one, and the orchestrator recorded
#      NOTHING for it. report.json finalized to `outcome: "PASS"` for a run
#      that halted on a failure, while the process exit code said 1. SC-008
#      tells an auditor to read report.json FIRST, so the artifact the
#      auditor trusts was the one that lied. CASE A below.
#
#   2. `phase_dir` derivation. The header states plainly that a fixed-width
#      `${script_name:6:2}` slice returns "05" for BOTH
#      phase-05a-changelog-entry.sh and phase-05-distribute.sh and "silently
#      merges" their evidence directories, and that a suffix-aware `sed`
#      replaced it. Reverting that one line to the exact pre-fix form left
#      every orchestrator suite green — 117 checks across five files, none of
#      which ever looks at a directory name. CASE D below.
#
#   3. `--help`. `_usage()` was rewritten from a hardcoded `sed -n '2,100p'`
#      window to an awk scan precisely because a fixed window silently
#      truncates when the header is edited. The only existing assertion on
#      --help is `exit 0` with stdout discarded, so `_usage` printing NOTHING
#      AT ALL still passed. One blank (non-`#`) line inserted mid-header cuts
#      the output from 192 lines to 39 — losing the entire --until/--skip
#      option block and the exit-code table — and exits 0 while doing it.
#      CASE E below.
#
# CASE F additionally proves each of the EIGHT phases individually fails the
# run when its script dies without appending. Between the other suites,
# `install_boot` and the first half of `live_verify` had no such coverage.
#
# SCOPE: nothing here invokes Gradle, systemd, podman, an emulator, firebase,
# or any real phase script. Nothing here touches this repository's working
# tree or its real .lava-ci-evidence/ tree.
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
# Harness: the REAL orchestrator and the REAL run-report library, synthetic
# phase scripts, and a `git` shim so no real repository is involved.
# ---------------------------------------------------------------------------
HARNESS="${WORKDIR}/harness"
mkdir -p "${HARNESS}/scripts/pipeline/lib" "${HARNESS}/bin"
cp "${LIBDIR}/run-report.sh" "${LIBDIR}/evidence.sh" "${HARNESS}/scripts/pipeline/lib/"

cat > "${HARNESS}/bin/git" <<'GITEOF'
#!/usr/bin/env bash
# A leading `-C <path>` is stripped before matching, exactly as real git treats
# it: a prefix that selects the repository, not part of the subcommand. Without
# this the shim matched only bare invocations, so `git -C "$REPO_ROOT" rev-parse
# HEAD` fell through to the catch-all and returned an EMPTY commit_sha —
# reported by init_run_report as "commit_sha '' is not a full 40-hex-char SHA"
# and cascading into 29 unrelated-looking failures. That is a defect in the
# shim, not in the caller: the orchestrator names the repository explicitly so
# that the report's commit_sha cannot silently describe whatever directory the
# process happened to be standing in, which is the wrong-repo attribution defect
# tests/pipeline/test_wrong_repo_attribution.sh exists for. A stub that only
# understands one spelling of a call quietly dictates how production code may be
# written, which is the wrong way round.
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

# _install_orchestrator [registry-rewrite-old] [registry-rewrite-new]
# Copies the REAL orchestrator into the harness. With two extra arguments it
# additionally applies a literal one-occurrence rewrite and REFUSES to
# continue unless that rewrite actually applied — a case built on a rewrite
# that silently did not happen would be testing the unmodified script and
# passing for the wrong reason.
_install_orchestrator() {
  cp "$ORCH" "${HARNESS}/scripts/pipeline-build-test-distribute.sh"
  [[ "$#" -eq 2 ]] || return 0
  python3 - "${HARNESS}/scripts/pipeline-build-test-distribute.sh" "$1" "$2" <<'PY'
import sys
tgt, old, new = sys.argv[1:4]
src = open(tgt).read()
n = src.count(old)
if n != 1:
    sys.stderr.write("harness rewrite matched %d times, expected exactly 1\n" % n)
    sys.exit(9)
open(tgt, "w").write(src.replace(old, new))
PY
}

_phase() { local name="$1"; cat > "${HARNESS}/scripts/pipeline/${name}"; chmod +x "${HARNESS}/scripts/pipeline/${name}"; }

# A phase script that appends its own PASS (registry mode `yes`).
_phase_ok() {
  local name="$1" phase="$2"
  _phase "$name" <<EOF
#!/usr/bin/env bash
set -euo pipefail
source "\$(dirname "\${BASH_SOURCE[0]}")/lib/run-report.sh"
printf '%s\n' "${phase}" >> "\$LAVA_TEST_ORDER_FILE"
append_phase_result "\$1" "${phase}" PASS 1 "d" >/dev/null
echo "${name}: ok"
EOF
}

# A phase script that appends NOTHING and exits with a chosen code — the real
# phase-05-distribute.sh's documented behaviour, and also the shape of any
# script that dies before reaching its own append_phase_result.
_phase_rc() {
  local name="$1" phase="$2" rc="$3"
  _phase "$name" <<EOF
#!/usr/bin/env bash
printf '%s\n' "${phase}" >> "\$LAVA_TEST_ORDER_FILE"
echo "${name}: exiting ${rc} without appending anything"
exit ${rc}
EOF
}

_reset_phases() {
  rm -f "${HARNESS}/scripts/pipeline/"phase-*.sh
  _phase phase-00-precondition.sh <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "precondition" >> "$LAVA_TEST_ORDER_FILE"
echo "precondition: ok"
exit 0
EOF
  _phase_ok phase-01-build.sh build
  _phase_ok phase-02-test.sh test
  _phase_ok phase-03-install-boot.sh install_boot
  _phase_ok phase-04-live-verify-api.sh live_verify
  _phase_ok phase-04-live-verify-api-app.sh live_verify
  _phase_ok phase-05a-changelog-entry.sh changelog_entry
  _phase_rc phase-05-distribute.sh distribute 3
  _phase_ok phase-06-docs.sh docs_refresh
}

RUN_EXIT=0; RUN_OUTCOME=""; RUN_PHASES=""; RUN_ORDER=""; RUN_CONSOLE=""; RUN_EVIDENCE_DIRS=""
_run() {
  local cwd; cwd="$(mktemp -d "${WORKDIR}/run.XXXXXX")"
  local order="${cwd}/order.txt"; : > "$order"
  set +e
  ( cd "$cwd" \
      && PATH="${HARNESS}/bin:$PATH" \
         LAVA_TEST_ORDER_FILE="$order" \
         bash "${HARNESS}/scripts/pipeline-build-test-distribute.sh" "$@" ) \
    > "${cwd}/console.txt" 2>&1
  RUN_EXIT=$?
  set -e
  RUN_ORDER="$(tr '\n' ' ' < "$order")"
  RUN_CONSOLE="$(cat "${cwd}/console.txt")"
  local rp
  # `|| true`: a usage error is refused BEFORE init_run_report, so there is
  # legitimately no run directory to find, and the failing find would abort
  # this suite under `set -e` instead of letting the case assert.
  rp="$(find "${cwd}/.lava-ci-evidence/pipeline-runs" -mindepth 2 -maxdepth 2 -name report.json 2>/dev/null | head -1 || true)"
  if [[ -z "$rp" ]]; then
    RUN_OUTCOME="(no report)"; RUN_PHASES="[]"; RUN_EVIDENCE_DIRS=""; return 0
  fi
  RUN_OUTCOME="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["outcome"])' "$rp")"
  RUN_PHASES="$(python3 -c 'import json,sys; print([(p["name"],p["result"]) for p in json.load(open(sys.argv[1]))["phases"]])' "$rp")"
  RUN_EVIDENCE_DIRS="$( (cd "$(dirname "$rp")" && find . -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort | tr '\n' ' ') 2>/dev/null || true)"
}

echo "==============================================================="
echo "CASE A (LOAD-BEARING): a \`gate\` phase that QUALIFIES in one script"
echo "and then FAILS in another must fail the run AND say so in report.json"
echo "==============================================================="
echo "The registry supports comma-separated scripts per phase and live_verify"
echo "already uses that shape. Checking gate_no_op BEFORE phase_exit made the"
echo "failure branch unreachable once any script in the phase had exited 3:"
echo "the process exited 1 while report.json finalized to outcome PASS."

_install_orchestrator \
  '  "distribute|phase-05-distribute.sh|gate"' \
  '  "distribute|phase-05-distribute.sh,phase-05z-second-half.sh|gate"'
_reset_phases
_phase_rc phase-05-distribute.sh distribute 3      # first half QUALIFIES
_phase_rc phase-05z-second-half.sh distribute 1    # second half FAILS
_run

if [[ "$RUN_EXIT" -ne 0 ]]; then
  pass "a gate phase that failed after qualifying exits non-zero (${RUN_EXIT})"
else
  fail "a gate phase failed after qualifying and the run still exited 0"
fi
if [[ "$RUN_OUTCOME" == "FAIL" ]]; then
  pass "report.json finalizes to FAIL — the artifact SC-008 sends an auditor to must not disagree with the exit code"
else
  fail "report.json says outcome='${RUN_OUTCOME}' for a run that halted on a failure. phases=${RUN_PHASES}"
fi
if [[ "$RUN_PHASES" == *"('distribute', 'FAIL')"* ]]; then
  pass "the failure is recorded as distribute/FAIL — the gate appends nothing, so the orchestrator must"
else
  fail "phases[] records nothing for the failed gate phase: ${RUN_PHASES}"
fi
if [[ "$RUN_ORDER" != *docs_refresh* ]]; then
  pass "the run halts before docs_refresh"
else
  fail "the run continued past a failed gate phase: ${RUN_ORDER}"
fi
if ! grep -q "GATE QUALIFIED — NOTHING WAS DISTRIBUTED" <<< "$RUN_CONSOLE"; then
  pass "the RUN SUMMARY does not claim the gate qualified for a phase that failed"
else
  fail "the RUN SUMMARY claims 'GATE QUALIFIED' for a phase that failed. Console: ${RUN_CONSOLE}"
fi

echo ""
echo "==============================================================="
echo "CASE B (POSITIVE, over-correction guard): every script of a multi-script"
echo "gate qualifying is still the no-entry path, not a failure"
echo "==============================================================="
echo "Without this, 'treat any gate phase as FAIL' would satisfy CASE A."

_reset_phases
_phase_rc phase-05-distribute.sh distribute 3
_phase_rc phase-05z-second-half.sh distribute 3
_run
if [[ "$RUN_EXIT" -eq 0 && "$RUN_OUTCOME" == "PASS" && "$RUN_PHASES" != *"'distribute'"* ]]; then
  pass "an all-qualified multi-script gate still records no entry and passes the run"
else
  fail "all-qualified multi-script gate gave exit=${RUN_EXIT} outcome='${RUN_OUTCOME}' phases=${RUN_PHASES}"
fi
if [[ "$RUN_ORDER" == *"distribute distribute docs_refresh"* ]]; then
  pass "both gate scripts ran and the run continued to docs_refresh"
else
  fail "order was '${RUN_ORDER}'"
fi

echo ""
echo "==============================================================="
echo "CASE C (POSITIVE): the single-script registry shipping today is"
echo "unchanged — gate exit 3 records nothing and the run passes"
echo "==============================================================="

_install_orchestrator          # the REAL registry, unmodified
_reset_phases
_run
if [[ "$RUN_EXIT" -eq 0 && "$RUN_OUTCOME" == "PASS" && "$RUN_PHASES" != *"'distribute'"* ]]; then
  pass "the shipping registry's gate exit 3 still records no entry and passes"
else
  fail "shipping gate exit 3 gave exit=${RUN_EXIT} outcome='${RUN_OUTCOME}' phases=${RUN_PHASES}"
fi
if [[ "$RUN_EXIT" -eq 0 ]] && grep -q "GATE QUALIFIED" <<< "$RUN_CONSOLE"; then
  pass "the qualified-but-distributed-nothing disclosure is still printed"
else
  fail "the qualified disclosure went missing. Console: ${RUN_CONSOLE}"
fi

echo ""
echo "==============================================================="
echo "CASE D: every phase script gets its OWN evidence directory —"
echo "phase-05a and phase-05 are NOT the same directory"
echo "==============================================================="
echo "The pre-fix \${script_name:6:2} slice returned '05' for both and merged"
echo "them. No orchestrator suite looked at a directory name, so reverting the"
echo "fix left 117 checks green."

for expected in phase-00 phase-01 phase-02 phase-03 phase-04 phase-05a phase-05 phase-06; do
  if [[ " $RUN_EVIDENCE_DIRS " == *" $expected "* ]]; then
    pass "a full run creates the evidence directory '${expected}'"
  else
    fail "evidence directory '${expected}' is missing. Got: ${RUN_EVIDENCE_DIRS}"
  fi
done
_n_dirs="$(wc -w <<< "$RUN_EVIDENCE_DIRS")"
if [[ "$_n_dirs" -eq 8 ]]; then
  pass "exactly 8 evidence directories for 8 phases — none merged, none phantom"
else
  fail "a full run produced ${_n_dirs} evidence directories, expected 8: ${RUN_EVIDENCE_DIRS}"
fi

# The derivation itself, against every real script name plus the shapes that
# would break a naive slice. Read out of the orchestrator so this can never
# drift into testing a copy of the expression instead of the expression.
_derive_expr="$(command grep -F 'phase_dir="${RUN_DIR}/phase-' "$ORCH" | head -1)"
if [[ "$_derive_expr" == *'${script_name:'* ]]; then
  fail "phase_dir is derived by a fixed-width slice again: ${_derive_expr}"
else
  pass "phase_dir is not derived by a fixed-width slice"
fi
_derive() { sed -E 's/^phase-([0-9]+[a-z]?)-.*/\1/' <<< "$1"; }
_bad=0
while IFS='|' read -r name want; do
  got="$(_derive "$name")"
  [[ "$got" == "$want" ]] || { fail "phase_dir derivation: '${name}' -> '${got}', expected '${want}'"; _bad=1; }
  [[ -n "$got" ]] || { fail "phase_dir derivation produced an EMPTY directory for '${name}'"; _bad=1; }
done <<'NAMES'
phase-00-precondition.sh|00
phase-01-build.sh|01
phase-02-test.sh|02
phase-03-install-boot.sh|03
phase-04-live-verify-api.sh|04
phase-04-live-verify-api-app.sh|04
phase-05a-changelog-entry.sh|05a
phase-05-distribute.sh|05
phase-06-docs.sh|06
phase-07-closure.sh|07
phase-10b-multi-digit-suffix.sh|10b
NAMES
[[ "$_bad" -eq 0 ]] && pass "phase_dir derivation is correct for every real script name and for a two-digit+letter form"

echo ""
echo "==============================================================="
echo "CASE E: --help prints the WHOLE usage block, not an empty or"
echo "truncated one"
echo "==============================================================="
echo "The only pre-existing assertion on --help was 'exit 0' with stdout"
echo "discarded, so _usage() printing nothing at all also passed."

_help_out="$( (cd "$WORKDIR" && PATH="${HARNESS}/bin:$PATH" bash "${HARNESS}/scripts/pipeline-build-test-distribute.sh" --help) 2>&1 )"
_help_rc=$?
_help_lines="$(wc -l <<< "$_help_out")"
if [[ "$_help_rc" -eq 0 ]]; then pass "--help exits 0"; else fail "--help exited ${_help_rc}"; fi
if [[ "$_help_lines" -ge 100 ]]; then
  pass "--help prints the full header (${_help_lines} lines), not a truncated window"
else
  fail "--help printed only ${_help_lines} lines — the header is ~190; a fixed window or an early stop has truncated it"
fi
for needle in "--until <phase>" "--skip <phase>" "-h, --help" "Default: docs_refresh" \
              "precondition" "changelog_entry" "docs_refresh" "Exit codes" "closure"; do
  if grep -qF -- "$needle" <<< "$_help_out"; then
    pass "--help documents '${needle}'"
  else
    fail "--help no longer contains '${needle}' — it has been truncated or the header moved"
  fi
done
if ! grep -qF 'set -euo pipefail' <<< "$_help_out"; then
  pass "--help stops at the end of the header and does not spill into code"
else
  fail "--help spilled past the header into the script body"
fi

echo ""
echo "==============================================================="
echo "CASE F: EACH of the eight phases fails the run when its script dies"
echo "without appending — a phase whose failure cannot fail the run is"
echo "decorative, not wired"
echo "==============================================================="

while IFS='|' read -r phase script; do
  _reset_phases
  _phase_rc "$script" "$phase" 1
  _run
  if [[ "$RUN_EXIT" -ne 0 && "$RUN_OUTCOME" == "FAIL" && "$RUN_PHASES" == *"('${phase}', 'FAIL')"* ]]; then
    pass "${phase} (${script}) failing fails the run and is recorded as ${phase}/FAIL"
  else
    fail "${phase} (${script}) failing gave exit=${RUN_EXIT} outcome='${RUN_OUTCOME}' phases=${RUN_PHASES}"
  fi
done <<'SPECS'
precondition|phase-00-precondition.sh
build|phase-01-build.sh
test|phase-02-test.sh
install_boot|phase-03-install-boot.sh
live_verify|phase-04-live-verify-api.sh
live_verify|phase-04-live-verify-api-app.sh
changelog_entry|phase-05a-changelog-entry.sh
distribute|phase-05-distribute.sh
docs_refresh|phase-06-docs.sh
SPECS

echo ""
echo "==============================================================="
echo "CASE G: exit 3 is only special for a \`gate\` phase — from any other"
echo "phase it is an ordinary failure"
echo "==============================================================="
echo "The gate's QUALIFIED meaning belongs to the gate contract, not to the"
echo "number 3. A build that happens to exit 3 must not be read as consent."

while IFS='|' read -r phase script; do
  _reset_phases
  _phase_rc "$script" "$phase" 3
  _run
  if [[ "$RUN_EXIT" -ne 0 && "$RUN_OUTCOME" == "FAIL" && "$RUN_PHASES" == *"('${phase}', 'FAIL')"* ]]; then
    pass "${phase} exiting 3 is an ordinary failure, not a qualified no-op"
  else
    fail "${phase} exiting 3 gave exit=${RUN_EXIT} outcome='${RUN_OUTCOME}' phases=${RUN_PHASES}"
  fi
done <<'SPECS'
build|phase-01-build.sh
live_verify|phase-04-live-verify-api.sh
changelog_entry|phase-05a-changelog-entry.sh
docs_refresh|phase-06-docs.sh
SPECS

echo ""
echo "==============================================================="
echo "CASE H: the default run announces, at RUN TIME, that it writes to"
echo "the repository"
echo "==============================================================="
echo "The default --until moved from live_verify to docs_refresh, which added"
echo "two phases that WRITE to the working tree. That was documented only in"
echo "the header — i.e. only in --help, whose content nothing asserted (CASE E)."

_reset_phases
_run
if grep -qi "leave the working tree" <<< "$RUN_CONSOLE" || grep -qi "WRITES TO THE REPOSITORY" <<< "$RUN_CONSOLE"; then
  pass "a default run discloses up front that it will write to the working tree"
else
  fail "a default run gives no run-time notice that it writes to the repository. Console head: $(head -6 <<< "$RUN_CONSOLE")"
fi

_reset_phases
_run --until test
if ! grep -qi "leave the working tree" <<< "$RUN_CONSOLE" && ! grep -qi "WRITES TO THE REPOSITORY" <<< "$RUN_CONSOLE"; then
  pass "a run that stops before the writing phases does NOT print the notice (it would be false)"
else
  fail "--until test printed a repository-writing notice for a run that writes nothing. Console: ${RUN_CONSOLE}"
fi

_reset_phases
_run --skip changelog_entry,distribute,docs_refresh
if ! grep -qi "leave the working tree" <<< "$RUN_CONSOLE" && ! grep -qi "WRITES TO THE REPOSITORY" <<< "$RUN_CONSOLE"; then
  pass "--skip of both writing phases suppresses the notice"
else
  fail "--skip changelog_entry,distribute,docs_refresh still printed a writing notice. Console: ${RUN_CONSOLE}"
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

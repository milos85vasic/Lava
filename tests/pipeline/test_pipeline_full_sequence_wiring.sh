#!/usr/bin/env bash
# Hermetic test suite for T046 + T057: the full-sequence phase wiring in
# scripts/pipeline-build-test-distribute.sh.
#
# WHY A SEPARATE SUITE FROM test_pipeline_orchestrator.sh:
# that suite drives the REAL phase scripts against disposable git fixtures and
# is therefore pinned to `--until precondition` — everything past it invokes
# Gradle, systemd, podman and an Android emulator. This suite is about the
# ORCHESTRATION of the three phases wired by T046 (`changelog_entry`,
# `distribute`, `docs_refresh`), which cannot be exercised at all without
# getting past those. So it substitutes synthetic phase scripts for the real
# ones, exactly as tests/pipeline/test_phase_failure_always_recorded.sh does,
# and asserts on ORDER, on the report, and on the exit code.
#
# THE ONE DECISION THIS SUITE EXISTS TO PIN DOWN — phase-05-distribute.sh's
# exit code 3.
#
# That script is a REFUSAL GATE that deliberately cannot distribute. Its own
# header defines three exit codes:
#
#     0 - a distribution completed. RESERVED, and unreachable today.
#     2 - GATE REFUSED (the default).
#     3 - GATE QUALIFIED, and the distribute step is not implemented.
#
# Exit 3 is neither a success nor a failure, and the run report's
# `phases[].result` enum (PASS | FAIL | SKIPPED) has no value that means it:
#
#   * PASS would put a `distribute: PASS` entry in a report for a run that
#     distributed nothing. An auditor reading SC-008's "read report.json
#     first" would conclude a distribution happened. That is the §6.Z /
#     §6.AK bluff class, at the gate layer.
#   * SKIPPED is treated by finalize_run_report as NOT-PASS (deliberately —
#     see its own docblock), so every otherwise-perfect run would finalize to
#     `outcome: FAIL` and exit non-zero. A pipeline whose exit code is 1 for
#     both a good run and a broken one has no exit code at all.
#
# The wiring therefore records NO `phases[]` entry for a gate that qualified
# with nothing left to do, and says so loudly on stdout. The absence is the
# honest record, and `distributions: []` is its machine-readable half. This
# suite asserts BOTH halves, and asserts that every OTHER exit code from that
# gate still fails the run — because a phase whose failure cannot fail the run
# is precisely the defect this feature has spent its whole life finding.
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
cp "$ORCH" "${HARNESS}/scripts/"
cp "${LIBDIR}/run-report.sh" "${LIBDIR}/evidence.sh" "${HARNESS}/scripts/pipeline/lib/"

cat > "${HARNESS}/bin/git" <<'GITEOF'
#!/usr/bin/env bash
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

# _phase <basename> — write a synthetic phase script from stdin.
_phase() {
  local name="$1"
  cat > "${HARNESS}/scripts/pipeline/${name}"
  chmod +x "${HARNESS}/scripts/pipeline/${name}"
}

# Every synthetic phase script records that it RAN, and what the in-flight
# marker said while it was running, into files named by the environment. Those
# two facts are what prove a phase is genuinely reached (and genuinely marked)
# rather than merely listed in a registry.
_phase_ok() {
  local name="$1" phase="$2"
  _phase "$name" <<EOF
#!/usr/bin/env bash
set -euo pipefail
source "\$(dirname "\${BASH_SOURCE[0]}")/lib/run-report.sh"
printf '%s\n' "${phase}" >> "\$LAVA_TEST_ORDER_FILE"
printf '%s=%s\n' "${phase}" "\$(cat ".lava-ci-evidence/pipeline-runs/\$1/.phase-in-flight" 2>/dev/null || echo MISSING)" \
  >> "\$LAVA_TEST_MARKER_FILE"
append_phase_result "\$1" "${phase}" PASS 1 "d" >/dev/null
echo "${name}: ok"
EOF
}

# A phase script that exits with a chosen code and appends NOTHING to
# report.json — the real phase-05-distribute.sh's documented behaviour
# ("writes NO Distribution Record and mutates report.json in NO way").
_phase_gate() {
  local name="$1" phase="$2" rc="$3"
  _phase "$name" <<EOF
#!/usr/bin/env bash
set -uo pipefail
printf '%s\n' "${phase}" >> "\$LAVA_TEST_ORDER_FILE"
printf '%s=%s\n' "${phase}" "\$(cat ".lava-ci-evidence/pipeline-runs/\$1/.phase-in-flight" 2>/dev/null || echo MISSING)" \
  >> "\$LAVA_TEST_MARKER_FILE"
echo "${name}: synthetic gate exiting ${rc}"
exit ${rc}
EOF
}

# A phase script that fails WITHOUT appending its own result.
_phase_dies_early() {
  local name="$1" phase="$2" rc="${3:-1}"
  _phase "$name" <<EOF
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "${phase}" >> "\$LAVA_TEST_ORDER_FILE"
echo "${name}: dying (${rc}) before it could append its own result"
exit ${rc}
EOF
}

# _reset_phases — the whole eight-phase happy path. The distribute gate
# defaults to exit 3, which is what the REAL gate returns when it qualifies.
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
  _phase_gate phase-05-distribute.sh distribute 3
  _phase_ok phase-06-docs.sh docs_refresh
}

RUN_EXIT=0; RUN_OUTCOME=""; RUN_PHASES=""; RUN_ORDER=""; RUN_MARKERS=""
RUN_CONSOLE=""; RUN_DISTRIBUTIONS=""
# _run [args...] — run the orchestrator in a pristine cwd; sets RUN_* globals.
_run() {
  local cwd; cwd="$(mktemp -d "${WORKDIR}/run.XXXXXX")"
  local order="${cwd}/order.txt" markers="${cwd}/markers.txt"
  : > "$order"; : > "$markers"
  set +e
  ( cd "$cwd" \
      && PATH="${HARNESS}/bin:$PATH" \
         LAVA_TEST_ORDER_FILE="$order" \
         LAVA_TEST_MARKER_FILE="$markers" \
         bash "${HARNESS}/scripts/pipeline-build-test-distribute.sh" "$@" ) \
    > "${cwd}/console.txt" 2>&1
  RUN_EXIT=$?
  set -e
  RUN_ORDER="$(tr '\n' ' ' < "$order")"
  RUN_MARKERS="$(tr '\n' ' ' < "$markers")"
  RUN_CONSOLE="$(cat "${cwd}/console.txt")"
  local rp
  # `|| true`: a usage error is refused BEFORE init_run_report, so there is
  # legitimately no run directory to find. Under `set -e` + pipefail the
  # failing `find` would abort this suite instead of letting the case assert.
  rp="$(find "${cwd}/.lava-ci-evidence/pipeline-runs" -mindepth 2 -maxdepth 2 -name report.json 2>/dev/null | head -1 || true)"
  if [[ -z "$rp" ]]; then
    RUN_OUTCOME="(no report)"; RUN_PHASES="[]"; RUN_DISTRIBUTIONS="(no report)"; return 0
  fi
  RUN_OUTCOME="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["outcome"])' "$rp")"
  RUN_PHASES="$(python3 -c 'import json,sys; print([(p["name"],p["result"]) for p in json.load(open(sys.argv[1]))["phases"]])' "$rp")"
  RUN_DISTRIBUTIONS="$(python3 -c 'import json,sys; print(len(json.load(open(sys.argv[1]))["distributions"]))' "$rp")"
}

echo "==============================================================="
echo "GROUP A: the registry, the preflight, and --until"
echo "==============================================================="

_reset_phases

for p in changelog_entry distribute docs_refresh; do
  _run --until "$p"
  if [[ "$RUN_EXIT" -eq 2 ]] && grep -q "is not a wired phase" <<< "$RUN_CONSOLE"; then
    fail "--until '${p}' is still rejected as not-wired — T046 requires it to be an accepted phase name"
  else
    pass "--until '${p}' is an accepted phase name (exit ${RUN_EXIT})"
  fi
done

# closure has no phase-07-closure.sh at all (blocked behind T054's review).
_run --until closure
if [[ "$RUN_EXIT" -eq 2 ]] && grep -q "is not a wired phase" <<< "$RUN_CONSOLE"; then
  pass "--until closure is still a usage error (exit 2) naming it as not wired"
else
  fail "--until closure exited ${RUN_EXIT} — it must stay a usage error, never a silent no-op. Console: ${RUN_CONSOLE}"
fi

if grep -q "closure" <<< "$RUN_CONSOLE"; then
  pass "the not-wired refusal still explains that closure specifically is blocked"
else
  fail "the not-wired refusal no longer mentions closure: ${RUN_CONSOLE}"
fi

for p in changelog_entry distribute docs_refresh; do
  _run --skip "$p" --until live_verify
  if [[ "$RUN_EXIT" -eq 2 ]] && grep -q "is not a wired phase" <<< "$RUN_CONSOLE"; then
    fail "--skip '${p}' is rejected as not-wired — every wired phase must be skippable except precondition"
  else
    pass "--skip '${p}' is accepted (exit ${RUN_EXIT})"
  fi
done

# Preflight must cover the NEW scripts, not just the original five.
for missing in phase-05a-changelog-entry.sh phase-05-distribute.sh phase-06-docs.sh; do
  _reset_phases
  mv "${HARNESS}/scripts/pipeline/${missing}" "${HARNESS}/${missing}.hidden"
  _run --until precondition
  if [[ "$RUN_EXIT" -eq 2 ]] && grep -qF "wired phase script not found" <<< "$RUN_CONSOLE" \
     && grep -qF "$missing" <<< "$RUN_CONSOLE"; then
    pass "preflight names the missing ${missing} and refuses before anything runs"
  else
    fail "preflight did not catch a missing ${missing} (exit ${RUN_EXIT}). Console: ${RUN_CONSOLE}"
  fi
  if [[ -z "$RUN_ORDER" ]]; then
    pass "preflight refusal for ${missing} ran no phase at all"
  else
    fail "preflight refusal for ${missing} still ran phases: ${RUN_ORDER}"
  fi
  mv "${HARNESS}/${missing}.hidden" "${HARNESS}/scripts/pipeline/${missing}"
done

echo ""
echo "==============================================================="
echo "GROUP B: R-004 ordering — changelog BEFORE the distribute gate,"
echo "broader docs AFTER it"
echo "==============================================================="

_reset_phases
_run
EXPECTED_ORDER="precondition build test install_boot live_verify live_verify changelog_entry distribute docs_refresh "
if [[ "$RUN_ORDER" == "$EXPECTED_ORDER" ]]; then
  pass "all eight phases ran, in the full R-004 sequence"
else
  fail "phase order was '${RUN_ORDER}', expected '${EXPECTED_ORDER}'"
fi

# Assert the ordering RELATION explicitly, not only the whole string, so a
# future reordering fails with a message that says which pair inverted.
_idx() { local needle="$1"; local i=0 w; for w in $RUN_ORDER; do i=$((i+1)); [[ "$w" == "$needle" ]] && { echo "$i"; return 0; }; done; echo 0; }
i_cl="$(_idx changelog_entry)"; i_di="$(_idx distribute)"; i_do="$(_idx docs_refresh)"
if [[ "$i_cl" -gt 0 && "$i_di" -gt 0 && "$i_cl" -lt "$i_di" ]]; then
  pass "changelog_entry runs BEFORE distribute (R-004: the gate reads the CHANGELOG as a pre-existing input)"
else
  fail "changelog_entry at ${i_cl}, distribute at ${i_di} — R-004 requires changelog first"
fi
if [[ "$i_di" -gt 0 && "$i_do" -gt 0 && "$i_di" -lt "$i_do" ]]; then
  pass "docs_refresh runs AFTER distribute (R-004)"
else
  fail "distribute at ${i_di}, docs_refresh at ${i_do} — R-004 requires the broader docs pass last"
fi

if [[ "$RUN_MARKERS" == *"changelog_entry=changelog_entry"* \
   && "$RUN_MARKERS" == *"distribute=distribute"* \
   && "$RUN_MARKERS" == *"docs_refresh=docs_refresh"* ]]; then
  pass "each newly-wired phase is marked in flight, under its own name, while it runs"
else
  fail "in-flight markers were '${RUN_MARKERS}' — each new phase must be marked with its own name"
fi

echo ""
echo "==============================================================="
echo "CASE C (LOAD-BEARING): the distribute gate's exit 3 is neither a"
echo "pass nor a failure, and the report must claim neither"
echo "==============================================================="

_reset_phases
_run
if [[ "$RUN_EXIT" -eq 0 ]]; then
  pass "a run whose gate QUALIFIED with nothing left to do exits 0"
else
  fail "gate exit 3 made the whole run exit ${RUN_EXIT} — every otherwise-good run would fail. Console: ${RUN_CONSOLE}"
fi
if [[ "$RUN_OUTCOME" == "PASS" ]]; then
  pass "outcome is PASS (exit 3 is not a failure)"
else
  fail "outcome is '${RUN_OUTCOME}', expected PASS. phases=${RUN_PHASES}"
fi
if [[ "$RUN_PHASES" != *"'distribute'"* ]]; then
  pass "report.json records NO distribute entry — nothing was distributed, and no phases[] result may claim otherwise"
else
  fail "report.json contains a distribute entry (${RUN_PHASES}) for a run that distributed nothing"
fi
if [[ "$RUN_DISTRIBUTIONS" == "0" ]]; then
  pass "distributions[] is empty — the machine-readable half of 'nothing was distributed'"
else
  fail "distributions[] has ${RUN_DISTRIBUTIONS} entries for a run that distributed nothing"
fi
if grep -qi "QUALIFIED" <<< "$RUN_CONSOLE" && grep -qi "distributed nothing" <<< "$RUN_CONSOLE"; then
  pass "the orchestrator DISCLOSES on stdout that the gate qualified and nothing was distributed"
else
  fail "no explicit qualified-but-distributed-nothing disclosure on stdout. Console: ${RUN_CONSOLE}"
fi
if [[ "$RUN_ORDER" == *"distribute docs_refresh"* ]]; then
  pass "exit 3 does not halt the run — docs_refresh still runs after it"
else
  fail "the run stopped at the gate; order was '${RUN_ORDER}'"
fi

echo ""
echo "==============================================================="
echo "CASE D (LOAD-BEARING): the gate's REFUSAL (exit 2) genuinely"
echo "fails the run, and stops it"
echo "==============================================================="

_reset_phases
_phase_gate phase-05-distribute.sh distribute 2
_run
if [[ "$RUN_EXIT" -ne 0 ]]; then
  pass "a REFUSED gate makes the run exit non-zero (${RUN_EXIT})"
else
  fail "a REFUSED gate still exited 0 — a phase whose failure cannot fail the run is not wired, it is decorative"
fi
if [[ "$RUN_OUTCOME" == "FAIL" ]]; then
  pass "a REFUSED gate makes outcome FAIL"
else
  fail "outcome is '${RUN_OUTCOME}', expected FAIL. phases=${RUN_PHASES}"
fi
if [[ "$RUN_PHASES" == *"('distribute', 'FAIL')"* ]]; then
  pass "the refusal is recorded as distribute/FAIL — the gate itself appends nothing, so the orchestrator must"
else
  fail "phases[] does not record distribute/FAIL: ${RUN_PHASES}"
fi
if [[ "$RUN_ORDER" != *"docs_refresh"* ]]; then
  pass "a REFUSED gate halts the run before docs_refresh"
else
  fail "the run continued past a REFUSED gate: ${RUN_ORDER}"
fi

echo ""
echo "==============================================================="
echo "CASE E: an UNDEFINED exit code from the gate fails the run"
echo "==============================================================="
echo "phase-05-distribute.sh defines 0, 2 and 3 and no others. A code it"
echo "never defined means something went wrong that nobody modelled, and"
echo "the only safe reading of an unmodelled state is failure."

_reset_phases
_phase_gate phase-05-distribute.sh distribute 7
_run
if [[ "$RUN_EXIT" -ne 0 && "$RUN_OUTCOME" == "FAIL" && "$RUN_PHASES" == *"('distribute', 'FAIL')"* ]]; then
  pass "an undefined gate exit code (7) fails the run and is recorded as distribute/FAIL"
else
  fail "undefined gate exit 7 gave exit=${RUN_EXIT} outcome='${RUN_OUTCOME}' phases=${RUN_PHASES}"
fi

echo ""
echo "==============================================================="
echo "CASE F: the gate's RESERVED exit 0 (a real distribution) is the"
echo "only code that may record distribute/PASS"
echo "==============================================================="

_reset_phases
_phase_gate phase-05-distribute.sh distribute 0
_run
if [[ "$RUN_EXIT" -eq 0 && "$RUN_OUTCOME" == "PASS" && "$RUN_PHASES" == *"('distribute', 'PASS')"* ]]; then
  pass "gate exit 0 records distribute/PASS and the run passes"
else
  fail "gate exit 0 gave exit=${RUN_EXIT} outcome='${RUN_OUTCOME}' phases=${RUN_PHASES}"
fi

echo ""
echo "==============================================================="
echo "CASE G: the two SELF-APPENDING new phases still fail the run when"
echo "they die before appending anything"
echo "==============================================================="

for spec in "phase-05a-changelog-entry.sh:changelog_entry:distribute" \
            "phase-06-docs.sh:docs_refresh:"; do
  script="${spec%%:*}"; rest="${spec#*:}"; phase="${rest%%:*}"; must_not_run="${rest#*:}"
  _reset_phases
  _phase_dies_early "$script" "$phase" 1
  _run
  if [[ "$RUN_EXIT" -ne 0 && "$RUN_OUTCOME" == "FAIL" && "$RUN_PHASES" == *"('${phase}', 'FAIL')"* ]]; then
    pass "${phase} dying before it appends is recorded as ${phase}/FAIL and fails the run"
  else
    fail "${phase} failure gave exit=${RUN_EXIT} outcome='${RUN_OUTCOME}' phases=${RUN_PHASES}"
  fi
  if [[ -n "$must_not_run" ]]; then
    if [[ "$RUN_ORDER" != *"$must_not_run"* ]]; then
      pass "a failing ${phase} halts the run before ${must_not_run}"
    else
      fail "the run continued to ${must_not_run} after ${phase} failed: ${RUN_ORDER}"
    fi
  fi
done

echo ""
echo "==============================================================="
echo "CASE H (POSITIVE): a fully green run records every phase it ran,"
echo "exactly once each, with no phantom entries"
echo "==============================================================="
echo "Without this, a change that fails or drops every run would satisfy"
echo "every case above."

_reset_phases
_run
if [[ "$RUN_EXIT" -eq 0 && "$RUN_OUTCOME" == "PASS" ]]; then
  pass "the full eight-phase happy path exits 0 with outcome PASS"
else
  fail "the happy path gave exit=${RUN_EXIT} outcome='${RUN_OUTCOME}' phases=${RUN_PHASES}"
fi
for expected in "('precondition', 'PASS')" "('build', 'PASS')" "('test', 'PASS')" \
                "('install_boot', 'PASS')" "('live_verify', 'PASS')" \
                "('changelog_entry', 'PASS')" "('docs_refresh', 'PASS')"; do
  if [[ "$RUN_PHASES" == *"$expected"* ]]; then
    pass "phases[] contains ${expected}"
  else
    fail "phases[] is missing ${expected}: ${RUN_PHASES}"
  fi
done
_n_cl="$(grep -c -o "('changelog_entry', 'PASS')" <<< "$RUN_PHASES" || true)"
_n_do="$(grep -c -o "('docs_refresh', 'PASS')" <<< "$RUN_PHASES" || true)"
if [[ "$_n_cl" -eq 1 && "$_n_do" -eq 1 ]]; then
  pass "the self-appending new phases are recorded exactly once each (no double-append)"
else
  fail "changelog_entry appears ${_n_cl} time(s) and docs_refresh ${_n_do} time(s), expected 1 each: ${RUN_PHASES}"
fi
if [[ "$RUN_PHASES" != *"'FAIL'"* && "$RUN_PHASES" != *"'SKIPPED'"* ]]; then
  pass "no phantom FAIL/SKIPPED entry in a fully green run"
else
  fail "a green run gained a non-PASS entry: ${RUN_PHASES}"
fi

echo ""
echo "==============================================================="
echo "CASE I: --until stops where it is told, in the new tail too"
echo "==============================================================="

_reset_phases
_run --until changelog_entry
if [[ "$RUN_ORDER" == *"changelog_entry"* && "$RUN_ORDER" != *"distribute"* ]]; then
  pass "--until changelog_entry runs the changelog phase and stops before the gate"
else
  fail "--until changelog_entry produced order '${RUN_ORDER}'"
fi
_reset_phases
_run --until distribute
if [[ "$RUN_ORDER" == *"distribute"* && "$RUN_ORDER" != *"docs_refresh"* ]]; then
  pass "--until distribute runs the gate and stops before docs_refresh"
else
  fail "--until distribute produced order '${RUN_ORDER}'"
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

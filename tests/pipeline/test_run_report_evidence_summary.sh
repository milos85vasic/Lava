#!/usr/bin/env bash
# Hermetic test suite for scripts/pipeline/lib/run-report.sh's
# `recompute_evidence_summary` (the Pipeline Run Report's evidence_summary
# aggregator).
#
# WHY THIS TEST EXISTS (forensic anchor, 2026-08-21, T038/T057 wiring):
# data-model.md's Validation rule states that a Pipeline Run Report's
# `outcome` is "PASS" if and only if every phase is PASS *AND*
# `evidence_summary.rejected_by_anti_bluff == 0`. `finalize_run_report`
# implements that rule literally and correctly. But at the time this test
# was written, `evidence_summary` was initialized to all-zeros by
# `init_run_report` and then NEVER updated by anything: `phase-02-test.sh`
# tallies the same counters locally and prints them to its console SUMMARY,
# but does not write them back into report.json. The consequence is exactly
# the bluff class this project's constitution exists to evict: a run whose
# Evidence Records were REJECTED by anti-bluff validation could still
# finalize to `outcome: "PASS"`, because the counter the rule reads was
# permanently 0. The rule looked enforced and was not.
#
# So this suite's load-bearing case is CASE 3: a run with a genuinely
# REJECTED Evidence Record on disk MUST finalize to "FAIL". If that case
# ever passes while a REJECTED record sits in the run directory, the
# anti-bluff half of the outcome rule has silently become a no-op again.
#
# Design note — why a separate aggregator rather than folding the scan into
# `finalize_run_report`: the existing suite
# tests/pipeline/test_evidence_and_run_report.sh asserts that
# `finalize_run_report` leaves a caller-set `evidence_summary` UNCHANGED
# (its "evidence_summary.skipped survived finalize_run_report unchanged"
# case). That is a deliberate separation of concerns — finalize applies the
# rule, an aggregator supplies the inputs — and this test does not break it.
# What was missing was the aggregator, not a change to finalize.
#
# Everything here runs inside a mktemp -d working directory, because every
# run-report.sh function resolves its report path relative to the CURRENT
# WORKING DIRECTORY. This suite never touches this repository's real
# .lava-ci-evidence/ tree.
#
# Exit 0 if every case passes; non-zero otherwise.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUN_REPORT_LIB="${REPO_ROOT}/scripts/pipeline/lib/run-report.sh"
EVIDENCE_LIB="${REPO_ROOT}/scripts/pipeline/lib/evidence.sh"

for lib in "$RUN_REPORT_LIB" "$EVIDENCE_LIB"; do
  if [[ ! -f "$lib" ]]; then
    echo "FAIL: library under test not found: $lib"
    exit 1
  fi
done
if ! command -v jq >/dev/null 2>&1; then
  echo "FAIL: jq is required to run this test suite but was not found on PATH"
  exit 1
fi

FAILURES=0
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAILURES=$((FAILURES + 1)); }

WORKDIR="$(mktemp -d)"
trap 'rm -rf -- "$WORKDIR"' EXIT

# shellcheck source=scripts/pipeline/lib/run-report.sh
source "$RUN_REPORT_LIB"
# shellcheck source=scripts/pipeline/lib/evidence.sh
source "$EVIDENCE_LIB"
# shellcheck source=scripts/pipeline/lib/anti-bluff-validate.sh
# UPDATED 2026-08-26: the fixtures below must model a REAL pipeline run, and a
# real run always has the independent validator evaluate each record after it
# is written (every production wrapper does exactly that). Before this change
# the fixtures wrote records and never validated them, which was survivable
# only because write_evidence_record stamped every record it wrote with the
# literal "validated" — the validator's own accept value. That fail-open
# default is gone (an unvalidated record now carries a "REJECTED: ..."
# not-yet-validated placeholder and therefore fails closed), so a fixture that
# skips validation is now modelling a broken run, not a clean one.
source "${REPO_ROOT}/scripts/pipeline/lib/anti-bluff-validate.sh"

FAKE_SHA="0123456789abcdef0123456789abcdef01234567"

# _field <report-path> <jq-path> — read one field out of a report.json.
_field() { jq -r "$2" "$1"; }

# _seed_record <phase_dir> <test_id> <category> <result> — write one REAL
# Evidence Record via the production writer, with a real non-empty raw
# output file behind it (write_evidence_record requires the raw file to
# exist so it can normalize raw_output_ref relative to the record).
_seed_record() {
  local phase_dir="$1" test_id="$2" category="$3" result="$4"
  local raw_dir="${phase_dir}/${category}/raw"
  mkdir -p -- "$raw_dir"
  local raw_path="${raw_dir}/${test_id}.log"
  printf 'real captured output for %s\nresult=%s\n' "$test_id" "$result" > "$raw_path"
  local record_path
  record_path="$(write_evidence_record \
    "$phase_dir" \
    "$test_id" \
    "$category" \
    "bash -c 'echo real captured output for ${test_id}'" \
    "$result" \
    "expected 3 rows for ${test_id}, observed 3 rows with matching ids" \
    "$raw_path")"
  # Model the real run: the independent validator evaluates the finished
  # record. Without this the record keeps its not-yet-validated placeholder
  # and correctly counts as REJECTED, which would make these fixtures
  # describe a broken run rather than a clean one.
  if ! validate_evidence_record "$record_path" >/dev/null 2>&1; then
    fail "fixture error: the honest seeded record ${test_id} was REJECTED by the real anti-bluff validator — the fixture, not the code under test, is wrong"
  fi
}

echo "==============================================================="
echo "CASE 1: aggregator tallies real Evidence Records off disk"
echo "==============================================================="

cd "$WORKDIR"
RUN_1="2026-08-21T10-00-00Z"
init_run_report "$RUN_1" "$FAKE_SHA" >/dev/null
RUN_DIR_1=".lava-ci-evidence/pipeline-runs/${RUN_1}"
PHASE_DIR_1="${RUN_DIR_1}/phase-02"

_seed_record "$PHASE_DIR_1" "lava.core.AlphaTest" "kotlin-unit" "PASS"
_seed_record "$PHASE_DIR_1" "lava.core.BetaTest" "kotlin-unit" "PASS"
_seed_record "$PHASE_DIR_1" "lava.core.GammaTest" "kotlin-unit" "FAIL"
_seed_record "$PHASE_DIR_1" "TestGoDelta" "go-unit-integration" "SKIPPED"

if ! recompute_evidence_summary "$RUN_1" >/dev/null 2>&1; then
  fail "recompute_evidence_summary exited non-zero on a well-formed run"
else
  pass "recompute_evidence_summary exited 0 on a well-formed run"
fi

REPORT_1="${RUN_DIR_1}/report.json"
for expectation in "total 4" "passed 2" "failed 1" "skipped 1" "rejected_by_anti_bluff 0"; do
  key="${expectation%% *}"; want="${expectation##* }"
  got="$(_field "$REPORT_1" ".evidence_summary.${key}")"
  if [[ "$got" == "$want" ]]; then
    pass "evidence_summary.${key} == ${want}"
  else
    fail "evidence_summary.${key} is '${got}', expected '${want}'"
  fi
done

echo ""
echo "==============================================================="
echo "CASE 2: aggregator scans EVERY phase directory, not just one"
echo "==============================================================="

RUN_2="2026-08-21T11-00-00Z"
init_run_report "$RUN_2" "$FAKE_SHA" >/dev/null
RUN_DIR_2=".lava-ci-evidence/pipeline-runs/${RUN_2}"

_seed_record "${RUN_DIR_2}/phase-02" "lava.core.OneTest" "kotlin-unit" "PASS"
_seed_record "${RUN_DIR_2}/phase-03" "systemd-install-and-health" "hermetic-script" "PASS"
_seed_record "${RUN_DIR_2}/phase-04" "live-health-endpoint" "hermetic-script" "PASS"

recompute_evidence_summary "$RUN_2" >/dev/null 2>&1 || true
REPORT_2="${RUN_DIR_2}/report.json"
got_total_2="$(_field "$REPORT_2" '.evidence_summary.total')"
if [[ "$got_total_2" == "3" ]]; then
  pass "records from phase-02, phase-03 and phase-04 all counted (total == 3)"
else
  fail "evidence_summary.total is '${got_total_2}', expected 3 — the aggregator is not scanning every phase directory"
fi

echo ""
echo "==============================================================="
echo "CASE 3 (LOAD-BEARING): a REJECTED record must force outcome FAIL"
echo "==============================================================="
echo "This is the case that proves data-model.md's Validation rule is"
echo "genuinely enforced. Every phase below is PASS; the ONLY thing that"
echo "may legitimately fail this run is the rejected Evidence Record."
echo ""

RUN_3="2026-08-21T12-00-00Z"
init_run_report "$RUN_3" "$FAKE_SHA" >/dev/null
RUN_DIR_3=".lava-ci-evidence/pipeline-runs/${RUN_3}"
PHASE_DIR_3="${RUN_DIR_3}/phase-02"

_seed_record "$PHASE_DIR_3" "lava.core.HonestTest" "kotlin-unit" "PASS"
_seed_record "$PHASE_DIR_3" "lava.core.BluffingTest" "kotlin-unit" "PASS"

# Mark the second record REJECTED exactly the way anti-bluff-validate.sh
# does (it rewrites anti_bluff_status in place to "REJECTED: <reason>").
BLUFF_RECORD="${PHASE_DIR_3}/kotlin-unit/lava.core.BluffingTest.json"
if [[ ! -f "$BLUFF_RECORD" ]]; then
  fail "fixture setup error: expected seeded record at ${BLUFF_RECORD}"
else
  tmp_bluff="${BLUFF_RECORD}.tmp"
  jq '.anti_bluff_status = "REJECTED: assertion_summary is generic boilerplate"' \
    "$BLUFF_RECORD" > "$tmp_bluff" && mv -f "$tmp_bluff" "$BLUFF_RECORD"
fi

append_phase_result "$RUN_3" "precondition" "PASS" 1 "${RUN_DIR_3}/phase-00" >/dev/null
append_phase_result "$RUN_3" "test" "PASS" 42 "$PHASE_DIR_3" >/dev/null

recompute_evidence_summary "$RUN_3" >/dev/null 2>&1 || true
finalize_run_report "$RUN_3" >/dev/null

REPORT_3="${RUN_DIR_3}/report.json"
got_rejected_3="$(_field "$REPORT_3" '.evidence_summary.rejected_by_anti_bluff')"
if [[ "$got_rejected_3" == "1" ]]; then
  pass "evidence_summary.rejected_by_anti_bluff == 1 (the REJECTED record was actually counted)"
else
  fail "evidence_summary.rejected_by_anti_bluff is '${got_rejected_3}', expected 1 — a REJECTED record on disk was not counted"
fi

got_outcome_3="$(_field "$REPORT_3" '.outcome')"
if [[ "$got_outcome_3" == "FAIL" ]]; then
  pass "outcome == FAIL despite every phase being PASS (anti-bluff rule is genuinely load-bearing)"
else
  fail "outcome is '${got_outcome_3}', expected FAIL — a run containing a REJECTED Evidence Record reported success. This is the exact bluff data-model.md's Validation rule exists to prevent."
fi

echo ""
echo "==============================================================="
echo "CASE 4: an all-clean run still reaches outcome PASS"
echo "(guards against 'fix' that just hardcodes FAIL)"
echo "==============================================================="

RUN_4="2026-08-21T13-00-00Z"
init_run_report "$RUN_4" "$FAKE_SHA" >/dev/null
RUN_DIR_4=".lava-ci-evidence/pipeline-runs/${RUN_4}"
_seed_record "${RUN_DIR_4}/phase-02" "lava.core.CleanTest" "kotlin-unit" "PASS"
append_phase_result "$RUN_4" "precondition" "PASS" 1 "${RUN_DIR_4}/phase-00" >/dev/null
append_phase_result "$RUN_4" "test" "PASS" 5 "${RUN_DIR_4}/phase-02" >/dev/null
recompute_evidence_summary "$RUN_4" >/dev/null 2>&1 || true
finalize_run_report "$RUN_4" >/dev/null

got_outcome_4="$(_field "${RUN_DIR_4}/report.json" '.outcome')"
if [[ "$got_outcome_4" == "PASS" ]]; then
  pass "a genuinely clean run still reaches outcome PASS"
else
  fail "outcome is '${got_outcome_4}', expected PASS — the aggregator is failing clean runs"
fi

echo ""
echo "==============================================================="
echo "CASE 5: a run with zero Evidence Records yields all-zero counters"
echo "==============================================================="

RUN_5="2026-08-21T14-00-00Z"
init_run_report "$RUN_5" "$FAKE_SHA" >/dev/null
recompute_evidence_summary "$RUN_5" >/dev/null 2>&1 || true
got_total_5="$(_field ".lava-ci-evidence/pipeline-runs/${RUN_5}/report.json" '.evidence_summary.total')"
if [[ "$got_total_5" == "0" ]]; then
  pass "an empty run reports total == 0 (no phantom records invented)"
else
  fail "evidence_summary.total is '${got_total_5}' for a run with no records, expected 0"
fi

echo ""
echo "==============================================================="
echo "CASE 6 (LOAD-BEARING): when recompute_evidence_summary FAILS, the"
echo "run must not report success — neither in report.json nor in exit"
echo "==============================================================="
echo "FORENSIC ANCHOR (2026-08-26, independent audit of the orchestrator):"
echo "CASES 1-5 above all exercise recompute WORKING. Nothing exercised it"
echo "FAILING, and _close_report handled that with:"
echo ""
echo "    recompute_evidence_summary \"\$run_id\" >/dev/null 2>&1 || echo WARNING"
echo ""
echo "recompute is the ONLY writer of evidence_summary.rejected_by_anti_bluff"
echo "(init_run_report seeds it 0), so a failed recompute left the outcome rule"
echo "reading 'nothing was rejected' and finalizing to PASS. Measured, with a"
echo "REJECTED record on disk in BOTH runs, one return code apart:"
echo "   pristine        -> rejected=1  outcome=FAIL     exit=1"
echo "   recompute fails -> rejected=0  outcome=PASS     exit=0   <-- fail-open"
echo "The existing backstop could not catch it: it tests for a non-PASS OUTCOME,"
echo "and the outcome genuinely computed to PASS off the unmeasured counter."
echo ""

# This case drives the REAL orchestrator, because the defect lived in the
# orchestrator's close path rather than in the library. The fixture holds the
# real orchestrator and the real libraries; the failure is injected by
# APPENDING an override to the FIXTURE'S COPY of run-report.sh. The repository's
# own scripts/pipeline/lib/run-report.sh is never modified — a test that mutates
# the code under test in place would be its own hazard.

CASE6_EXAMINED=0

# _c6_fixture <override> — build a harness tree; prints its root.
#   override: "" (pristine) | "recompute" (force recompute to return 1)
_c6_fixture() {
  local override="$1"
  local H; H="$(mktemp -d "${WORKDIR}/c6.XXXXXX")"
  mkdir -p "${H}/scripts/pipeline/lib" "${H}/bin"
  cp "${REPO_ROOT}/scripts/pipeline-build-test-distribute.sh" "${H}/scripts/"
  cp "$RUN_REPORT_LIB" "$EVIDENCE_LIB" "${H}/scripts/pipeline/lib/"

  cat > "${H}/bin/git" <<'GITEOF'
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
  chmod +x "${H}/bin/git"

  if [[ "$override" == "recompute" ]]; then
    # Appended AFTER the real definition, so this one wins for the orchestrator
    # that sources this copy. Fixture-only.
    printf '\nrecompute_evidence_summary() { return 1; }\n' \
      >> "${H}/scripts/pipeline/lib/run-report.sh"
  fi

  _c6_phase() { cat > "${H}/scripts/pipeline/$1"; chmod +x "${H}/scripts/pipeline/$1"; }
  _c6_ok() {
    _c6_phase "$1" <<EOF
#!/usr/bin/env bash
set -euo pipefail
source "\$(dirname "\${BASH_SOURCE[0]}")/lib/run-report.sh"
append_phase_result "\$1" "$2" PASS 1 "d" >/dev/null
echo "$1: ok"
EOF
  }

  _c6_phase phase-00-precondition.sh <<'EOF'
#!/usr/bin/env bash
echo "precondition: ok"
exit 0
EOF
  _c6_ok phase-01-build.sh build

  # The load-bearing fixture detail: this phase PASSES, and leaves a genuinely
  # REJECTED Evidence Record behind — written by the production writer and
  # marked exactly the way anti-bluff-validate.sh marks one, as CASE 3 does.
  # Every phase is PASS, so the rejected record is the ONLY thing that may
  # legitimately fail this run.
  _c6_phase phase-02-test.sh <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
LIB="$(dirname "${BASH_SOURCE[0]}")/lib"
source "${LIB}/run-report.sh"
source "${LIB}/evidence.sh"
run_id="$1"
pd=".lava-ci-evidence/pipeline-runs/${run_id}/phase-02"
mkdir -p "${pd}/kotlin-unit/raw"
printf 'real captured output for lava.core.BluffingTest\n' > "${pd}/kotlin-unit/raw/Bluff.log"
write_evidence_record "$pd" "lava.core.BluffingTest" "kotlin-unit" \
  "bash -c 'echo real captured output'" "PASS" \
  "expected 3 rows for lava.core.BluffingTest, observed 3 rows with matching ids" \
  "${pd}/kotlin-unit/raw/Bluff.log" >/dev/null
rec="${pd}/kotlin-unit/lava.core.BluffingTest.json"
tmp="${rec}.tmp"
jq '.anti_bluff_status = "REJECTED: assertion_summary is generic boilerplate"' \
  "$rec" > "$tmp" && mv -f "$tmp" "$rec"
append_phase_result "$run_id" test PASS 1 "$pd" >/dev/null
echo "test: ok, and left a REJECTED Evidence Record on disk"
EOF

  _c6_ok phase-03-install-boot.sh install_boot
  _c6_ok phase-04-live-verify-api.sh live_verify
  _c6_ok phase-04-live-verify-api-app.sh live_verify
  _c6_ok phase-05a-changelog-entry.sh changelog_entry
  _c6_phase phase-05-distribute.sh <<'EOF'
#!/usr/bin/env bash
set -uo pipefail
echo "distribute: gate qualified; nothing to distribute"
exit 3
EOF
  _c6_ok phase-06-docs.sh docs_refresh
  printf '%s' "$H"
}

C6_EXIT=0; C6_OUTCOME=""; C6_CONSOLE=""; C6_REJECTED_ON_DISK=0
# _c6_run <override> — run the orchestrator once; sets the C6_* globals.
_c6_run() {
  local H; H="$(_c6_fixture "$1")"
  local cwd; cwd="$(mktemp -d "${WORKDIR}/c6run.XXXXXX")"
  set +e
  ( cd "$cwd" && PATH="${H}/bin:$PATH" \
      bash "${H}/scripts/pipeline-build-test-distribute.sh" ) > "${cwd}/console.txt" 2>&1
  C6_EXIT=$?
  set -e
  C6_CONSOLE="$(cat "${cwd}/console.txt")"
  C6_REJECTED_ON_DISK="$(find "${cwd}/.lava-ci-evidence" -path '*kotlin-unit*' -name '*.json' \
      -exec jq -e -r 'select(.anti_bluff_status | tostring | startswith("REJECTED"))' {} \; 2>/dev/null \
      | grep -c REJECTED || true)"
  local rp
  rp="$(find "${cwd}/.lava-ci-evidence/pipeline-runs" -mindepth 2 -maxdepth 2 -name report.json 2>/dev/null | head -1)"
  if [[ -n "$rp" ]]; then C6_OUTCOME="$(jq -r '.outcome' "$rp")"; else C6_OUTCOME="(no report)"; fi
  CASE6_EXAMINED=$((CASE6_EXAMINED + 1))
}

# --- 6a: the defect itself -------------------------------------------------
_c6_run recompute

if [[ "$C6_REJECTED_ON_DISK" -ge 1 ]]; then
  pass "fixture precondition: a REJECTED Evidence Record is physically on disk (${C6_REJECTED_ON_DISK})"
else
  fail "fixture setup error: no REJECTED Evidence Record was written, so this case would prove nothing"
fi

if [[ "$C6_EXIT" -ne 0 ]]; then
  pass "a run whose recompute FAILED does not exit 0 (exit=${C6_EXIT})"
else
  fail "the orchestrator exited 0 for a run carrying a REJECTED Evidence Record whose evidence_summary was never measured. This is the fail-open: one line of stderr was the only trace."
fi

if [[ "$C6_OUTCOME" != "PASS" ]]; then
  pass "report.json does not claim PASS (outcome=${C6_OUTCOME})"
else
  fail "report.json says outcome PASS. The exit code protects only the running process; report.json outlives it and is what a later reader — and §6.AA clause 8(A)/(B) — consults."
fi

# The report must state the TRUE reason. The untrustworthy channel is shared
# with the interrupt path, whose message was hardcoded to "THIS RUN WAS
# INTERRUPTED"; emitting that here would send a reader hunting an interruption
# that never happened.
if grep -q "evidence_summary COULD NOT BE RECOMPUTED" <<< "$C6_CONSOLE"; then
  pass "the console names the real reason (evidence_summary could not be recomputed)"
else
  fail "the console never states that evidence_summary could not be recomputed"
fi
if grep -q "THIS RUN WAS INTERRUPTED" <<< "$C6_CONSOLE"; then
  fail "the console claims THIS RUN WAS INTERRUPTED, which is false — this run was not interrupted, its recompute failed. A report that misdescribes why it cannot be trusted is its own bluff."
else
  pass "the console does not falsely claim the run was interrupted"
fi

# --- 6b: control — the SAME fixture with recompute working -----------------
# Guards against a 'fix' that simply fails every run: the rejected record must
# still be caught the honest way, through the counter.
_c6_run ""
if [[ "$C6_EXIT" -ne 0 && "$C6_OUTCOME" == "FAIL" ]]; then
  pass "control: with recompute WORKING the same run is caught honestly (outcome=FAIL, exit=${C6_EXIT})"
else
  fail "control: expected outcome FAIL and non-zero exit with recompute working; got outcome='${C6_OUTCOME}' exit=${C6_EXIT}"
fi

# A gate that reports success having examined nothing is the vacuous pass this
# repository has recorded ~50 times. State the count, and fail on zero.
echo ""
echo "CASE 6 examined ${CASE6_EXAMINED} orchestrator run(s)."
if [[ "$CASE6_EXAMINED" -ge 2 ]]; then
  pass "CASE 6 examined ${CASE6_EXAMINED} real orchestrator runs (>0, so this case is not vacuous)"
else
  fail "CASE 6 examined ${CASE6_EXAMINED} run(s) — it must exercise at least the defect run and its control, or it proves nothing"
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

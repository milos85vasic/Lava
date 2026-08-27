#!/usr/bin/env bash
# phase-02-test-hermetic.sh — Phase 02 test-category wrapper: hermetic-script
# (T025 of specs/002-build-test-distribute-pipeline/tasks.md).
#
# This project already has ~80 self-contained bash test suites checked in
# under tests/*/ (predating this pipeline feature) — the constitution
# checker's own fixtures, the pre-push hook's own Check-N fixtures, the
# firebase-distribute.sh gate fixtures, the tag-helper matrix-attestation
# fixtures, the codegraph anti-bluff suite, etc. This script is a thin
# wrapper (Decoupled Reusable Architecture: reuse, don't reinvent) that
# invokes every one of them for real, captures each one's real stdout/
# stderr, and records one Evidence Record (category: hermetic-script) per
# suite per specs/002-build-test-distribute-pipeline/contracts/
# evidence-record.schema.json, then anti-bluff-validates each record via
# scripts/pipeline/lib/anti-bluff-validate.sh.
#
# Usage:
#   scripts/pipeline/phase-02-test-hermetic.sh [repo-path] [phase-dir]
#
# With no arguments: repo-path resolves via `git rev-parse --show-toplevel`
# (works from anywhere inside the real repo, matching phase-00-precondition.sh
# / phase-01-build-lava-api-go.sh's existing convention); phase-dir defaults
# to a freshly-created `.lava-ci-evidence/pipeline-runs/<UTC-run-id>/phase-02`
# under repo-path (per data-model.md's Evidence Record path convention:
# "<run_dir>/phase-<NN>/<category>/<test_id>.json"), so this script is
# independently runnable/testable without an orchestrator having already
# created a run directory. An orchestrator that already has a run_id MAY
# pass its own phase-dir explicitly as the second argument instead.
#
# Environment:
#   PHASE02_HERMETIC_TIMEOUT — per-suite timeout in seconds (default: 120).
#     A suite that exceeds this is killed and recorded as FAIL (exit 124),
#     never left to hang the whole wrapper indefinitely.
#
# Enumeration (every "*.sh" file directly under tests/<subdir>/, i.e.
# `find tests -mindepth 2 -maxdepth 2 -name '*.sh'`), EXCLUDING:
#
#   1. Everything under tests/pipeline/** — this feature's OWN hermetic
#      tests (test_phase_00_precondition.sh, test_evidence_and_run_report.sh,
#      test_anti_bluff_validate.sh). Wrapping them here would be circular:
#      they already prove themselves via the harness that built them, and
#      this script itself is one of the things they exist to exercise
#      indirectly through phase-02-test.sh's eventual composition. Excluded
#      per this task's explicit instruction.
#
#   2. Any file literally named `run_all.sh` (tests/ci-sh/run_all.sh,
#      tests/compose-layout/run_all.sh, tests/firebase/run_all.sh,
#      tests/tag-helper/run_all.sh, tests/vm-distro/run_all.sh,
#      tests/vm-images/run_all.sh, tests/vm-signing/run_all.sh). Every one
#      of these is a same-directory aggregator that does nothing but loop
#      over `test_*.sh` in its own directory and re-invoke each
#      (`for t in "$SCRIPT_DIR"/test_*.sh; do bash "$t"; ... done` — see
#      tests/ci-sh/run_all.sh for the canonical shape). This script already
#      enumerates + runs every one of those same constituent test_*.sh files
#      individually; ALSO running the aggregator would produce a second,
#      redundant Evidence Record claiming to cover the exact same checks the
#      individual records already cover — an inflated-coverage-count vector,
#      which is itself a shape of bluff this project's Anti-Bluff Pact
#      exists to prevent. Skipped; the constituents are what get real,
#      distinct Evidence Records.
#
#   3. Any file whose basename begins with `repro_` (added 2026-08-21). A
#      reproduction harness demonstrates that an OPEN defect is still present;
#      it is not a test and must never be counted as coverage. Such a harness
#      typically exits 0 when it SUCCESSFULLY REPRODUCES the defect, so
#      enumerating it here would mint a PASS Evidence Record whose real meaning
#      is "a known bug is still broken" — the exact inversion the Anti-Bluff
#      Pact exists to prevent, and a strictly worse case than the redundant
#      aggregator in rule 2 above (that one over-counts real coverage; this one
#      would manufacture coverage out of a known failure).
#
#      Concrete instance: tests/firebase/repro_mode_both_channel_gap.sh
#      demonstrates that `firebase-distribute.sh --debug-and-release` uploads
#      the RELEASE APK while pointing the §6.AK device-evidence gate at the
#      DEBUG channel. It exits 0 today because the gap is still there.
#
#      Note tests/firebase/run_all.sh does not need this rule: it globs
#      `test_*.sh`, so `repro_*` files are already outside its reach. This
#      wrapper is broader (`-name '*.sh'`), which is why the rule is needed
#      here specifically.
#
#   4. tests/codegraph/lib.sh — a `source`-only shared-helper library
#      (`set -u`, no test logic, no assertions of its own). It is sourced
#      BY tests/codegraph/test_*.sh, not independently invocable as a suite.
#
#   5. tests/vm-distro/boot-and-probe.sh and tests/vm-signing/sign-and-hash.sh
#      — per their own header comments, both "run[] INSIDE each VM in the
#      matrix": they curl real localhost services (proxy/lava-api-go health
#      endpoints) or sign a real APK that only exist once uploaded into a
#      live VM-matrix run. They are not self-contained hermetic suites
#      runnable standalone on this host — invoking them here would just
#      fail against absent infrastructure they were never meant to have on
#      this host, producing a meaningless FAIL that says nothing real about
#      the check they implement inside an actual VM-matrix run.
#
#   6. tests/check-constitution/check_constitution_test.sh — per its own
#      header/inline comments, this suite builds FOUR full filtered
#      repo-copy fixtures per run (rsync-excluding .git/build/.gradle/
#      releases/worktrees) specifically because an earlier `cp -r` version
#      of it exhausted host /tmp and killed unrelated processes, including
#      this very project's own pre-push hook. This task's own instructions
#      call this file out by name: check it first, and skip it if it looks
#      like a heavy full-repo fixture-copy operation whose runtime is
#      unpredictable and which could collide with another agent's
#      concurrent disk usage in this same working tree (a live Gradle build
#      was running in this repo at the time this script was authored).
#      Skipped for exactly that documented reason — reported honestly as a
#      skip, never silently omitted.
#
# Result derivation: PASS iff the suite's real exit code is 0, otherwise
# FAIL (including exit 124 = PHASE02_HERMETIC_TIMEOUT exceeded).
#
# assertion_summary derivation: this project's bash test suites
# overwhelmingly print their own clear final verdict line ("PASS: ...",
# "FAIL: ...", "ALL PASS", "ALL CHECKS PASSED", "N passed, M failed", etc —
# see the suites themselves). This script searches the suite's OWN real
# captured output (tail-first) for the last line matching a verdict-shaped
# pattern and quotes it verbatim in the Evidence Record, rather than writing
# a generic phrase like "ran without crashing" — which
# scripts/pipeline/lib/anti-bluff-validate.sh's rule 1 exists specifically
# to reject as a bluff pattern. If no such line is found, the real last
# non-blank output line is quoted instead; if the suite produced no output
# at all, that fact itself is stated (and the record will correctly be
# REJECTED by anti-bluff-validate.sh's rule 3 for an empty raw_output_ref —
# a suite with zero captured output has zero real evidence behind its exit
# code, which IS a legitimate anti-bluff rejection, not a wrapper bug).
#
# Skips produce a real SKIPPED Evidence Record, not just a console line
# (added 2026-08-21 after a wrapper audit): this header already claimed a
# skip is "reported honestly as a skip, never silently omitted", but the
# only reporting was the `SKIP:` line printed below, which never reaches the
# run report. contracts/evidence-record.schema.json's `result` enum has
# SKIPPED as a first-class outcome precisely so an honest non-execution
# survives into the evidence set and is anti-bluff-validated like any other
# record. Every excluded suite now writes one SKIPPED Evidence Record whose
# assertion_summary quotes this script's own real exclusion reason verbatim
# and whose raw_output_ref is a real captured file naming the skipped script
# and showing its first lines.
#
# Exit codes:
#   0 - at least one suite actually executed, every executed (non-skipped)
#       suite exited 0, AND every Evidence Record (including the SKIPPED
#       ones) was anti-bluff-validated ("validated").
#   1 - at least one suite FAILed, at least one Evidence Record was REJECTED
#       by anti-bluff-validate.sh, or ZERO suites executed at all.
#   2 - usage/precondition error (repo path or tests/ directory missing).
#
# The zero-executed-suites case (added 2026-08-21, same audit) is a real
# failure, not a vacuous pass. Every PASS condition above is satisfied
# vacuously by a run in which the enumeration matched nothing that survived
# exclusion: no suite failed, no record was rejected -- because there were no
# suites and no records. Observed verbatim against a synthetic tests/ tree
# whose only candidate was an excluded aggregator:
#     phase-02-test-hermetic: 0 suite(s) to run, 1 skipped
#       suites run:      0 / PASS: 0 / FAIL: 0
#     WRAPPER EXIT CODE = 0   (0 Evidence Records written)
# A renamed tests/ layout, a broadened exclusion rule, or a `find` that
# silently returns nothing would each have reported a green hermetic category
# having proven nothing. This is the same principle phase-02-test.sh already
# enforces for the phase as a whole ("an empty test phase proves nothing"),
# applied to this one category. Regression coverage:
# tests/pipeline/test_phase_02_hermetic_wrapper.sh CASE 2 + CASE 3.
#
# This script does NOT invoke Gradle, Docker, or Podman anywhere in its own
# logic, and does not shell out to scripts/ci.sh — the enumerated suites
# themselves are static/hermetic checks (grep-based constitution scans,
# fixture-repo-based CLI-contract tests with fake binaries on PATH, etc.),
# confirmed by inspection before this script was authored.

set -uo pipefail
# Deliberately NOT `set -e`: this script's whole job is to keep going after
# an individual suite fails (that failure IS the real, wanted signal for
# that suite's own Evidence Record) — every risky command below is
# explicitly guarded (`if`, direct `$?` capture), never relying on
# inherited errexit to stop the script.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/pipeline/lib/evidence.sh
source "${SCRIPT_DIR}/lib/evidence.sh"
# shellcheck source=scripts/pipeline/lib/anti-bluff-validate.sh
source "${SCRIPT_DIR}/lib/anti-bluff-validate.sh"

REPO_PATH="${1:-}"
PHASE_DIR="${2:-}"

if [[ -z "$REPO_PATH" ]]; then
  REPO_PATH="$(git rev-parse --show-toplevel)"
fi

TESTS_DIR="${REPO_PATH}/tests"

if [[ ! -d "$TESTS_DIR" ]]; then
  echo "phase-02-test-hermetic: precondition failed — no tests/ directory under '${REPO_PATH}'" >&2
  exit 2
fi

if [[ -z "$PHASE_DIR" ]]; then
  RUN_ID="$(date -u +%Y-%m-%dT%H-%M-%SZ)"
  PHASE_DIR="${REPO_PATH}/.lava-ci-evidence/pipeline-runs/${RUN_ID}/phase-02"
fi

mkdir -p "$PHASE_DIR"
RAW_DIR="${PHASE_DIR}/hermetic-script/raw"
mkdir -p "$RAW_DIR"

TIMEOUT_SECS="${PHASE02_HERMETIC_TIMEOUT:-120}"

echo "phase-02-test-hermetic: repo=${REPO_PATH}"
echo "phase-02-test-hermetic: phase_dir=${PHASE_DIR}"
echo "phase-02-test-hermetic: per-suite timeout=${TIMEOUT_SECS}s"

# --- _derive_assertion_summary <raw_output_file> <exit_code> ---------------
# Prints a real, specific assertion_summary derived from the suite's own
# captured output — see the header comment above for the rationale.
_derive_assertion_summary() {
  local raw_file="$1" exit_code="$2"
  local verdict_line=""

  if [[ -f "$raw_file" && -s "$raw_file" ]]; then
    verdict_line="$(grep -aEi '(^|[^A-Za-z])(PASS|FAIL|OK|ALL PASS|ALL CHECKS PASSED|REJECTED|VALIDATED|passed|failed|ERROR|FATAL)([^A-Za-z]|$)' "$raw_file" 2>/dev/null | tail -n 1 || true)"
    if [[ -z "$verdict_line" ]]; then
      verdict_line="$(grep -av '^[[:space:]]*$' "$raw_file" 2>/dev/null | tail -n 1 || true)"
    fi
  fi

  verdict_line="$(printf '%s' "$verdict_line" | tr -d '\r')"
  if [[ ${#verdict_line} -gt 400 ]]; then
    verdict_line="${verdict_line:0:400}...(truncated)"
  fi

  if [[ -z "$verdict_line" ]]; then
    printf 'exit code %s; suite produced no captured stdout/stderr output' "$exit_code"
  else
    printf 'exit code %s; final verdict line from suite output: "%s"' "$exit_code" "$verdict_line"
  fi
}

# --- Enumeration ------------------------------------------------------------
declare -a ALL_CANDIDATES=()
while IFS= read -r -d '' f; do
  ALL_CANDIDATES+=("$f")
done < <(find "${TESTS_DIR}" -mindepth 2 -maxdepth 2 -name '*.sh' -not -path '*/pipeline/*' -not -name 'repro_*.sh' -print0 | sort -z)

declare -a RUN_LIST=()
declare -a SKIPPED_LIST=()
declare -a SKIPPED_REASONS=()

for f in "${ALL_CANDIDATES[@]}"; do
  base="$(basename "$f")"
  skip_reason=""

  if [[ "$base" == "run_all.sh" ]]; then
    skip_reason="aggregator — re-invokes sibling test_*.sh files in the same directory, already enumerated + run individually"
  elif [[ "$f" == "${TESTS_DIR}/codegraph/lib.sh" ]]; then
    skip_reason="source-only shared helper library (set -u, no assertions) — not an independently runnable suite"
  elif [[ "$f" == "${TESTS_DIR}/vm-distro/boot-and-probe.sh" ]]; then
    skip_reason="VM-guest-only helper — curls real localhost services that only exist inside a live VM-matrix run"
  elif [[ "$f" == "${TESTS_DIR}/vm-signing/sign-and-hash.sh" ]]; then
    skip_reason="VM-guest-only helper — signs a real uploaded APK that only exists inside a live VM-matrix run"
  elif [[ "$f" == "${TESTS_DIR}/check-constitution/check_constitution_test.sh" ]]; then
    skip_reason="heavy fixture-copy suite (4x filtered full-repo rsync copies per its own header) — skipped to avoid disk/tmp collision with a concurrently-running Gradle build in this working tree, per this task's explicit instruction"
  fi

  if [[ -n "$skip_reason" ]]; then
    SKIPPED_LIST+=("$f")
    SKIPPED_REASONS+=("$skip_reason")
    continue
  fi

  RUN_LIST+=("$f")
done

echo "phase-02-test-hermetic: ${#RUN_LIST[@]} suite(s) to run, ${#SKIPPED_LIST[@]} skipped"

# --- Execution ---------------------------------------------------------------
PASS_COUNT=0
FAIL_COUNT=0
SKIPPED_COUNT=0
VALIDATED_COUNT=0
REJECTED_COUNT=0
declare -a FAILED_SUITES=()
declare -a REJECTED_RECORDS=()

# --- One real SKIPPED Evidence Record per excluded suite --------------------
# See the header note "Skips produce a real SKIPPED Evidence Record". The
# reason string written here is this script's OWN real exclusion reason (the
# same one printed on the console), and the raw file quotes the real skipped
# script's own first lines, so the record is specific, falsifiable, and
# survives anti-bluff-validate.sh's rules exactly like a PASS or FAIL record.
for i in "${!SKIPPED_LIST[@]}"; do
  skip_path="${SKIPPED_LIST[$i]}"
  skip_reason="${SKIPPED_REASONS[$i]}"
  skip_rel="${skip_path#$REPO_PATH/}"
  echo "  SKIP: ${skip_rel} — ${skip_reason}"

  skip_raw="${RAW_DIR}/$(printf '%s' "$skip_rel" | tr '/' '_').skipped.log"
  {
    echo "# suite NOT executed by phase-02-test-hermetic.sh"
    echo "# path: ${skip_rel}"
    echo "# exclusion reason: ${skip_reason}"
    echo "# command that WOULD have run: cd '${REPO_PATH}' && timeout ${TIMEOUT_SECS} bash '${skip_rel}'"
    echo "# --- first 20 lines of the real, un-executed script ---"
    head -n 20 "$skip_path" 2>/dev/null || echo "(script unreadable)"
  } > "$skip_raw"

  skip_summary="Genuinely did not execute: '${skip_rel}' is excluded by this wrapper's own enumeration rule — ${skip_reason}"

  SKIPPED_COUNT=$((SKIPPED_COUNT + 1))

  skip_record=""
  if ! skip_record="$(write_evidence_record "$PHASE_DIR" "$skip_rel" "hermetic-script" \
      "cd '${REPO_PATH}' && timeout ${TIMEOUT_SECS} bash '${skip_rel}'" "SKIPPED" \
      "$skip_summary" "$skip_raw")"; then
    echo "  ERROR: write_evidence_record failed for skipped suite ${skip_rel}" >&2
    REJECTED_COUNT=$((REJECTED_COUNT + 1))
    REJECTED_RECORDS+=("${skip_rel} (evidence-write failure, skip record)")
    continue
  fi

  if validate_evidence_record "$skip_record" >/dev/null 2>&1; then
    VALIDATED_COUNT=$((VALIDATED_COUNT + 1))
  else
    REJECTED_COUNT=$((REJECTED_COUNT + 1))
    REJECTED_RECORDS+=("${skip_rel} ($(jq -r '.anti_bluff_status' "$skip_record" 2>/dev/null || echo REJECTED), skip record)")
  fi
done

for f in "${RUN_LIST[@]}"; do
  rel_path="${f#$REPO_PATH/}"
  sanitized_log_name="$(printf '%s' "$rel_path" | tr '/' '_')"
  raw_log="${RAW_DIR}/${sanitized_log_name}.log"
  cmd_str="cd '${REPO_PATH}' && timeout ${TIMEOUT_SECS} bash '${rel_path}'"

  echo "phase-02-test-hermetic: RUN ${rel_path}"

  ( cd "$REPO_PATH" && timeout "${TIMEOUT_SECS}" bash "$f" ) > "$raw_log" 2>&1
  exit_code=$?

  if [[ $exit_code -eq 0 ]]; then
    result="PASS"
    PASS_COUNT=$((PASS_COUNT + 1))
  else
    result="FAIL"
    FAIL_COUNT=$((FAIL_COUNT + 1))
    FAILED_SUITES+=("${rel_path} (exit ${exit_code})")
  fi

  assertion_summary="$(_derive_assertion_summary "$raw_log" "$exit_code")"

  record_path=""
  if ! record_path="$(write_evidence_record "$PHASE_DIR" "$rel_path" "hermetic-script" "$cmd_str" "$result" "$assertion_summary" "$raw_log")"; then
    echo "  ERROR: write_evidence_record failed for ${rel_path}" >&2
    REJECTED_COUNT=$((REJECTED_COUNT + 1))
    REJECTED_RECORDS+=("${rel_path} (evidence-write failure)")
    continue
  fi

  if validate_evidence_record "$record_path" >/dev/null 2>&1; then
    VALIDATED_COUNT=$((VALIDATED_COUNT + 1))
    echo "  -> result=${result} anti_bluff=validated record=${record_path#$REPO_PATH/}"
  else
    REJECTED_COUNT=$((REJECTED_COUNT + 1))
    REJECTED_RECORDS+=("${rel_path} ($(jq -r '.anti_bluff_status' "$record_path" 2>/dev/null || echo REJECTED))")
    echo "  -> result=${result} anti_bluff=REJECTED record=${record_path#$REPO_PATH/}"
  fi
done

# --- Summary -------------------------------------------------------------------
echo ""
echo "phase-02-test-hermetic: SUMMARY"
echo "  suites run:      ${#RUN_LIST[@]}"
echo "  suites skipped:  ${#SKIPPED_LIST[@]}"
echo "  PASS:            ${PASS_COUNT}"
echo "  FAIL:            ${FAIL_COUNT}"
echo "  SKIPPED (excluded suites, one honest SKIPPED Evidence Record each): ${SKIPPED_COUNT}"
echo "  anti_bluff validated: ${VALIDATED_COUNT}"
echo "  anti_bluff REJECTED:  ${REJECTED_COUNT}"

if [[ ${#FAILED_SUITES[@]} -gt 0 ]]; then
  echo "  Failed suites:"
  for s in "${FAILED_SUITES[@]}"; do
    echo "    - ${s}"
  done
fi

if [[ ${#REJECTED_RECORDS[@]} -gt 0 ]]; then
  echo "  Rejected Evidence Records:"
  for r in "${REJECTED_RECORDS[@]}"; do
    echo "    - ${r}"
  done
fi

if [[ ${#RUN_LIST[@]} -eq 0 ]]; then
  echo "" >&2
  echo "phase-02-test-hermetic: FAILED — zero suites actually executed." >&2
  echo "  ${#ALL_CANDIDATES[@]} candidate file(s) were enumerated under ${TESTS_DIR#$REPO_PATH/}/ and" >&2
  echo "  ${#SKIPPED_LIST[@]} of them were excluded, leaving nothing to run. A hermetic-script" >&2
  echo "  category that executed no suite at all proves nothing — reporting PASS here would be" >&2
  echo "  vacuous (no suite failed only because no suite ran). Investigate the enumeration:" >&2
  echo "  find '${TESTS_DIR}' -mindepth 2 -maxdepth 2 -name '*.sh' -not -path '*/pipeline/*' -not -name 'repro_*.sh'" >&2
  exit 1
fi

if [[ $FAIL_COUNT -gt 0 || $REJECTED_COUNT -gt 0 ]]; then
  exit 1
fi

exit 0

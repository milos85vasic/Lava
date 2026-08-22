#!/usr/bin/env bash
# scripts/pipeline-build-test-distribute.sh — Top-level orchestrator for the
# local build-test-distribute pipeline (specs/002-build-test-distribute-pipeline).
#
# ---------------------------------------------------------------------------
# What is wired in RIGHT NOW, and what is honestly not
# ---------------------------------------------------------------------------
# WIRED (T038): the five phase scripts that exist and have each been proven
# independently —
#   precondition  -> scripts/pipeline/phase-00-precondition.sh   (FR-000)
#   build         -> scripts/pipeline/phase-01-build.sh          (US1)
#   test          -> scripts/pipeline/phase-02-test.sh           (US1)
#   install_boot  -> scripts/pipeline/phase-03-install-boot.sh   (US2, FR-006/7)
#   live_verify   -> scripts/pipeline/phase-04-live-verify-api.sh      (US2)
#                    scripts/pipeline/phase-04-live-verify-api-app.sh  (US2)
#                    (both, in that order — see the registry comment)
#
# NOT WIRED, and deliberately so — do not read the absence as an oversight:
#   changelog / distribute (phase-05a / phase-05) — blocked behind the
#     constitutional amendment tasks T040/T041 (CLAUDE.md §6.AA two-stage
#     distribute + Seventh Law clause 3). Until that amendment is reviewed
#     and merged, this orchestrator MUST NOT be able to distribute anything.
#   docs refresh (phase-06) — depends on the same reviewed change landing.
#   closure (phase-07) — blocked behind T048/T049 (the Decoupled Reusable
#     Architecture rule's "submodule fetch/pull is an EXPLICIT operator
#     action, never automatic" carve-out) plus T054's dedicated review gate.
#
# The `--until` option below therefore only accepts phases that genuinely
# exist. Asking for a phase that is not wired is a usage error (exit 2), not
# a silent no-op — a pipeline that silently skips the phase you asked for is
# the same bluff class this whole feature exists to prevent.
#
# ---------------------------------------------------------------------------
# Live-verify scope (updated 2026-08-21 — T037 landed)
# ---------------------------------------------------------------------------
# `live_verify` now covers BOTH live surfaces, and a green result means both
# were proven:
#   - the running lava-api-go service (phase-04-live-verify-api.sh), and
#   - the :api-app debug APK installed onto a Containers-submodule emulator
#     per CLAUDE.md §6.AH, driving its real boot-and-serve Challenge
#     (phase-04-live-verify-api-app.sh).
# The api-app half proves §6.AH by CHECKED FACT rather than by log-line
# assertion: a poller samples `podman ps --filter name=lava-emu` throughout
# the run, and if no running emulator container was ever observed, its
# provenance record is FAIL. It also requires two independent sources to
# agree — Gradle's own host-side JUnit XML AND the Containers attestation row
# — and treats disagreement between them as a FAIL in itself.
#
# Remaining honest caveat: the api-app half runs ONE AVD (API 34 phone),
# because that is the only emulator image cached on this host. That is a
# live-verification, NOT the §6.AE.2 release gate matrix.
#
# ---------------------------------------------------------------------------
# Usage
# ---------------------------------------------------------------------------
#   scripts/pipeline-build-test-distribute.sh [options] [repo-path-override]
#
# Options:
#   --until <phase>   Stop after <phase> completes successfully. One of:
#                     precondition | build | test | install_boot | live_verify
#                     Default: live_verify (the furthest wired phase).
#                     This is what makes quickstart.md's per-user-story
#                     slices runnable: `--until test` is the US1 slice,
#                     `--until live_verify` is US1+US2.
#   --skip <phase>[,<phase>...]
#                     Do not run the named phase(s). `precondition` may NOT
#                     be skipped — it is the FR-000 safety boundary, and a
#                     pipeline that can be talked out of checking its own
#                     preconditions has no safety boundary at all.
#   -h, --help        Print this usage block and exit 0.
#
# The optional positional repo-path override is forwarded to every phase
# script as its own `[repo-path]` argument (they all share the signature
# `<run_id> [repo-path]`). It controls which repository the phases INSPECT.
# It does NOT relocate the Pipeline Run Report: per
# scripts/pipeline/lib/run-report.sh's documented contract, every
# run-report function resolves ".lava-ci-evidence/pipeline-runs/<run_id>/
# report.json" relative to the CURRENT WORKING DIRECTORY, and this
# project's scripts are always invoked from the repo root (same convention
# as scripts/ci.sh, scripts/tag.sh).
#
# ---------------------------------------------------------------------------
# Report finalization
# ---------------------------------------------------------------------------
# On EVERY exit path after the report is initialized — success, phase
# failure, or an interrupt — this script runs:
#   recompute_evidence_summary <run_id>   then   finalize_run_report <run_id>
#
# The recompute step is not optional bookkeeping. data-model.md's Validation
# rule makes `outcome: "PASS"` conditional on
# `evidence_summary.rejected_by_anti_bluff == 0`, and finalize_run_report
# implements that rule literally — but the counter it reads is seeded to 0
# by init_run_report and is only ever populated by recompute_evidence_summary
# scanning the real Evidence Records on disk. Skipping the recompute makes
# the anti-bluff half of the outcome rule a silent no-op, which is exactly
# the failure tests/pipeline/test_run_report_evidence_summary.sh CASE 3
# exists to catch. Do not "optimize" it away.
#
# ---------------------------------------------------------------------------
# Exit codes
# ---------------------------------------------------------------------------
#   0 - every phase that ran reported PASS, and the finalized report's
#       `outcome` is "PASS".
#   1 - at least one phase genuinely failed, OR the finalized `outcome` is
#       "FAIL" (which includes the case where every phase passed but at
#       least one Evidence Record was REJECTED by anti-bluff validation).
#   2 - usage error, or the FR-000 precondition check failed (propagated
#       verbatim from phase-00-precondition.sh's own exit code, never
#       hardcoded, so a future change to its contract cannot go stale here).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# --- Phase registry --------------------------------------------------------
# Ordered. Each entry: <phase-name>|<script-basename>|<self-appends-result?>
#
# "self-appends-result" records whether the phase script calls
# append_phase_result itself. phase-00-precondition.sh does NOT (it predates
# the run-report wiring and is deliberately usable standalone by its own
# hermetic test), so this orchestrator appends on its behalf. Every other
# phase script appends its own entry, and this orchestrator MUST NOT append
# a second one — a duplicated phases[] entry would corrupt the outcome
# computation (which requires EVERY entry to be PASS).
# The script field may name MORE THAN ONE script, comma-separated. They run in
# listed order, and the phase halts at the first one that fails. This exists
# because "live_verify" is genuinely two independent surfaces — the running
# lava-api-go service and the :api-app build on a containerized emulator — and
# collapsing them into one script would have forced one to reimplement the
# other's orchestration. Both append their own "live_verify" entry to phases[];
# the report schema permits that (phases[] declares no uniqueItems), and it is
# the honest shape: BOTH must pass for the phase to have proven what its name
# claims, and finalize_run_report requires EVERY phases[] entry to be PASS.
PHASES=(
  "precondition|phase-00-precondition.sh|no"
  "build|phase-01-build.sh|yes"
  "test|phase-02-test.sh|yes"
  "install_boot|phase-03-install-boot.sh|yes"
  "live_verify|phase-04-live-verify-api.sh,phase-04-live-verify-api-app.sh|yes"
)

_phase_names() {
  local entry
  for entry in "${PHASES[@]}"; do
    printf '%s\n' "${entry%%|*}"
  done
}

_is_known_phase() {
  local candidate="$1" name
  while IFS= read -r name; do
    [[ "$name" == "$candidate" ]] && return 0
  done < <(_phase_names)
  return 1
}

_usage() {
  sed -n '2,100p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

# --- Argument parsing ------------------------------------------------------
UNTIL_PHASE="live_verify"
SKIP_LIST=""
REPO_PATH_OVERRIDE=""

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    -h|--help)
      _usage
      exit 0
      ;;
    --until)
      if [[ "$#" -lt 2 ]]; then
        echo "pipeline-build-test-distribute: --until requires a phase name" >&2
        exit 2
      fi
      UNTIL_PHASE="$2"
      shift 2
      ;;
    --skip)
      if [[ "$#" -lt 2 ]]; then
        echo "pipeline-build-test-distribute: --skip requires a phase name (or comma-separated list)" >&2
        exit 2
      fi
      SKIP_LIST="$2"
      shift 2
      ;;
    --*)
      echo "pipeline-build-test-distribute: unknown option '$1'" >&2
      echo "run with --help for usage" >&2
      exit 2
      ;;
    *)
      if [[ -n "$REPO_PATH_OVERRIDE" ]]; then
        echo "pipeline-build-test-distribute: unexpected extra argument '$1' (only one repo-path override is accepted)" >&2
        exit 2
      fi
      REPO_PATH_OVERRIDE="$1"
      shift
      ;;
  esac
done

if ! _is_known_phase "$UNTIL_PHASE"; then
  echo "pipeline-build-test-distribute: --until '${UNTIL_PHASE}' is not a wired phase." >&2
  echo "Wired phases (in order): $(_phase_names | tr '\n' ' ')" >&2
  echo "The distribute / docs / closure phases are deliberately NOT wired yet — they are blocked behind the constitutional amendment tasks T040/T041 and T048/T049. See this script's header." >&2
  exit 2
fi

declare -A SKIP_SET=()
if [[ -n "$SKIP_LIST" ]]; then
  IFS=',' read -r -a _skips <<< "$SKIP_LIST"
  for s in "${_skips[@]}"; do
    s="$(printf '%s' "$s" | tr -d '[:space:]')"
    [[ -z "$s" ]] && continue
    if ! _is_known_phase "$s"; then
      echo "pipeline-build-test-distribute: --skip '${s}' is not a wired phase." >&2
      echo "Wired phases (in order): $(_phase_names | tr '\n' ' ')" >&2
      exit 2
    fi
    if [[ "$s" == "precondition" ]]; then
      echo "pipeline-build-test-distribute: refusing to skip 'precondition' — it is the FR-000 safety boundary (clean tree on master). A pipeline that can be talked out of checking its own preconditions has no safety boundary at all." >&2
      exit 2
    fi
    SKIP_SET["$s"]=1
  done
fi

# --- Preflight: every wired phase script must actually exist ---------------
# Checked BEFORE anything runs, so a missing script is reported up front
# rather than four phases and several minutes into a real build.
_missing=0
for entry in "${PHASES[@]}"; do
  script_field="${entry#*|}"; script_field="${script_field%%|*}"
  IFS=',' read -r -a _scripts <<< "$script_field"
  for script_name in "${_scripts[@]}"; do
    if [[ ! -f "${SCRIPT_DIR}/pipeline/${script_name}" ]]; then
      echo "pipeline-build-test-distribute: wired phase script not found: ${SCRIPT_DIR}/pipeline/${script_name}" >&2
      _missing=1
    fi
  done
done
if [[ "$_missing" -ne 0 ]]; then
  exit 2
fi

if [[ -n "$REPO_PATH_OVERRIDE" ]]; then
  REPO_ROOT="$REPO_PATH_OVERRIDE"
else
  REPO_ROOT="$(git rev-parse --show-toplevel)"
fi

# shellcheck source=scripts/pipeline/lib/run-report.sh
source "$SCRIPT_DIR/pipeline/lib/run-report.sh"
# shellcheck source=scripts/pipeline/lib/evidence.sh
source "$SCRIPT_DIR/pipeline/lib/evidence.sh"

run_id="$(date -u +%Y-%m-%dT%H-%M-%SZ)"
commit_sha="$(git rev-parse HEAD)"

init_run_report "$run_id" "$commit_sha" >/dev/null
REPORT_INITIALIZED=1

RUN_DIR=".lava-ci-evidence/pipeline-runs/${run_id}"

# _close_report — recompute evidence_summary from the real records on disk,
# then finalize. Runs on EVERY exit path once the report exists (including
# an interrupt), because a run directory containing a report.json that was
# never finalized is indistinguishable at rest from one that is still
# running. Prints the finalized outcome.
_close_report() {
  [[ "${REPORT_INITIALIZED:-0}" -eq 1 ]] || return 0
  recompute_evidence_summary "$run_id" >/dev/null 2>&1 || \
    echo "pipeline-build-test-distribute: WARNING — could not recompute evidence_summary; the finalized outcome may not reflect rejected evidence" >&2
  finalize_run_report "$run_id" >/dev/null 2>&1 || \
    echo "pipeline-build-test-distribute: WARNING — could not finalize report.json" >&2
}

trap '_close_report' INT TERM

echo "pipeline-build-test-distribute: run_id=${run_id} commit_sha=${commit_sha} repo=${REPO_ROOT}"
echo "pipeline-build-test-distribute: running phases up to and including '${UNTIL_PHASE}'"
echo ""

_final_exit=0
_halted_at=""

for entry in "${PHASES[@]}"; do
  phase_name="${entry%%|*}"
  rest="${entry#*|}"
  script_field="${rest%%|*}"
  self_appends="${rest##*|}"

  if [[ -n "${SKIP_SET[$phase_name]:-}" ]]; then
    echo "pipeline-build-test-distribute: SKIPPING phase '${phase_name}' (--skip)"
    if [[ "$phase_name" == "$UNTIL_PHASE" ]]; then
      break
    fi
    continue
  fi

  # A phase may own more than one script (see the PHASES registry comment).
  # They run in listed order and the phase halts at the FIRST failure — a
  # later script must never get the chance to append a PASS entry for a phase
  # whose earlier half already failed.
  IFS=',' read -r -a phase_scripts <<< "$script_field"

  phase_start_epoch="$(date +%s)"
  phase_exit=0
  failed_script=""

  for script_name in "${phase_scripts[@]}"; do
    phase_dir="${RUN_DIR}/phase-${script_name:6:2}"
    mkdir -p -- "$phase_dir"
    phase_log="${phase_dir}/${script_name%.sh}-orchestrator.log"

    echo "pipeline-build-test-distribute: === phase '${phase_name}' -> ${script_name} ==="

    set +e
    if [[ "$phase_name" == "precondition" ]]; then
      # phase-00 takes only the repo-path argument (no run_id) — it predates
      # the run-report wiring and is standalone by design.
      "$SCRIPT_DIR/pipeline/${script_name}" "$REPO_PATH_OVERRIDE" 2>&1 | tee "$phase_log"
      script_exit="${PIPESTATUS[0]}"
    else
      "$SCRIPT_DIR/pipeline/${script_name}" "$run_id" "$REPO_PATH_OVERRIDE" 2>&1 | tee "$phase_log"
      script_exit="${PIPESTATUS[0]}"
    fi
    set -e

    if [[ "$script_exit" -ne 0 ]]; then
      phase_exit="$script_exit"
      failed_script="$script_name"
      break
    fi
  done

  phase_end_epoch="$(date +%s)"
  duration_seconds=$((phase_end_epoch - phase_start_epoch))

  if [[ "$self_appends" == "no" ]]; then
    if [[ "$phase_exit" -eq 0 ]]; then
      append_phase_result "$run_id" "$phase_name" "PASS" "$duration_seconds" "$phase_dir" >/dev/null
    else
      append_phase_result "$run_id" "$phase_name" "FAIL" "$duration_seconds" "$phase_dir" >/dev/null
    fi
  fi

  if [[ "$phase_exit" -ne 0 ]]; then
    echo "" >&2
    echo "pipeline-build-test-distribute: phase '${phase_name}' FAILED (exit ${phase_exit}) in '${failed_script}' — halting. Full output: ${phase_log}" >&2

    # Self-diagnosis for one specific, very confusing failure mode.
    #
    # init_run_report runs BEFORE the precondition check (deliberately — a
    # refusal to start is itself a run outcome and must be recorded in a
    # report). That means this run's own evidence directory already exists
    # on disk by the time phase-00 evaluates FR-000's clean-tree rule. In
    # this repository that is harmless, because .gitignore line 59 ignores
    # ".lava-ci-evidence/pipeline-runs/" (task T003 exists precisely for
    # this). But if that ignore rule is ever removed — or the pipeline is
    # pointed at a repository that lacks it — the pipeline dirties the very
    # tree it is about to test, and then refuses to start FOREVER, with a
    # "working tree is not clean" message that gives no hint that the
    # pipeline itself is the thing making it dirty. Observed for real on
    # 2026-08-21 against a fixture repo with no such ignore rule.
    #
    # So: if the precondition failed AND this run's own directory is what
    # git is reporting as untracked, say so explicitly rather than letting
    # the operator hunt for it.
    if [[ "$phase_name" == "precondition" ]]; then
      _dirt="$(git -C "$REPO_ROOT" status --porcelain --untracked-files=all 2>/dev/null | grep -F ".lava-ci-evidence/pipeline-runs" || true)"
      if [[ -n "$_dirt" ]]; then
        echo "" >&2
        echo "pipeline-build-test-distribute: DIAGNOSIS — the working tree git reports as dirty includes THIS PIPELINE'S OWN evidence directory:" >&2
        printf '%s\n' "$_dirt" | sed 's/^/    /' >&2
        echo "  '.lava-ci-evidence/pipeline-runs/' must be listed in that repository's .gitignore, or the pipeline dirties the tree it is about to test and can never satisfy FR-000. See task T003." >&2
      fi
    fi

    _halted_at="$phase_name"
    # The FR-000 precondition failure keeps its own exit code 2 (a refusal to
    # start is categorically different from a phase that ran and failed);
    # every other phase failure surfaces as 1.
    if [[ "$phase_name" == "precondition" ]]; then
      _final_exit="$phase_exit"
    else
      _final_exit=1
    fi
    break
  fi

  echo "pipeline-build-test-distribute: phase '${phase_name}' PASSED (${duration_seconds}s, ${#phase_scripts[@]} script(s))"
  echo ""

  if [[ "$phase_name" == "$UNTIL_PHASE" ]]; then
    echo "pipeline-build-test-distribute: reached --until target '${UNTIL_PHASE}'; stopping here as requested."
    break
  fi
done

_close_report
trap - INT TERM

report_path="${RUN_DIR}/report.json"
outcome="$(python3 -c "import json,sys; print(json.load(open(sys.argv[1])).get('outcome','UNKNOWN'))" "$report_path" 2>/dev/null || echo UNKNOWN)"

echo "==============================================================="
echo "pipeline-build-test-distribute: RUN SUMMARY"
echo "  run_id:      ${run_id}"
echo "  commit_sha:  ${commit_sha}"
echo "  report:      ${report_path}"
echo "  outcome:     ${outcome}"
if [[ -n "$_halted_at" ]]; then
  echo "  halted at:   ${_halted_at}"
fi
echo "==============================================================="

# The finalized outcome is authoritative. A run whose phases all exited 0 but
# whose outcome is FAIL (because an Evidence Record was REJECTED by
# anti-bluff validation) MUST NOT exit 0 — that is the precise case
# data-model.md's Validation rule exists for.
if [[ "$_final_exit" -eq 0 && "$outcome" != "PASS" ]]; then
  echo "pipeline-build-test-distribute: every phase exited 0 but the finalized outcome is '${outcome}' — see evidence_summary.rejected_by_anti_bluff in ${report_path}" >&2
  _final_exit=1
fi

exit "$_final_exit"

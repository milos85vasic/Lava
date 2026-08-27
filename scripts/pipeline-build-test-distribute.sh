#!/usr/bin/env bash
# scripts/pipeline-build-test-distribute.sh — Top-level orchestrator for the
# local build-test-distribute pipeline (specs/002-build-test-distribute-pipeline).
#
# ---------------------------------------------------------------------------
# What is wired in RIGHT NOW, and what is honestly not
# ---------------------------------------------------------------------------
# WIRED (T038, extended by T046/T057): the eight phase scripts that exist and
# have each been proven independently —
#   precondition     -> scripts/pipeline/phase-00-precondition.sh   (FR-000)
#   build            -> scripts/pipeline/phase-01-build.sh          (US1)
#   test             -> scripts/pipeline/phase-02-test.sh           (US1)
#   install_boot     -> scripts/pipeline/phase-03-install-boot.sh   (US2, FR-006/7)
#   live_verify      -> scripts/pipeline/phase-04-live-verify-api.sh      (US2)
#                       scripts/pipeline/phase-04-live-verify-api-app.sh  (US2)
#                       (both, in that order — see the registry comment)
#   changelog_entry  -> scripts/pipeline/phase-05a-changelog-entry.sh (US3, T042)
#   distribute       -> scripts/pipeline/phase-05-distribute.sh      (US3, T043)
#                       GATE ONLY — it cannot distribute. See below.
#   docs_refresh     -> scripts/pipeline/phase-06-docs.sh            (US3, T044/T045)
#
# NOT WIRED, and deliberately so — do not read the absence as an oversight:
#   closure (phase-07) — blocked behind T054 ALONE: the mandatory review of
#     scripts/advance-all-submodules.sh (plan.md Human Checkpoint #2), which
#     has run four rounds, returned APPROVE-WITH-FIXES every time, and has
#     never returned a clean approval. T055 (the phase script itself) depends
#     on it. scripts/pipeline/phase-07-closure.sh does not exist.
#
#     CORRECTED 2026-08-26 — this note used to name T048/T049 (the Decoupled
#     Reusable Architecture rule's "submodule fetch/pull is an EXPLICIT
#     operator action, never automatic" carve-out) as a co-blocker. THEY ARE
#     NOT BLOCKERS: both landed 2026-08-23 under explicit operator approval
#     (tasks.md T048/T049, both [x]), and root CLAUDE.md now carries the
#     `Automated Pipeline Pin-Advance Path` subsection with conditions
#     (A)-(F). T040/T041 landed the same day. No constitutional amendment
#     blocks this phase any more; the review does.
#
#     THE SAME STALE CITATION IS STILL LIVE IN THE `--until` ERROR MESSAGE
#     BELOW (the echo at line 375), which is operator-facing output rather
#     than a comment and was therefore left untouched by the comment-only
#     correction pass. It must be fixed in the commit that wires `closure`.
#
# The `--until` option below therefore only accepts phases that genuinely
# exist. Asking for a phase that is not wired is a usage error (exit 2), not
# a silent no-op — a pipeline that silently skips the phase you asked for is
# the same bluff class this whole feature exists to prevent.
#
# ---------------------------------------------------------------------------
# R-004 ORDERING: changelog BEFORE the distribute gate, broader docs AFTER
# ---------------------------------------------------------------------------
# scripts/firebase-distribute.sh treats the CHANGELOG.md entry (its Gate 2)
# and the per-version `.lava-ci-evidence/distribute-changelog/<channel>/
# <version>-<code>.md` snapshot (its Gate 3) as PRE-EXISTING INPUTS it
# verifies, never as things it authors. So `changelog_entry` runs BEFORE
# `distribute`. spec.md's User Story 3 groups "distribute" and "refresh
# documentation" together, but the dependency direction for the CHANGELOG
# specifically is inverted from that naive reading. The broader documentation
# refresh (`docs_refresh`) has no such inversion and runs last, per R-004.
#
# ---------------------------------------------------------------------------
# THE DISTRIBUTE PHASE IS A REFUSAL GATE THAT CANNOT DISTRIBUTE
# ---------------------------------------------------------------------------
# scripts/pipeline/phase-05-distribute.sh is the §6.AA clause 8 refusal gate
# and TODAY IT IS ONLY THAT. It never invokes scripts/firebase-distribute.sh,
# it writes no Distribution Record, and it mutates report.json in no way. Its
# own header defines exactly three exit codes:
#
#   0 - a distribution completed. RESERVED, and unreachable today.
#   2 - GATE REFUSED (one or more of FR-009 / §6.AA clause 8 (A)-(H) failed,
#       or a usage error). Refusal is the default.
#   3 - GATE QUALIFIED, and the distribute step is not implemented.
#
# EXIT 3 IS NEITHER A SUCCESS NOR A FAILURE, and this orchestrator must not
# pretend otherwise in either direction. The run report's `phases[].result`
# enum is PASS | FAIL | SKIPPED, and NONE of the three means what exit 3
# means:
#
#   * PASS would put a `distribute: PASS` entry into the report for a run
#     that distributed nothing. SC-008 tells an auditor to read report.json
#     FIRST; a reader of that entry would conclude a distribution happened.
#     Manufacturing the appearance of a distribution that did not occur is
#     the §6.Z / §6.AK bluff class at the gate layer.
#   * FAIL would say the gate failed. It did not — it qualified.
#   * SKIPPED is the closest word, but finalize_run_report treats a SKIPPED
#     phase as NOT-PASS (deliberately, and documented as such in its own
#     docblock), so recording it would finalize EVERY otherwise-perfect run
#     to `outcome: FAIL` and exit non-zero. A pipeline whose exit code is 1
#     for a good run and 1 for a broken one has no exit code at all, and an
#     always-red gate is ignored within a week — which is how a real failure
#     then ships unnoticed.
#
# So on exit 3 this orchestrator records NO `phases[]` entry for `distribute`,
# does not halt, and DISCLOSES the fact loudly on stdout and in the run
# summary. The absence is the honest record: nothing was distributed, so
# there is no distribution result to report, and `distributions` in
# report.json stays empty — that empty array is the machine-readable half of
# the same statement.
#
# EVERY OTHER EXIT CODE FROM THAT GATE STILL FAILS THE RUN, and is recorded
# as `distribute: FAIL` by this orchestrator (the gate appends nothing of its
# own, so if this orchestrator does not record it, nobody does). That
# includes exit 2 (the gate's own default refusal) and any code the gate
# never defined — an unmodelled state is read as failure, never as consent.
# Regression coverage for all of it:
# tests/pipeline/test_pipeline_full_sequence_wiring.sh CASES C-F.
#
# AND A FAILURE ANYWHERE IN A GATE PHASE OUTRANKS A SCRIPT THAT QUALIFIED.
# A phase may name more than one script; `distribute` names one today, but the
# registry supports more and `live_verify` already uses that shape. When an
# earlier script exits 3 and a later one fails, the phase FAILED — it is
# recorded `FAIL` and the run halts. The qualified-no-op path is reachable
# ONLY when every script in the phase returned without failing. Checking the
# no-op first (as this did until the audit of 2026-08-25) made the failure
# branch unreachable and finalized such a run to `outcome: "PASS"` while the
# process exit code said 1 — the report an auditor is told to read first was
# the one that lied. Regression coverage:
# tests/pipeline/test_orchestrator_gate_and_registry_audit.sh CASES A and B.
#
# ---------------------------------------------------------------------------
# THE DEFAULT RUN NOW WRITES TO THE REPOSITORY
# ---------------------------------------------------------------------------
# `changelog_entry` writes a CHANGELOG.md entry and a per-version snapshot
# file; `docs_refresh` applies research.md R-002's stale-documentation fixes
# and regenerates the .html/.pdf siblings of every .md it changed. Both are
# what FR-013 / SC-006 ("zero manual documentation follow-up required") ask
# for, and both are now inside the default `--until docs_refresh`.
#
# Two consequences worth stating plainly rather than discovering:
#   - A completed default run leaves the working tree DIRTY on purpose. The
#     phase that commits those changes is `closure`, which is not wired.
#     Until it is, committing is a human act.
#   - Because FR-000 requires a clean tree, a SECOND run started before those
#     changes are committed or discarded will refuse at `precondition`. That
#     refusal is correct, not a bug.
# `--until live_verify` reproduces the pre-T046 default exactly, and
# `--skip changelog_entry,distribute,docs_refresh` does the same for a run
# that should touch nothing.
#
# Because the operator who types no flags at all never reads this header, a
# run that will actually reach one of those two phases ALSO announces it on
# stdout before the first phase starts. The notice is computed from the
# phases the run will genuinely reach — `--until` short of them, or `--skip`
# of them, suppresses it, because a warning that is wrong teaches the reader
# to ignore the next one. Regression coverage:
# tests/pipeline/test_orchestrator_gate_and_registry_audit.sh CASE H.
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
#                     precondition | build | test | install_boot |
#                     live_verify | changelog_entry | distribute |
#                     docs_refresh
#                     Default: docs_refresh (the furthest wired phase).
#                     This is what makes quickstart.md's per-user-story
#                     slices runnable: `--until test` is the US1 slice,
#                     `--until live_verify` is US1+US2, `--until
#                     docs_refresh` is US1+US2+US3.
#   --skip <phase>[,<phase>...]
#                     Do not run the named phase(s). `precondition` may NOT
#                     be skipped — it is the FR-000 safety boundary, and a
#                     pipeline that can be talked out of checking its own
#                     preconditions has no safety boundary at all.
#   -h, --help        Print this usage block and exit 0.
#
# The optional positional repo-path override names THE REPOSITORY THIS RUN
# OPERATES ON, whole. It is forwarded to every phase script as its own
# `[repo-path]` argument (they all share the signature `<run_id> [repo-path]`),
# it is the repository whose HEAD becomes the report's `commit_sha`, and it is
# the repository the run's evidence directory is written inside. This script
# chdir's to it before creating anything, so every relative
# ".lava-ci-evidence/..." path — this file's, lib/run-report.sh's, and each
# phase script's — resolves under that one repository.
#
# CORRECTED 2026-08-26. This block, and contracts/cli-contract.md's row for
# `[repo-path]`, both used to say the override "does not relocate the run
# report, which is always written relative to the current working directory."
# That was not a design; it was a defect with a docstring. A run pointed at
# repository X wrote a report into repository Y, stamped with Y's HEAD and an
# outcome of PASS — a verdict about X, filed under a commit of Y that nothing
# had tested, in the exact place §6.Z / §6.AK / §6.AA clause 8 look for
# same-SHA evidence. See the long note above the chdir for the measurement and
# for the four places in this codebase that already assumed otherwise.
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
#       `outcome` is "PASS". A `distribute` gate that QUALIFIED without
#       distributing anything (its exit 3) does not change this, and does
#       not claim a distribution happened — see the section above.
#   1 - at least one phase genuinely failed, OR the finalized `outcome` is
#       "FAIL" (which includes the case where every phase passed but at
#       least one Evidence Record was REJECTED by anti-bluff validation).
#   2 - usage error, or the FR-000 precondition check failed (propagated
#       verbatim from phase-00-precondition.sh's own exit code, never
#       hardcoded, so a future change to its contract cannot go stale here).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# --- Phase registry --------------------------------------------------------
# Ordered. Each entry: <phase-name>|<script-basename>[,<script-basename>...]|<result-mode>
#
# <phase-name> MUST be one of the values in the phases[].name enum of
# specs/002-build-test-distribute-pipeline/contracts/pipeline-run-report.schema.json.
# The schema is the closed set; this registry does not get to invent names.
#
# <result-mode> says WHO records the phase's phases[] entry:
#
#   yes  — the phase script calls append_phase_result itself. This
#          orchestrator MUST NOT append a second one; a duplicated phases[]
#          entry would corrupt the outcome computation (which requires EVERY
#          entry to be PASS). The contract is still VERIFIED rather than
#          trusted when the phase fails — see phase_nonpass_count below.
#   no   — the phase script does not append. phase-00-precondition.sh does
#          not (it predates the run-report wiring and is deliberately usable
#          standalone by its own hermetic test), so this orchestrator appends
#          on its behalf: PASS on exit 0, FAIL otherwise.
#   gate — the phase script does not append AND has a THIRD outcome that is
#          neither pass nor fail. Today this is exactly phase-05-distribute.sh,
#          whose exit 3 means "the gate QUALIFIED and there is no distribute
#          step to run". See this script's header for why that outcome gets NO
#          phases[] entry rather than a PASS (which would claim a distribution
#          that did not happen) or a SKIPPED (which finalize_run_report turns
#          into outcome FAIL, failing every otherwise-good run). Exit 0 records
#          PASS; every other code records FAIL and halts the run. When a gate
#          phase names several scripts, a failure in ANY of them outranks a
#          sibling that qualified — the phase is FAIL, not a no-op.
#
# The script field may name MORE THAN ONE script, comma-separated. They run in
# listed order, and the phase halts at the first one that fails. This exists
# because "live_verify" is genuinely two independent surfaces — the running
# lava-api-go service and the :api-app build on a containerized emulator — and
# collapsing them into one script would have forced one to reimplement the
# other's orchestration. Both append their own "live_verify" entry to phases[];
# the report schema permits that (phases[] declares no uniqueItems), and it is
# the honest shape: BOTH must pass for the phase to have proven what its name
# claims, and finalize_run_report requires EVERY phases[] entry to be PASS.
#
# ORDER IS R-004's ORDER: changelog_entry precedes distribute (the distribute
# gate reads the CHANGELOG entry and its per-version snapshot as pre-existing
# inputs), and the broader docs_refresh comes last.
PHASES=(
  "precondition|phase-00-precondition.sh|no"
  "build|phase-01-build.sh|yes"
  "test|phase-02-test.sh|yes"
  "install_boot|phase-03-install-boot.sh|yes"
  "live_verify|phase-04-live-verify-api.sh,phase-04-live-verify-api-app.sh|yes"
  "changelog_entry|phase-05a-changelog-entry.sh|yes"
  "distribute|phase-05-distribute.sh|gate"
  "docs_refresh|phase-06-docs.sh|yes"
)

# The single exit code that a `gate` phase uses for "I qualified, and there is
# nothing further for me to do". Named, not spelled inline, so the one place
# it is defined can cite where it comes from: phase-05-distribute.sh's own
# header, which declares 0 / 2 / 3 and "No other exit codes are defined by
# this script."
GATE_QUALIFIED_NO_OP_EXIT=3

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

# Print the header comment block, whatever length it happens to be. A fixed
# line range (this used to be `sed -n '2,100p'`) silently truncates or spills
# into code the moment the header is edited, and --help is then wrong in a way
# nothing tests. Stop at the first line that is not a comment.
_usage() {
  awk 'NR==1 {next} /^#/ {sub(/^# ?/, ""); print; next} {exit}' "${BASH_SOURCE[0]}"
}

# --- Argument parsing ------------------------------------------------------
# Default: the furthest wired phase. NOTE this now includes the two phases
# that WRITE to the repository (changelog_entry, docs_refresh) — see the
# header section "THE DEFAULT RUN NOW WRITES TO THE REPOSITORY".
UNTIL_PHASE="docs_refresh"
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
  echo "The 'closure' phase is deliberately NOT wired yet — scripts/pipeline/phase-07-closure.sh does not exist, and is blocked behind T048/T049 plus T054's dedicated review gate. See this script's header." >&2
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

# --- The repository under test, and the ONE base every path resolves against -
#
# THE DEFECT THIS BLOCK EXISTS TO CLOSE (measured 2026-08-26, P0):
#
#   $ cd /path/to/lava
#   $ bash scripts/pipeline-build-test-distribute.sh /tmp/fixture-repo --until precondition
#
# ...where /tmp/fixture-repo was a DIFFERENT repository, on master, clean. The
# phases dutifully inspected the fixture. But `commit_sha` was read from the
# CURRENT WORKING DIRECTORY's HEAD, and every evidence path in this script, in
# lib/run-report.sh, and in half the phase scripts is RELATIVE — so it resolved
# against the CWD too. The run therefore deposited, inside THIS repository's
# .lava-ci-evidence/pipeline-runs/, a report reading:
#
#     "commit_sha": "<lava's HEAD>",  "outcome": "PASS",
#     "phases": [{"name": "precondition", "result": "PASS"}]
#
# A PASS attributed to a Lava commit that was never tested, produced by a run
# that verified a different repository, left at rest in Lava's evidence tree.
# §6.Z / §6.AK / §6.AA clause 8 all match evidence to artifacts BY COMMIT SHA,
# so that artifact is not merely untidy — it is a false claim positioned exactly
# where a gate will read it as true. Three such files were produced during the
# investigation before the cause was found.
#
# THE FIX, and why it is a chdir rather than a sprinkling of prefixes:
# the relative paths are not confined to this file. lib/run-report.sh's
# _run_report_path is relative; RUN_DIR and every phase log derived from it are
# relative; phase-01/02/03/04/05a/06 each compute REPORT_PATH and PHASE_DIR
# relative; while phase-02's sub-scripts and phase-05-distribute.sh instead use
# "${REPO_PATH}/.lava-ci-evidence/...". Prefixing only the ones in this file
# would move the report and leave the phase logs behind — two repositories, one
# run, which is worse than the single-wrong-repository state it replaced.
#
# Making the CWD *be* the repository under test collapses both families onto the
# same directory (".lava-ci-evidence/x" and "${REPO_PATH}/.lava-ci-evidence/x"
# become the same path) and needs no edit to any file this change does not own.
# It also satisfies lib/run-report.sh's stated contract ("Callers MUST run from
# the repo root") for the repository that is actually under test, rather than
# for whichever one the operator happened to be standing in.
#
# The interpretation is not invented here. It is the one the surrounding code
# already assumes in four independent places:
#   * phase-00-precondition.sh's header: the override exists so the hermetic
#     suite can aim the pipeline at a disposable fixture "without touching this
#     repository's actual git state" — which CWD-relative evidence writing did.
#   * the ignore probe immediately below: `git -C "$REPO_ROOT" check-ignore` is
#     only meaningful if the evidence lands in $REPO_ROOT.
#   * the precondition-failure diagnosis near the end of this file, which greps
#     `git -C "$REPO_ROOT" status` for this pipeline's own run directory.
#   * phase-01-build.sh:104, which records built_from_commit as
#     `cd "$REPO_PATH" && git rev-parse HEAD` — target-relative attribution,
#     directly contradicting the CWD-relative commit_sha this block replaces.
# and which tests/pipeline/test_pipeline_orchestrator.sh already depends on: its
# _latest_report helper looks for the report under the FIXTURE directory. That
# suite passes today only because its _run_orch cd's into the fixture AND passes
# it as the override, keeping the two bases accidentally equal. The defect lives
# precisely in the gap between them, which is why no existing case caught it.
#
# Canonicalising to an absolute physical path before the chdir is load-bearing:
# a relative override ("../fixture") would silently mean something else once the
# CWD moves, and the override is forwarded to every phase script.
if [[ -n "$REPO_PATH_OVERRIDE" ]]; then
  if [[ ! -d "$REPO_PATH_OVERRIDE" ]]; then
    echo "pipeline-build-test-distribute: repo-path override is not a directory: ${REPO_PATH_OVERRIDE}" >&2
    exit 2
  fi
  REPO_ROOT="$(cd -P -- "$REPO_PATH_OVERRIDE" && pwd -P)"
else
  if ! REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null)"; then
    echo "pipeline-build-test-distribute: not inside a git repository, and no repo-path override was given." >&2
    echo "  Run from a repository root, or pass the repository to operate on as the positional argument." >&2
    exit 2
  fi
  REPO_ROOT="$(cd -P -- "$REPO_ROOT" && pwd -P)"
fi

# A directory is not a repository. Every downstream `git -C "$REPO_ROOT" ...`
# (the ignore probe, the dirt diagnosis, the commit_sha read) assumes it is, and
# an unverified assumption at the top of a run is how a report ends up
# describing something other than what ran.
if ! git -C "$REPO_ROOT" rev-parse --git-dir >/dev/null 2>&1; then
  echo "pipeline-build-test-distribute: not a git repository: ${REPO_ROOT}" >&2
  exit 2
fi

# Forward the CANONICAL path to every phase, always — not only when the operator
# typed one. Each phase resolves `${REPO_PATH_OVERRIDE:-$REPO_ROOT}` where its
# own $REPO_ROOT is derived from its script location, so leaving this empty lets
# a phase decide for itself which repository is under test. That is exactly one
# more way for a phase and this orchestrator to disagree about what is being
# verified. In the ordinary invocation (from the repo root, no override) the two
# values are identical and nothing changes; when they would differ, this makes
# the orchestrator's answer the only answer. Verified against all phase scripts:
# none branches on whether the argument was supplied, only on its value.
REPO_PATH_OVERRIDE="$REPO_ROOT"

# THE chdir. Everything relative — here, in the sourced libraries, and in every
# phase script this orchestrator spawns (they inherit this CWD) — resolves under
# the repository being tested from this line onward.
cd -P -- "$REPO_ROOT"

# Assert it, rather than assume it. If a future edit reorders this block, or a
# path turns out not to be what `cd` accepted, this refuses to start instead of
# quietly resuming the split-base behaviour that produced the false PASS above.
if [[ "$PWD" != "$REPO_ROOT" ]]; then
  echo "pipeline-build-test-distribute: REFUSING TO START — could not make the repository under test the working directory." >&2
  echo "  wanted: ${REPO_ROOT}" >&2
  echo "  got:    ${PWD}" >&2
  echo "  Evidence paths are resolved relative to the working directory; with these" >&2
  echo "  disagreeing, this run would write its report into one repository while" >&2
  echo "  verifying another. That artifact would be a false claim at rest." >&2
  exit 2
fi

# shellcheck source=scripts/pipeline/lib/run-report.sh
source "$SCRIPT_DIR/pipeline/lib/run-report.sh"
# shellcheck source=scripts/pipeline/lib/evidence.sh
source "$SCRIPT_DIR/pipeline/lib/evidence.sh"

run_id="$(date -u +%Y-%m-%dT%H-%M-%SZ)"

# `-C "$REPO_ROOT"` is the whole point, and it is NOT redundant with the chdir
# above. This single field is what every downstream gate uses to decide which
# artifact a run's verdict describes (§6.Z's same-SHA evidence rule, §6.AK's
# coverage intersection, §6.AA clause 8 condition (A), which refuses outright
# when it does not equal `git rev-parse HEAD`). Reading it from the working
# directory made it a statement about wherever the operator was standing; naming
# the repository explicitly makes it a statement about what was actually tested,
# and keeps it true if the chdir is ever moved, wrapped, or removed.
if ! commit_sha="$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null)"; then
  echo "pipeline-build-test-distribute: could not read HEAD of ${REPO_ROOT} (an unborn branch, or a corrupt repository)." >&2
  echo "  Refusing rather than recording a run with no identifiable commit: a report whose" >&2
  echo "  commit_sha does not name the tested commit is the defect this check exists for." >&2
  exit 2
fi

# PREVENTIVE ignore check — MUST run before init_run_report creates anything.
#
# init_run_report creates this run's evidence directory, and it does so BEFORE
# phase-00 evaluates FR-000's clean-tree rule (deliberately: a refusal to start
# is itself a run outcome and must be recorded). In a repository where
# ".lava-ci-evidence/pipeline-runs/" is NOT ignored, that ordering means the
# pipeline dirties the very tree it is about to test, fails its own
# precondition, and then refuses to start FOREVER -- each attempt leaving one
# more untracked directory behind. Observed for real on 2026-08-21 against a
# fixture repo with no such ignore rule.
#
# There is already a diagnosis for that downstream, at the precondition-failure
# branch, and it fails CLOSED, which is correct. But a diagnosis printed after
# the damage is done still leaves the artifacts on disk. Checking the rule
# BEFORE creating anything converts "dirty the tree, then explain why" into
# "refuse before touching anything" -- which is what a precondition is for.
#
# `git check-ignore -q` is the authority rather than grepping .gitignore: the
# rule may live in .git/info/exclude, in a global excludesfile, or in a nested
# .gitignore, and a grep for the literal string would miss all three and refuse
# a repository that is in fact correctly configured.
_ignore_probe=".lava-ci-evidence/pipeline-runs/"
_ignore_rc=0
git -C "$REPO_ROOT" check-ignore -q "$_ignore_probe" || _ignore_rc=$?
if [[ "$_ignore_rc" -ne 0 ]]; then
  echo "pipeline-build-test-distribute: REFUSING TO START -- nothing has been created." >&2
  echo "" >&2
  if [[ "$_ignore_rc" -eq 1 ]]; then
    echo "  '${_ignore_probe}' is NOT ignored by git in ${REPO_ROOT}." >&2
    echo "  This pipeline writes its own evidence there before it evaluates FR-000's" >&2
    echo "  clean-tree precondition. Without the ignore rule it would dirty the tree it" >&2
    echo "  is about to test, fail that precondition, and never be able to start again --" >&2
    echo "  leaving one more untracked run directory behind on every attempt." >&2
    echo "" >&2
    echo "  Add this line to that repository's .gitignore and re-run:" >&2
    echo "      .lava-ci-evidence/pipeline-runs/" >&2
    echo "  (See task T003, which exists for exactly this.)" >&2
  else
    echo "  Could not determine whether '${_ignore_probe}' is ignored:" >&2
    echo "  'git check-ignore' exited ${_ignore_rc} in ${REPO_ROOT}." >&2
    echo "  Refusing rather than assuming: proceeding on an unverified precondition is" >&2
    echo "  how a run reports success it never established." >&2
  fi
  exit 2
fi

init_run_report "$run_id" "$commit_sha" >/dev/null
REPORT_INITIALIZED=1

# CONFIRM where the report actually landed — do not infer it from the fact that
# init_run_report returned 0.
#
# init_run_report resolves its path relative to the working directory, and the
# chdir above is what makes that the repository under test. This re-derives the
# same path independently and checks it is inside $REPO_ROOT, so the invariant
# "a run against repository X deposits its evidence in repository X" is measured
# on each run rather than trusted to hold because the code above looks right.
# It is deliberately checked at the FIRST artifact created: catching the split
# here means at most one empty directory in the wrong place, instead of a
# finalized report.json carrying a PASS and somebody else's commit_sha.
_report_probe="${PWD}/.lava-ci-evidence/pipeline-runs/${run_id}/report.json"
if [[ ! -f "$_report_probe" || "${_report_probe#"${REPO_ROOT}/"}" == "$_report_probe" ]]; then
  echo "pipeline-build-test-distribute: ABORTING — this run's report was not created inside the repository under test." >&2
  echo "  repository under test: ${REPO_ROOT}" >&2
  echo "  report expected at:    ${_report_probe}" >&2
  echo "  Evidence written outside the tested repository is attributed to the wrong commit" >&2
  echo "  by every gate that matches evidence to artifacts by SHA (§6.Z, §6.AK, §6.AA cl.8)." >&2
  exit 2
fi
unset _report_probe

# Relative ON PURPOSE, and correct only because of the chdir above: it is both
# the on-disk location and the `evidence_dir` string recorded in report.json.
# Keeping it repo-relative keeps that recorded value portable and identical to
# what the phase scripts record for themselves (they use the same literal), so a
# reader of report.json never has to reconcile two spellings of one directory.
# An absolute value here would bake this machine's checkout path into a tracked
# artifact for no gain.
RUN_DIR=".lava-ci-evidence/pipeline-runs/${run_id}"

# _close_report — recompute evidence_summary from the real records on disk,
# then finalize. Runs on EVERY exit path once the report exists (including
# an interrupt), because a run directory containing a report.json that was
# never finalized is indistinguishable at rest from one that is still
# running. Prints the finalized outcome.
_close_report() {
  [[ "${REPORT_INITIALIZED:-0}" -eq 1 ]] || return 0

  # FIRST: if a phase was still in flight, this run did not complete. Record
  # that before computing anything, so the outcome rule sees a FAIL phase.
  #
  # The second argument is the phase this orchestrator is executing. The marker
  # exists so a LATER reader can tell the run was interrupted; this process
  # already knows, so when the marker cannot be interpreted (empty after a
  # failed write, unreadable, mangled) the name is supplied from here rather
  # than losing the interruption with it. It can only NAME an interruption the
  # marker's existence already proves — with no marker, it changes nothing.
  #
  # The return code is COMPARED, not discarded. It used to be swallowed by
  # `|| true`, which made "a phase was in flight and I could not record it"
  # indistinguishable from "this run completed" — the same failed-measurement-
  # reads-as-clean shape the function itself was fixed for.
  local _irc=0
  _interrupted="$(append_interrupted_phase_if_any "$run_id" "${phase_name:-}" 2>/dev/null)" || _irc=$?
  if [[ "$_irc" -ne 0 ]]; then
    _REPORT_UNTRUSTWORTHY=1
    _REPORT_UNTRUSTWORTHY_REASON="THIS RUN WAS INTERRUPTED AND THE REPORT DOES NOT RECORD IT. Its 'outcome' field describes only the phases that managed to report"
    echo "pipeline-build-test-distribute: a phase was IN FLIGHT and its interruption could NOT be recorded (append_interrupted_phase_if_any exited ${_irc}). This run did not finish, and report.json cannot be made to say so — do not read it as a pass." >&2
  elif [[ -n "${_interrupted:-}" ]]; then
    echo "pipeline-build-test-distribute: run was INTERRUPTED during phase '${_interrupted}' — recorded as FAIL. A run that did not finish is not a run that passed." >&2
  fi

  # The return code is COMPARED, not swallowed by `|| echo WARNING`.
  #
  # `recompute_evidence_summary` is the ONLY thing that ever populates
  # `evidence_summary.rejected_by_anti_bluff`; `init_run_report` seeds that
  # counter to 0. So a FAILED recompute does not merely produce a report that
  # "may not reflect rejected evidence" — it leaves the outcome rule reading a
  # literal "nothing was rejected" and computing PASS, while a REJECTED
  # Evidence Record sits on disk in this very run directory.
  #
  # MEASURED (two runs identical but for this return code):
  #   pristine        → evidence_summary.rejected_by_anti_bluff=1  outcome=FAIL  exit=1
  #   recompute fails → evidence_summary.rejected_by_anti_bluff=0  outcome=PASS  exit=0
  # — with a REJECTED record present on disk in BOTH. One line of stderr was
  # the entire difference between a caught bluff and a clean bill of health.
  #
  # The `_final_exit`/outcome backstop below CANNOT catch this: it tests
  # whether the OUTCOME is non-PASS, and here the outcome genuinely computes
  # to PASS off the unpopulated counter. A failed MEASUREMENT is not a clean
  # measurement, so it is routed to the untrustworthy channel instead — the
  # same channel an unrecordable interruption uses, for the same reason: the
  # report cannot be made honest, so the exit code must carry the verdict.
  #
  # Regression coverage: tests/pipeline/test_run_report_evidence_summary.sh
  # CASE 6.
  if ! recompute_evidence_summary "$run_id" >/dev/null 2>&1; then
    _REPORT_UNTRUSTWORTHY=1
    _REPORT_UNTRUSTWORTHY_REASON="ITS evidence_summary COULD NOT BE RECOMPUTED. The rejected_by_anti_bluff counter its 'outcome' was computed from is the value init seeded (0), NOT a measurement of this run, so that outcome must not be read as anti-bluff-clean — check the Evidence Records on disk directly"
    echo "pipeline-build-test-distribute: ERROR — could not recompute evidence_summary. The finalized outcome was computed from an UNMEASURED rejected_by_anti_bluff counter and MUST NOT be read as a pass." >&2
  fi
  finalize_run_report "$run_id" >/dev/null 2>&1 || \
    echo "pipeline-build-test-distribute: WARNING — could not finalize report.json" >&2

  # If the verdict cannot be trusted, the FILE must say so too — not only this
  # process's exit code.
  #
  # The exit code is honest, but it protects only THIS process. report.json
  # outlives it, and it is the artifact SC-008 sends an auditor to read FIRST;
  # §6.AA clause 8 conditions (A)/(B) are defined to consult that file's
  # `outcome`, not the terminal scrollback of the run that produced it. So a
  # recompute failure that leaves `"outcome": "PASS"` persisted on disk hands
  # every LATER reader — human or gate — the exact claim the exit code was
  # busy refusing, with the stderr line that contradicted it long since gone.
  #
  # BLOCKED is the honest value and it is not an invention: it is already what
  # init_run_report seeds, it is in the schema's enum alongside PASS and FAIL,
  # and it means precisely "this run's verdict is not established". That is the
  # true state when the measurement the verdict depends on did not run. FAIL is
  # NOT used, because it would assert a failure that has not been observed —
  # the opposite bluff, and equally unfounded.
  #
  # Fires ONLY when _REPORT_UNTRUSTWORTHY is set, so no run that genuinely
  # passed is touched.
  if [[ "${_REPORT_UNTRUSTWORTHY:-0}" -eq 1 ]]; then
    # Relative, and therefore resolved under the repository under test — this
    # function can run from the INT/TERM trap, which does not change the working
    # directory the chdir near the top established. It must address the very
    # same file init_run_report created; a downgrade applied to a path in some
    # other repository would leave the real report still claiming PASS.
    local _rp=".lava-ci-evidence/pipeline-runs/${run_id}/report.json"
    if [[ -f "$_rp" ]]; then
      local _tmp="${_rp}.untrustworthy.$$"
      if python3 -c 'import json,sys
p=sys.argv[1]; t=sys.argv[2]
d=json.load(open(p))
d["outcome"]="BLOCKED"
json.dump(d,open(t,"w"),indent=2,ensure_ascii=False)
open(t,"a").write("\n")' "$_rp" "$_tmp" 2>/dev/null && mv -f -- "$_tmp" "$_rp" 2>/dev/null; then
        :
      else
        rm -f -- "$_tmp" 2>/dev/null || true
        echo "pipeline-build-test-distribute: WARNING — could not downgrade the untrustworthy report's outcome to BLOCKED; that file may still claim a verdict it cannot support." >&2
      fi
    fi
  fi
}

trap '_close_report' INT TERM

echo "pipeline-build-test-distribute: run_id=${run_id} commit_sha=${commit_sha} repo=${REPO_ROOT}"
echo "pipeline-build-test-distribute: running phases up to and including '${UNTIL_PHASE}'"

# Say AT RUN TIME which phases of this run write to the repository.
#
# The default `--until` moved from live_verify to docs_refresh, and that
# added two phases that MUTATE the working tree: `changelog_entry` authors a
# CHANGELOG.md entry plus a per-version snapshot, and `docs_refresh` rewrites
# documentation and regenerates .html/.pdf siblings. The header says so, but
# the header is only reachable via --help, and an operator who types no flags
# at all never sees it. A default that silently starts editing the repository
# is the kind of surprise this pipeline exists to not produce.
#
# Computed from the phases this run will ACTUALLY reach — --until short of
# them, or --skip of them, means the notice would be false, so it is not
# printed. A warning that is wrong is worse than no warning: it teaches the
# reader to ignore the next one.
_writes_repo=()
for entry in "${PHASES[@]}"; do
  _wname="${entry%%|*}"
  case "$_wname" in
    changelog_entry|docs_refresh)
      [[ -z "${SKIP_SET[$_wname]:-}" ]] && _writes_repo+=("$_wname")
      ;;
  esac
  [[ "$_wname" == "$UNTIL_PHASE" ]] && break
done
if [[ "${#_writes_repo[@]}" -gt 0 ]]; then
  echo "pipeline-build-test-distribute: NOTE — this run WRITES TO THE REPOSITORY. Phase(s) ${_writes_repo[*]} edit tracked files (CHANGELOG.md, its per-version snapshot, documentation and its regenerated .html/.pdf siblings)."
  echo "  A completed run will leave the working tree DIRTY on purpose; the phase that commits is 'closure', which is not wired, so committing is a human act. Because FR-000 requires a clean tree, a SECOND run started before those changes are committed or discarded will refuse at 'precondition' — correctly."
  echo "  For a run that touches nothing: --until live_verify (the pre-T046 default), or --skip changelog_entry,distribute,docs_refresh."
fi
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
  # Set to 1 when a `gate` phase reported QUALIFIED-with-nothing-to-do. That
  # is neither a pass nor a failure, so it is tracked separately from
  # phase_exit rather than squeezed into it.
  gate_no_op=0

  for script_name in "${phase_scripts[@]}"; do
    # The phase's evidence directory, derived from the script's own number so
    # it matches the directory that script writes into. The suffix letter
    # matters: phase-05a-changelog-entry.sh owns <run>/phase-05a and
    # phase-05-distribute.sh owns <run>/phase-05, and they are NOT the same
    # directory. A fixed-width `${script_name:6:2}` slice returns "05" for
    # both and silently merges them.
    phase_dir="${RUN_DIR}/phase-$(sed -E 's/^phase-([0-9]+[a-z]?)-.*/\1/' <<< "$script_name")"
    mkdir -p -- "$phase_dir"
    phase_log="${phase_dir}/${script_name%.sh}-orchestrator.log"

    echo "pipeline-build-test-distribute: === phase '${phase_name}' -> ${script_name} ==="

    # The marker means "a phase script is executing RIGHT NOW" — not merely
    # "a phase started". That precision matters: a script that RETURNS was not
    # interrupted, whatever its exit code, and it has already recorded its own
    # result. Only a script that never returns leaves the marker set, and only
    # that case is an interruption. Marking per-phase instead of per-script
    # would append a phantom second FAIL to an ordinary phase failure, claiming
    # the phase was interrupted when it actually ran to completion and failed.
    mark_phase_in_flight "$run_id" "$phase_name" >/dev/null || \
      echo "pipeline-build-test-distribute: WARNING — could not mark phase '${phase_name}' in flight; an interruption from here may not be recorded" >&2

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

    # The script returned, so it was not interrupted — clear the marker before
    # doing anything with its exit code.
    clear_phase_in_flight "$run_id" || true

    if [[ "$script_exit" -ne 0 ]]; then
      # A `gate` phase's QUALIFIED-with-nothing-to-do code is not a failure.
      # It is checked HERE, against this phase's declared result-mode, so a
      # non-gate phase exiting 3 is still an ordinary failure — the meaning
      # belongs to the gate contract, not to the number.
      if [[ "$self_appends" == "gate" && "$script_exit" -eq "$GATE_QUALIFIED_NO_OP_EXIT" ]]; then
        gate_no_op=1
        continue
      fi
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
  elif [[ "$self_appends" == "gate" ]]; then
    # A FAILURE ANYWHERE IN THE PHASE IS CHECKED FIRST, before the qualified
    # no-op. A `gate` phase may name more than one script (the registry says
    # so explicitly, and live_verify already uses that shape), so an earlier
    # script can exit GATE_QUALIFIED_NO_OP_EXIT and a later one still FAIL.
    # Testing gate_no_op first made the failure branch unreachable the moment
    # any script in the phase had qualified: nothing was recorded, and
    # report.json — the artifact SC-008 sends an auditor to FIRST — finalized
    # to `outcome: "PASS"` for a run that halted on a failure, while the
    # process exit code said 1. "Some part of me qualified" never outranks
    # "some part of me failed". Regression coverage:
    # tests/pipeline/test_orchestrator_gate_and_registry_audit.sh CASE A,
    # with CASE B guarding the opposite over-correction.
    if [[ "$phase_exit" -ne 0 ]]; then
      # Every other code — the gate's own exit 2 refusal, and any code it
      # never defined — is a failure. The gate appends nothing of its own, so
      # if this orchestrator does not record it, NOBODY does and
      # finalize_run_report's all-PASS rule is satisfied vacuously by the
      # phases that did report. Checked, not assumed, so a future gate that
      # starts appending its own entry is not double-recorded.
      _recorded="$(phase_nonpass_count "$run_id" "$phase_name" 2>/dev/null || true)"
      if [[ ! "${_recorded:-}" =~ ^[0-9]+$ ]]; then
        _recorded=0
      fi
      if [[ "$_recorded" -eq 0 ]]; then
        append_phase_result "$run_id" "$phase_name" "FAIL" "$duration_seconds" "$phase_dir" >/dev/null || \
          echo "pipeline-build-test-distribute: ERROR — could not record the failure of gate phase '${phase_name}' in report.json; TREAT THAT REPORT AS UNTRUSTWORTHY (the process exit code below is authoritative)" >&2
      fi
    elif [[ "$gate_no_op" -eq 1 ]]; then
      # DELIBERATELY APPEND NOTHING. The gate qualified and there was no
      # distribute step to run, so no distribution happened — and phases[]
      # has no result value that says that. PASS would claim a distribution
      # this run did not perform (the §6.Z/§6.AK bluff class, one level up),
      # and SKIPPED is treated by finalize_run_report as NOT-PASS, which
      # would make every otherwise-perfect run finalize to FAIL. The absence
      # of an entry is the honest record; `distributions` staying empty is
      # its machine-readable half. Both are asserted by
      # tests/pipeline/test_pipeline_full_sequence_wiring.sh CASE C.
      #
      # This is the ONLY path in this script that runs a phase and records
      # nothing for it, and it is reachable only for a `gate` phase exiting
      # exactly GATE_QUALIFIED_NO_OP_EXIT. Say so out loud, on stdout, so it
      # can never be a silent omission.
      GATE_QUALIFIED_NO_DISTRIBUTION=1
      echo "pipeline-build-test-distribute: phase '${phase_name}' — GATE QUALIFIED, and it distributed nothing (exit ${GATE_QUALIFIED_NO_OP_EXIT}: no distribute step is implemented)."
      echo "  No '${phase_name}' entry is recorded in report.json, because nothing was distributed and phases[] has no result value that says so. 'distributions' stays empty."
      echo "  The gate's own verdict artifact: ${phase_dir}/gate-verdict.json$( [[ -f "${phase_dir}/gate-verdict.json" ]] || printf ' (NOT PRESENT — the gate did not write one)' )"
    else
      # Reserved and unreachable today: a `gate` phase exits 0 only once a
      # real distribution has completed, in every one of its scripts. PASS is
      # then the honest record.
      append_phase_result "$run_id" "$phase_name" "PASS" "$duration_seconds" "$phase_dir" >/dev/null
    fi
  elif [[ "$phase_exit" -ne 0 ]]; then
    # The registry's "self-appends-result: yes" is a CONTRACT, and a failing
    # phase is exactly when it is least likely to have been honoured: a script
    # that dies before reaching its own append_phase_result — an early `set -e`
    # abort, a usage error, a missing dependency, a crash, a kill of the child
    # alone — appends nothing, and then NOBODY records that the phase failed.
    # finalize_run_report's all-PASS rule is then satisfied vacuously by the
    # phases that DID report, and a run that halted here finalizes to "PASS".
    #
    # The in-flight marker cannot cover this: the script RETURNED, so the marker
    # was cleared, and correctly so — this run was not interrupted, it failed.
    #
    # Worst variant is a multi-script phase: live_verify's first script appends
    # live_verify/PASS, the second dies before appending, and phases[] is a
    # FULL-LENGTH all-PASS list, indistinguishable from a complete success.
    #
    # So VERIFY the contract rather than trusting it. Checking is one jq call.
    # Note what is NOT done here: no unconditional append (that would duplicate
    # a phase that reported its own FAIL) and no `// 0` default folded into the
    # query (that would make "the report could not be read" and "the phase
    # reported no failure" the same answer). An unreadable count is treated as
    # zero DELIBERATELY, which appends — failing closed.
    _recorded="$(phase_nonpass_count "$run_id" "$phase_name" 2>/dev/null || true)"
    if [[ ! "${_recorded:-}" =~ ^[0-9]+$ ]]; then
      _recorded=0
    fi
    if [[ "$_recorded" -eq 0 ]]; then
      echo "pipeline-build-test-distribute: phase '${phase_name}' exited ${phase_exit} but recorded no result of its own — the orchestrator is recording it as FAIL so report.json cannot claim a pass this run did not earn." >&2
      append_phase_result "$run_id" "$phase_name" "FAIL" "$duration_seconds" "$phase_dir" >/dev/null || \
        echo "pipeline-build-test-distribute: ERROR — could not record the failure of phase '${phase_name}' in report.json; TREAT THAT REPORT AS UNTRUSTWORTHY (the process exit code below is authoritative)" >&2
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

  if [[ "$gate_no_op" -eq 1 ]]; then
    echo "pipeline-build-test-distribute: phase '${phase_name}' QUALIFIED (${duration_seconds}s, ${#phase_scripts[@]} script(s)) — not the same thing as PASSED, and not recorded as one"
  else
    echo "pipeline-build-test-distribute: phase '${phase_name}' PASSED (${duration_seconds}s, ${#phase_scripts[@]} script(s))"
  fi
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
echo "  repository:  ${REPO_ROOT}"
# Absolute, unlike the repo-relative form recorded inside report.json. A reader
# of this summary is standing in some shell somewhere; when the run was aimed at
# another repository via the positional override, a bare
# ".lava-ci-evidence/..." would resolve, for them, to a file that does not
# exist — and the one that does exist is in a repository they were not told
# about on this line.
echo "  report:      ${REPO_ROOT}/${report_path}"
echo "  outcome:     ${outcome}"
if [[ -n "$_halted_at" ]]; then
  echo "  halted at:   ${_halted_at}"
fi
if [[ "${GATE_QUALIFIED_NO_DISTRIBUTION:-0}" -eq 1 ]]; then
  # An `outcome: PASS` on a script called "build-test-distribute" must never
  # be readable as "and it distributed". Say what did not happen, in the same
  # box as the outcome.
  echo "  distribute:  GATE QUALIFIED — NOTHING WAS DISTRIBUTED (no distribute step is implemented; report.json has no 'distribute' phase entry and its 'distributions' array is empty)"
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

# Some failures leave report.json describing a run that did not happen, or a
# verdict computed from a measurement that never ran. The process exit code is
# then the only remaining honest channel, so it must not be 0 whatever the
# report says.
#
# The reason is carried by the trigger rather than hardcoded here. There is
# more than one way to reach this point — an interruption that could not be
# recorded, and an evidence_summary that could not be recomputed — and a fixed
# "THIS RUN WAS INTERRUPTED" line would state a plain falsehood for the second.
# A report that misdescribes WHY it cannot be trusted is its own small bluff,
# and it sends the reader looking for an interruption that never occurred.
if [[ "${_REPORT_UNTRUSTWORTHY:-0}" -eq 1 ]]; then
  echo "pipeline-build-test-distribute: ${report_path} CANNOT BE TRUSTED — ${_REPORT_UNTRUSTWORTHY_REASON:-no reason was recorded, which is itself a defect}. Treat this exit code, not that file, as the verdict." >&2
  _final_exit=1
fi

exit "$_final_exit"

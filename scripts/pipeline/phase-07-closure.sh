#!/usr/bin/env bash
# scripts/pipeline/phase-07-closure.sh — Repository closure phase (T055,
# FR-014/FR-015/FR-016/FR-017).
#
# Advances every submodule this pipeline is authorized to advance, then
# commits and pushes all outstanding main-repository changes — including
# any updated pins — to every configured upstream, refusing on anything it
# cannot honestly account for rather than forcing.
#
# Implements the three operator decisions recorded 2026-08-26 under
# specs/002-build-test-distribute-pipeline/tasks.md's T055 entry (that task
# text IS this phase's design document — there is no separate design file):
#
#   (1) RECURSIVE SCOPE. The submodule set this phase examines is the FULL
#       RECURSIVE set, not just this repository's direct submodules. It
#       drives the UNCHANGED scripts/advance-all-submodules.sh once PER
#       LEVEL (the main repository, then every already-initialized
#       submodule that itself declares nested submodules), because
#       advancing a level-2+ pin would require a commit INSIDE the level-1
#       submodule — R-005 step 6, removed 2026-08-26 (see
#       scripts/advance-all-submodules.sh's own header) — which this phase
#       never attempts. A submodule declared in some level's .gitmodules but
#       not yet checked out is initialized AT ITS ALREADY-RECORDED PIN
#       (`git submodule update --init`, never `--remote`) so it becomes
#       enumerable and recordable, never so it becomes advanceable: only
#       `advance-all-submodules.sh`'s own GOVERNANCE_ALLOW decides that, and
#       it is unchanged by this phase. "constitution" is excluded from the
#       walk ENTIRELY at every level — never entered, never init'd, never
#       passed as a target repository — per root CLAUDE.md condition (B)'s
#       default-deny on the governance submodule itself.
#
#   (2) FR-017 WINS OVER THE EARLIER quickstart.md DRAFT. A run that ends
#       with pins staged but not committed is an INTERMEDIATE state, never
#       this phase's pass condition. This phase's own "Step A" (the advance
#       walk above) may legitimately leave the tree staged-not-clean; this
#       phase's "Step B" (commit + push + verify) is what turns that into
#       FR-017's actual end state: git status empty, every configured
#       upstream at the same tip SHA, zero '+'/'-' anywhere in
#       `git submodule status --recursive`.
#
#   (3) DECLARED PATH SET, REFUSE ON SURPRISES. `scripts/commit_all.sh`
#       defaults to `git add -A`, which would stage anything outstanding
#       regardless of provenance. This phase instead computes a DECLARED
#       path set — everything dirty when this phase started (which, because
#       FR-000 already guarantees a clean tree at the START of the pipeline
#       RUN this phase belongs to, can only be output from this run's own
#       earlier phases) plus whatever this phase's own top-level advance
#       staged — and passes exactly that set to `commit_all.sh --files`.
#       Anything dirty afterwards that is in NEITHER set is a surprise, and
#       this phase REFUSES to commit it, naming the paths, rather than
#       silently absorbing them.
#
# Usage:
#   scripts/pipeline/phase-07-closure.sh <run_id> [repo-path]
#
# <run_id> MUST already have a report.json (via lib/run-report.sh's
# init_run_report), exactly like every other wired phase. This script
# appends to that report under phase name "closure" and merges every
# Submodule Advance Record this run produced into its
# `submodule_advances[]` array — the helper T055's own design note flagged
# as missing.
#
# Test-only environment overrides (never required for a real invocation,
# and never able to relax a safety boundary — they only make the hermetic
# fixture path reachable, exactly the same posture
# scripts/advance-all-submodules.sh's own --allow-local-path-remotes takes):
#
#   LAVA_ADVANCE_VERIFY_CMD
#       Passed straight through to every advance-all-submodules.sh
#       invocation this phase makes. Unset by default, so production runs
#       get that script's own default (a real rebuild-and-test via
#       phase-01-build.sh + phase-02-test.sh). A hermetic test overrides
#       this with a trivial stub so it is not trying to build this actual
#       Android/Go project against a disposable fixture submodule.
#
#   LAVA_CLOSURE_ALLOW_LOCAL_PATH_REMOTES
#       When "true", adds --allow-local-path-remotes to every
#       advance-all-submodules.sh invocation. A hermetic fixture's
#       "upstream" is a local bare repo, which that script otherwise
#       refuses to fetch from by design (root CLAUDE.md condition (C)).
#       Never set this for a real invocation.
#
# Exit codes:
#   0 - closure complete: every level examined and recorded, the main
#       repository committed and pushed to every configured upstream (or
#       there was genuinely nothing to commit because the tree was already
#       at the end state), working tree empty, 0 recursive submodule
#       entries showing '+' or '-'.
#   1 - closure failed: an advance was rejected somewhere this phase treats
#       as a real failure, an undeclared path appeared, the commit/push
#       step failed (including a §6.C mirror-divergence, which
#       commit_all.sh's own push step already detects), or the post-closure
#       FR-017 verification found the tree not clean or not fully
#       initialized.
#   2 - usage/precondition error (missing run_id, report.json absent, repo
#       path unreadable). Nothing attempted.
#
# HONEST SCOPE NOTE. scripts/advance-all-submodules.sh (this phase's own
# dependency) reached its current, structurally-safe shape only after five
# adversarial review rounds (T054, accepted 2026-09-18) found twelve
# fixture-proven ways an earlier design could leak content to a real
# upstream. This phase has NOT yet been through an equivalent adversarial
# pass of its own — it is a first implementation against the recorded
# design, exercised by this task's own hermetic test suite
# (tests/pipeline/test_phase_07_closure.sh), not yet against a real
# submodule tree. That is exactly what tasks.md's T056 (disposable clone)
# and T062 (disposable branch, full end-to-end) exist to do next.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT_OF_SCRIPT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# shellcheck source=scripts/pipeline/lib/run-report.sh
source "$SCRIPT_DIR/lib/run-report.sh"
# shellcheck source=scripts/pipeline/lib/evidence.sh
source "$SCRIPT_DIR/lib/evidence.sh"
# shellcheck source=scripts/pipeline/lib/anti-bluff-validate.sh
source "$SCRIPT_DIR/lib/anti-bluff-validate.sh"

RUN_ID=""
REPO_PATH_OVERRIDE=""
_positional=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help)
      sed -n '2,80p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    --*)
      echo "phase-07-closure: unknown option '$1'" >&2
      exit 2 ;;
    *)
      case "$_positional" in
        0) RUN_ID="$1" ;;
        1) REPO_PATH_OVERRIDE="$1" ;;
        *) echo "phase-07-closure: unexpected extra argument '$1'" >&2; exit 2 ;;
      esac
      _positional=$((_positional + 1))
      shift ;;
  esac
done

if [[ -z "$RUN_ID" ]]; then
  echo "phase-07-closure: usage: $0 <run_id> [repo-path]" >&2
  exit 2
fi

REPORT_PATH=".lava-ci-evidence/pipeline-runs/${RUN_ID}/report.json"
if [[ ! -f "$REPORT_PATH" ]]; then
  echo "phase-07-closure: precondition failed — $REPORT_PATH does not exist (call init_run_report first)" >&2
  exit 2
fi

REPO_PATH="${REPO_PATH_OVERRIDE:-$REPO_ROOT_OF_SCRIPT}"
if ! REPO_ROOT="$(cd "$REPO_PATH" 2>/dev/null && pwd)"; then
  echo "phase-07-closure: '$REPO_PATH' is not a readable directory" >&2
  exit 2
fi
if ! git -C "$REPO_ROOT" rev-parse --git-dir >/dev/null 2>&1; then
  echo "phase-07-closure: '$REPO_ROOT' is not a git repository" >&2
  exit 2
fi

ADVANCE_SCRIPT="${SCRIPT_DIR}/../advance-all-submodules.sh"
# COMMIT_ALL_SCRIPT is resolved relative to REPO_ROOT (the target repository
# this invocation is closing out), NEVER relative to SCRIPT_DIR (where this
# phase-07-closure.sh file itself happens to live). scripts/commit_all.sh has
# no repo-path argument of its own -- it always operates on whatever
# repository CONTAINS the exact file bash was told to run (it derives its
# REPO_ROOT from its own BASH_SOURCE, then `cd`s there unconditionally,
# discarding any inherited working directory). Resolving it from SCRIPT_DIR
# would therefore silently run every commit/push against THIS repository
# regardless of the `[repo-path]` override this phase documents, which is
# invisible in production (there REPO_ROOT and SCRIPT_DIR's repo are always
# the same checkout) but breaks the very first time this phase targets a
# genuinely different repository -- a disposable clone (tasks.md T056) or a
# hermetic test fixture, both of which carry their OWN scripts/commit_all.sh
# for exactly this reason.
COMMIT_ALL_SCRIPT="${REPO_ROOT}/scripts/commit_all.sh"
if [[ ! -x "$ADVANCE_SCRIPT" ]]; then
  echo "phase-07-closure: cannot find an executable scripts/advance-all-submodules.sh at '${ADVANCE_SCRIPT}'" >&2
  exit 2
fi
if [[ ! -x "$COMMIT_ALL_SCRIPT" ]]; then
  echo "phase-07-closure: cannot find an executable scripts/commit_all.sh at '${COMMIT_ALL_SCRIPT}' (the target repository at '${REPO_ROOT}' must carry its own copy)" >&2
  exit 2
fi

PHASE_DIR=".lava-ci-evidence/pipeline-runs/${RUN_ID}/phase-07"
RAW_DIR="${PHASE_DIR}/hermetic-script/raw"
mkdir -p "$RAW_DIR"
COMBINED_LOG="${RAW_DIR}/closure-combined.log"
: > "$COMBINED_LOG"
_log() { echo "$*" | tee -a "$COMBINED_LOG"; }

ADVANCE_RECORD_ROOT=".lava-ci-evidence/pipeline-runs/${RUN_ID}/submodule-advances"
mkdir -p "$ADVANCE_RECORD_ROOT"

START_TS=$(date +%s)
OVERALL_OK="true"
FAILURE_REASONS=()
_fail() {
  OVERALL_OK="false"
  FAILURE_REASONS+=("$1")
  _log "phase-07-closure: FAILED — $1"
}

_log "phase-07-closure: repo=${REPO_ROOT}"
_log "phase-07-closure: run_id=${RUN_ID}"
_log ""

# _sanitize <string> — filesystem-safe token, reused for both per-level
# record directories and the top-level record-directory lookup below, so
# both sides of that lookup are guaranteed to agree.
_sanitize() {
  local raw="$1" sanitized
  sanitized="$(printf '%s' "$raw" | tr -c 'A-Za-z0-9._-' '_')"
  sanitized="$(printf '%s' "$sanitized" | sed -E 's/_{2,}/_/g; s/^_+//; s/_+$//')"
  [[ -n "$sanitized" ]] || sanitized="unnamed"
  printf '%s' "$sanitized"
}

# _read_status_paths <repo-dir> — echoes one dirty path per line, per
# `git status --porcelain -z --ignore-submodules=none`. NUL-delimited and
# sliced at a fixed 3-byte offset (2-char status code + separator), exactly
# the parsing scripts/advance-all-submodules.sh already uses for the same
# reason: a path may contain a space, and a naive whitespace split would
# silently mis-parse it. A rename/copy entry's second (source) path is
# also emitted, since it belongs to the same operator change.
_read_status_paths() {
  local repo="$1" entry orig
  while IFS= read -r -d '' entry; do
    [[ -n "$entry" ]] || continue
    printf '%s\n' "${entry:3}"
    if [[ "${entry:0:1}" == "R" || "${entry:0:1}" == "C" \
       || "${entry:1:1}" == "R" || "${entry:1:1}" == "C" ]]; then
      IFS= read -r -d '' orig || break
      [[ -n "$orig" ]] && printf '%s\n' "$orig"
    fi
  done < <(git -C "$repo" status --porcelain -z --ignore-submodules=none)
}

# ---------------------------------------------------------------------------
# Step 0: baseline. See operator decision (3) in the file header.
# ---------------------------------------------------------------------------
mapfile -t BASELINE_PATHS < <(_read_status_paths "$REPO_ROOT")
_log "phase-07-closure: baseline dirty path(s): ${#BASELINE_PATHS[@]}${BASELINE_PATHS[*]:+ (${BASELINE_PATHS[*]})}"

# ---------------------------------------------------------------------------
# Step 1: recursive per-level walk. See operator decision (1).
# ---------------------------------------------------------------------------
LEVELS_PROCESSED=()
_MAX_DEPTH=8

_advance_extra_args() {
  if [[ "${LAVA_CLOSURE_ALLOW_LOCAL_PATH_REMOTES:-}" == "true" ]]; then
    printf '%s\n' "--allow-local-path-remotes"
  fi
}

process_level() {
  local level_path="$1" depth="$2"
  local level_name
  level_name="$(basename -- "$level_path")"

  if [[ "$level_name" == "constitution" ]]; then
    _log "phase-07-closure: excluding '${level_path}' from the walk entirely (constitution/** is out of scope per root CLAUDE.md condition (B))"
    return 0
  fi
  if [[ "$depth" -gt "$_MAX_DEPTH" ]]; then
    _fail "recursion exceeded ${_MAX_DEPTH} levels at '${level_path}' — refusing to descend further"
    return 0
  fi
  if [[ ! -d "$level_path" ]] || ! git -C "$level_path" rev-parse --git-dir >/dev/null 2>&1; then
    # Not (yet) its own repository root -- an uninitialized submodule the
    # caller has not init'd yet, or a stale path. Nothing to do here; the
    # caller's own init pass (below, at the PARENT level) is what fixes
    # this before recursion is attempted.
    return 0
  fi

  # --- (a) initialize every declared-but-uninitialized nested submodule at
  # its already-recorded pin, excluding constitution by name. -------------
  local status_raw line prefix sub_path
  status_raw="$(git -C "$level_path" submodule status 2>/dev/null || true)"
  while IFS= read -r line; do
    [[ -n "$line" ]] || continue
    prefix="${line:0:1}"
    sub_path="$(awk '{print $2}' <<< "$line")"
    [[ -n "$sub_path" ]] || continue
    [[ "$(basename -- "$sub_path")" == "constitution" ]] && continue
    if [[ "$prefix" == "-" ]]; then
      _log "phase-07-closure: initializing uninitialized submodule '${level_path}/${sub_path}' at its already-recorded pin"
      if ! git -C "$level_path" submodule update --init -- "$sub_path" >>"$COMBINED_LOG" 2>&1; then
        _fail "could not initialize '${level_path}/${sub_path}' at its recorded pin"
      fi
    fi
  done <<< "$status_raw"

  # --- (b) invoke the unchanged advance-all-submodules.sh at this level ---
  local sanitized_level record_dir advance_rc=0 advance_log
  sanitized_level="$(_sanitize "$level_path")"
  record_dir="${ADVANCE_RECORD_ROOT}/${sanitized_level}"
  advance_log="${RAW_DIR}/advance-${sanitized_level}.log"
  _log "phase-07-closure: advancing level '${level_path}' (records -> ${record_dir})"
  local -a extra_args=()
  mapfile -t extra_args < <(_advance_extra_args)
  # Built as an array of NAME=value strings passed to `env`, NEVER as a
  # bash "${VAR+word}" conditional sitting where a command-prefix assignment
  # would normally go. That idiom looks plausible but is a genuine bash trap:
  # assignment-prefix recognition happens SYNTACTICALLY at parse time, before
  # any parameter expansion runs — a word that begins with `${` is never
  # reclassified as `NAME=value` no matter what it expands to, so once such a
  # word appears, bash treats it (after expansion) as the command name
  # itself. Measured on a fixture with LAVA_ADVANCE_VERIFY_CMD="true" set (as
  # every hermetic test in this suite's family sets it): bash tried to run
  # the literal string "LAVA_ADVANCE_VERIFY_CMD=true" as a command ("command
  # not found", exit 127) and scripts/advance-all-submodules.sh was never
  # invoked at all -- zero Submodule Advance Records were ever written,
  # silently, because (see below) exit 127 also fell through this function's
  # exit-code handling as an implicit success.
  local -a advance_env=(
    "LAVA_PIPELINE_RUN_ID=${RUN_ID}"
    "LAVA_ADVANCE_RECORD_DIR=${record_dir}"
  )
  if [[ -n "${LAVA_ADVANCE_VERIFY_CMD+x}" ]]; then
    advance_env+=("LAVA_ADVANCE_VERIFY_CMD=${LAVA_ADVANCE_VERIFY_CMD}")
  fi
  env "${advance_env[@]}" \
    bash "$ADVANCE_SCRIPT" "${extra_args[@]}" "$level_path" > "$advance_log" 2>&1 || advance_rc=$?
  cat "$advance_log" >> "$COMBINED_LOG"
  LEVELS_PROCESSED+=("$level_path")
  # Every branch here is explicit; there is no implicit-success fallthrough.
  # advance-all-submodules.sh's own contract only ever exits 0/1/2 -- any
  # other code (127 "command not found" chief among them, per the forensic
  # note above) means this invocation never ran the way this phase expects,
  # and that is a closure failure too, not a silently-accepted no-op.
  if [[ "$advance_rc" -eq 2 ]]; then
    _fail "advance-all-submodules.sh refused outright at level '${level_path}' (exit 2 — usage/config error; see ${advance_log})"
  elif [[ "$advance_rc" -eq 1 ]]; then
    _fail "advance-all-submodules.sh reported at least one rejection/failure at level '${level_path}' (exit 1; see ${advance_log})"
  elif [[ "$advance_rc" -ne 0 ]]; then
    _fail "advance-all-submodules.sh exited ${advance_rc} at level '${level_path}' — an exit code its own contract does not document (see ${advance_log}); treated as a failure rather than silently accepted"
  fi

  # --- recurse into every INITIALIZED nested submodule that itself has a
  # .gitmodules, excluding constitution. Re-read status: step (a) may have
  # just initialized entries the earlier snapshot showed as '-'. ----------
  status_raw="$(git -C "$level_path" submodule status 2>/dev/null || true)"
  while IFS= read -r line; do
    [[ -n "$line" ]] || continue
    prefix="${line:0:1}"
    sub_path="$(awk '{print $2}' <<< "$line")"
    [[ -n "$sub_path" ]] || continue
    [[ "$(basename -- "$sub_path")" == "constitution" ]] && continue
    [[ "$prefix" != "-" ]] || continue
    if [[ -f "${level_path}/${sub_path}/.gitmodules" ]]; then
      process_level "${level_path}/${sub_path}" "$((depth + 1))"
    fi
  done <<< "$status_raw"
}

process_level "$REPO_ROOT" 0
_log "phase-07-closure: level(s) processed: ${#LEVELS_PROCESSED[@]}${LEVELS_PROCESSED[*]:+ (${LEVELS_PROCESSED[*]})}"

# ---------------------------------------------------------------------------
# Step 2: reconcile. See operator decision (3).
# ---------------------------------------------------------------------------
mapfile -t CURRENT_PATHS < <(_read_status_paths "$REPO_ROOT")

TOP_LEVEL_RECORD_DIR="${ADVANCE_RECORD_ROOT}/$(_sanitize "$REPO_ROOT")"
ADVANCED_PATHS=()
if [[ -d "$TOP_LEVEL_RECORD_DIR" ]]; then
  while IFS= read -r -d '' rec; do
    outcome="$(python3 -c "import json,sys; print(json.load(open(sys.argv[1])).get('outcome',''))" "$rec" 2>/dev/null || true)"
    if [[ "$outcome" == "ADVANCED" ]]; then
      name="$(python3 -c "import json,sys; print(json.load(open(sys.argv[1])).get('submodule_name',''))" "$rec" 2>/dev/null || true)"
      [[ -n "$name" ]] && ADVANCED_PATHS+=("$name")
    fi
  done < <(find "$TOP_LEVEL_RECORD_DIR" -maxdepth 1 -name '*.json' -print0 2>/dev/null)
fi
_log "phase-07-closure: submodule(s) ADVANCED at the top level: ${#ADVANCED_PATHS[@]}${ADVANCED_PATHS[*]:+ (${ADVANCED_PATHS[*]})}"

DECLARED_PATHS=()
for p in ${BASELINE_PATHS[@]+"${BASELINE_PATHS[@]}"} ${ADVANCED_PATHS[@]+"${ADVANCED_PATHS[@]}"}; do
  [[ -n "$p" ]] && DECLARED_PATHS+=("$p")
done

SURPRISE_PATHS=()
for cp in ${CURRENT_PATHS[@]+"${CURRENT_PATHS[@]}"}; do
  [[ -n "$cp" ]] || continue
  found="false"
  for dp in ${DECLARED_PATHS[@]+"${DECLARED_PATHS[@]}"}; do
    [[ "$cp" == "$dp" ]] && { found="true"; break; }
  done
  [[ "$found" == "true" ]] || SURPRISE_PATHS+=("$cp")
done

if [[ "${#SURPRISE_PATHS[@]}" -gt 0 ]]; then
  _fail "${#SURPRISE_PATHS[@]} path(s) are dirty but were neither written by an earlier phase of this run nor staged by this phase's own submodule advance: ${SURPRISE_PATHS[*]}. Refusing to commit them — this phase never stages anything it cannot account for."
fi

# ---------------------------------------------------------------------------
# Step 3: commit + push. See operator decision (3). commit_all.sh's own
# push step already performs the §6.C per-remote convergence check and
# exits 4 on divergence, so this phase does not duplicate that check.
# ---------------------------------------------------------------------------
COMMIT_RC=0
if [[ "$OVERALL_OK" == "true" ]]; then
  if [[ "${#DECLARED_PATHS[@]}" -eq 0 ]]; then
    _log "phase-07-closure: nothing declared to commit — the tree may already be at the FR-017 end state"
  else
    _log "phase-07-closure: committing declared path(s): ${DECLARED_PATHS[*]}"
    (
      cd "$REPO_ROOT" \
        && bash "$COMMIT_ALL_SCRIPT" -m "chore(pipeline): closure phase — advance authorized submodule pins, commit outstanding run output [run ${RUN_ID}]" --files "${DECLARED_PATHS[@]}"
    ) >>"$COMBINED_LOG" 2>&1
    COMMIT_RC=$?
    if [[ "$COMMIT_RC" -eq 3 ]]; then
      _log "phase-07-closure: commit_all.sh reported nothing staged (exit 3) — treated as the already-clean end state, not a failure"
      COMMIT_RC=0
    elif [[ "$COMMIT_RC" -ne 0 ]]; then
      _fail "scripts/commit_all.sh exited ${COMMIT_RC} — see ${COMBINED_LOG}"
    fi
  fi
fi

# ---------------------------------------------------------------------------
# Step 4: FR-017 end-state verification.
# ---------------------------------------------------------------------------
if [[ "$OVERALL_OK" == "true" ]]; then
  final_status="$(git -C "$REPO_ROOT" status --porcelain --ignore-submodules=none)"
  if [[ -n "$final_status" ]]; then
    _fail "FR-017 violated — working tree is not empty after closure: $(printf '%s' "$final_status" | tr '\n' ';')"
  fi
  dirty_recursive="$(git -C "$REPO_ROOT" submodule status --recursive 2>/dev/null | grep -c '^[+-]' || true)"
  dirty_recursive="${dirty_recursive:-0}"
  if [[ "$dirty_recursive" -gt 0 ]]; then
    _fail "FR-017 violated — ${dirty_recursive} submodule(s) in the recursive tree are uninitialized or diverged from their parent's pin"
  fi
fi

# ---------------------------------------------------------------------------
# Step 5: merge every Submodule Advance Record this run produced, across
# every level, into report.json's submodule_advances[] array.
# ---------------------------------------------------------------------------
if ! python3 - "$REPORT_PATH" "$ADVANCE_RECORD_ROOT" <<'PYEOF'
import json, sys, os, glob

report_path, record_root = sys.argv[1], sys.argv[2]
with open(report_path) as f:
    report = json.load(f)

records = []
for path in sorted(glob.glob(os.path.join(record_root, "**", "*.json"), recursive=True)):
    base = os.path.basename(path)
    if base.startswith(".") or base == "_corpus.json":
        continue
    with open(path) as f:
        rec = json.load(f)
    required = ("submodule_name", "old_commit", "new_commit", "outcome")
    if all(k in rec for k in required):
        records.append(rec)

report["submodule_advances"] = records

tmp = report_path + ".tmp.merge"
with open(tmp, "w") as f:
    json.dump(report, f, indent=2, ensure_ascii=False)
    f.write("\n")
os.replace(tmp, report_path)
PYEOF
then
  _fail "could not merge Submodule Advance Records into ${REPORT_PATH}"
fi

# ---------------------------------------------------------------------------
# Evidence Record + phase result.
# ---------------------------------------------------------------------------
DURATION=$(( $(date +%s) - START_TS ))
RESULT="PASS"
[[ "$OVERALL_OK" == "true" ]] || RESULT="FAIL"

if [[ "$RESULT" == "PASS" ]]; then
  ASSERTION_SUMMARY="Closure examined ${#LEVELS_PROCESSED[@]} level(s) (${LEVELS_PROCESSED[*]:-none}), advanced ${#ADVANCED_PATHS[@]} submodule(s) at the top level (${ADVANCED_PATHS[*]:-none}), committed ${#DECLARED_PATHS[@]} declared path(s) via scripts/commit_all.sh (exit ${COMMIT_RC}), and confirmed the FR-017 end state directly: 'git status --porcelain --ignore-submodules=none' empty and 0 entries in 'git submodule status --recursive' showing '+' or '-'."
else
  ASSERTION_SUMMARY="FAILED: ${FAILURE_REASONS[*]}"
fi

RECORD_PATH=""
ANTI_BLUFF_STATUS="n/a"
if ! RECORD_PATH="$(write_evidence_record \
    "$PHASE_DIR" \
    "pipeline-closure-fr014-fr015-fr016-fr017" \
    "hermetic-script" \
    "scripts/pipeline/phase-07-closure.sh ${RUN_ID}" \
    "$RESULT" \
    "$ASSERTION_SUMMARY" \
    "$COMBINED_LOG")"; then
  echo "phase-07-closure: ERROR — write_evidence_record failed" >&2
  append_phase_result "$RUN_ID" "closure" "FAIL" "$DURATION" "$PHASE_DIR" >/dev/null || true
  exit 1
fi

if validate_evidence_record "$RECORD_PATH" >/dev/null 2>&1; then
  ANTI_BLUFF_STATUS="validated"
else
  ANTI_BLUFF_STATUS="$(python3 -c "import json,sys; print(json.load(open(sys.argv[1])).get('anti_bluff_status','REJECTED: unknown'))" "$RECORD_PATH" 2>/dev/null || echo "REJECTED: unknown")"
  RESULT="FAIL"
  FAILURE_REASONS+=("Evidence Record REJECTED by anti-bluff-validate.sh: ${ANTI_BLUFF_STATUS}")
fi

PHASE_RESULT="PASS"
[[ "$RESULT" == "PASS" ]] || PHASE_RESULT="FAIL"
append_phase_result "$RUN_ID" "closure" "$PHASE_RESULT" "$DURATION" "$PHASE_DIR" >/dev/null

echo ""
echo "phase-07-closure: SUMMARY"
echo "  level(s) processed:       ${LEVELS_PROCESSED[*]:-none}"
echo "  submodule(s) advanced:    ${ADVANCED_PATHS[*]:-none}"
echo "  declared path(s) staged:  ${DECLARED_PATHS[*]:-none}"
echo "  Evidence Record:          ${RECORD_PATH} (anti_bluff_status=${ANTI_BLUFF_STATUS})"
echo "  phase result:             ${PHASE_RESULT}"

if [[ "$PHASE_RESULT" == "FAIL" ]]; then
  echo "phase-07-closure: FAILED —" >&2
  for r in "${FAILURE_REASONS[@]}"; do
    echo "  ${r}" >&2
  done
  exit 1
fi
exit 0

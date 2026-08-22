#!/usr/bin/env bash
# scripts/pipeline/lib/run-report.sh — Pipeline Run Report writer (FR-019/SC-008).
#
# Source this file from the top-level orchestrator (and, for
# append_phase_result, from any phase script) per
# specs/002-build-test-distribute-pipeline/contracts/cli-contract.md's
# "Shared library contract". Do NOT `exec` it directly.
#
#   # shellcheck source=scripts/pipeline/lib/run-report.sh
#   source "$(dirname "${BASH_SOURCE[0]}")/lib/run-report.sh"
#
# Exposes four functions, all operating on the single consolidated
# artifact at ".lava-ci-evidence/pipeline-runs/<run_id>/report.json" per
# data-model.md's "Pipeline Run Report" entity and
# contracts/pipeline-run-report.schema.json:
#
#   init_run_report <run_id> <commit_sha>
#   append_phase_result <run_id> <phase_name> <result> <duration_seconds> <evidence_dir>
#   finalize_run_report <run_id>
#
# All three print the report.json path they wrote to, on stdout, on
# success (matching this project's existing CLI convention of printing
# what was done) and return non-zero + a stderr message on any failure.
#
# Path convention: every function takes a bare `run_id` and derives
# ".lava-ci-evidence/pipeline-runs/<run_id>/report.json" from it, relative
# to the current working directory — consistent with this project's
# existing scripts (scripts/ci.sh, scripts/tag.sh, etc.), which are always
# invoked from the repository root. Callers MUST run from the repo root.
#
# JSON is always read-modify-written via `jq` (preferred, since it never
# needs to re-serialize the parts of the document it isn't touching
# incorrectly) or a `python3 -c` fallback when `jq` is not on PATH — never
# via a regex substitution into the file, which is exactly the class of
# bug this project's own CLAUDE.md calls out ("never regex-substitute into
# a JSON file — that's how these things silently corrupt"). Every write
# goes through a temp file + atomic `mv` into place, and every write is
# re-parsed with `python3 -c "import json; json.load(...)"` before being
# reported as successful.
#
# Note: like scripts/pipeline/lib/evidence.sh, this file does NOT set
# `set -euo pipefail` at its own top level (it is a sourceable library —
# see that file's header for the full rationale). Callers are expected to
# run under `set -euo pipefail` themselves.
# ---------------------------------------------------------------------------

_RUN_REPORT_PHASE_NAMES=(
  "precondition"
  "build"
  "test"
  "install_boot"
  "live_verify"
  "changelog_entry"
  "distribute"
  "docs_refresh"
  "closure"
)

_RUN_REPORT_PHASE_RESULTS=("PASS" "FAIL" "SKIPPED")

# _run_report_path <run_id> — echoes the report.json path for a run_id.
_run_report_path() {
  printf '.lava-ci-evidence/pipeline-runs/%s/report.json' "$1"
}

# _run_report_is_one_of <needle> <haystack-array-name...> — exit 0 iff
# needle equals one of the remaining args.
_run_report_is_one_of() {
  local needle="$1"
  shift
  local candidate
  for candidate in "$@"; do
    if [[ "$needle" == "$candidate" ]]; then
      return 0
    fi
  done
  return 1
}

# _run_report_validate_json <path> — re-parse a JSON file as a mechanical
# anti-bluff self-check; never trust a writer's own exit code alone.
_run_report_validate_json() {
  python3 -c "import json,sys; json.load(open(sys.argv[1]))" "$1" >/dev/null 2>&1
}

# init_run_report <run_id> <commit_sha>
#
# Creates ".lava-ci-evidence/pipeline-runs/<run_id>/report.json" with the
# schema's required top-level fields populated:
#   run_id        — as given (validated against the schema's own pattern:
#                   ^\d{4}-\d{2}-\d{2}T\d{2}-\d{2}-\d{2}Z$, e.g.
#                   "2026-08-21T14-30-00Z" per data-model.md R-010).
#   commit_sha    — as given (validated against ^[0-9a-f]{40}$ — callers
#                   MUST pass a full `git rev-parse HEAD`, not a short SHA).
#   started_at    — now, in UTC, via `date -u +%Y-%m-%dT%H:%M:%SZ`.
#   completed_at  — a placeholder. The schema requires this field as a
#                   *string* matching format "date-time" — it does not
#                   allow `null`, so there is no schema-valid way to leave
#                   it "not yet known" other than writing SOME string.
#                   Rather than duplicating `started_at` (which would
#                   misleadingly read as "this run completed in zero
#                   seconds" to anyone who reads the file mid-run — itself
#                   a small anti-bluff concern), this function writes the
#                   Unix-epoch sentinel "1970-01-01T00:00:00Z": a value
#                   that is schema-valid but unambiguously NOT a real
#                   completion time to any human or tool reading it before
#                   finalize_run_report() overwrites it for real.
#   outcome       — "BLOCKED" (nothing has run yet; the schema's third enum
#                   value).
#
#                   CORRECTED 2026-08-21: this note used to say BLOCKED was
#                   "reserved for precondition refusal". The orchestrator
#                   contradicts that, deliberately. init_run_report runs
#                   BEFORE the precondition check, so that a refusal to start
#                   is itself recorded as a run outcome; a refusal therefore
#                   appends a precondition/FAIL phase entry and finalizes to
#                   outcome "FAIL", with a real run directory on disk. What
#                   BLOCKED actually means in practice is "this run was never
#                   finalized" — the value survives only when finalize_run_report
#                   never executed at all. Note the orchestrator traps INT and
#                   TERM and finalizes on both, so even an interrupted run
#                   normally does NOT end up BLOCKED.
#   phases, build_artifacts, distributions, submodule_advances — [].
#   evidence_summary — {total:0, passed:0, failed:0, skipped:0,
#                   rejected_by_anti_bluff:0}. `skipped` counts validated
#                   Evidence Records whose `result` is SKIPPED (a real,
#                   honestly-reported non-execution, e.g. a Go test's own
#                   `t.Skip(...)` or a check BLOCKED by a missing host
#                   precondition — see evidence-record.schema.json and
#                   data-model.md's Evidence Record section). A SKIPPED
#                   record is validated through the exact same anti-bluff
#                   rules as PASS/FAIL (anti-bluff-validate.sh does not
#                   exempt it from any check); once validated, it does
#                   NOT count toward `rejected_by_anti_bluff` and does NOT
#                   by itself block `outcome` from being PASS (see
#                   finalize_run_report below) — a legitimately-skipped,
#                   honestly-reported test is not a pipeline failure.
#
# Refuses (non-zero + stderr) if report.json already exists for this
# run_id — run_ids are meant to be unique per invocation (R-010); silently
# overwriting an existing run's report would destroy evidence.
init_run_report() {
  if [[ "$#" -ne 2 ]]; then
    echo "init_run_report: expected 2 arguments, got $#" >&2
    echo "usage: init_run_report <run_id> <commit_sha>" >&2
    return 1
  fi

  local run_id="$1" commit_sha="$2"

  if [[ ! "$run_id" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}-[0-9]{2}-[0-9]{2}Z$ ]]; then
    echo "init_run_report: run_id '$run_id' does not match the required pattern ^\\d{4}-\\d{2}-\\d{2}T\\d{2}-\\d{2}-\\d{2}Z$ (e.g. 2026-08-21T14-30-00Z)" >&2
    return 1
  fi

  if [[ ! "$commit_sha" =~ ^[0-9a-f]{40}$ ]]; then
    echo "init_run_report: commit_sha '$commit_sha' is not a full 40-hex-char SHA (use 'git rev-parse HEAD', not a short SHA)" >&2
    return 1
  fi

  local report_path
  report_path="$(_run_report_path "$run_id")"

  if [[ -e "$report_path" ]]; then  # vacuous-pass-ok: -e is the INTENDED test here — this is a refuse-to-overwrite guard, and anything existing at that path (file OR directory) must block, so the broader test is the correct one.
    echo "init_run_report: '$report_path' already exists — run_ids must be unique per invocation (R-010); refusing to overwrite" >&2
    return 1
  fi

  local run_dir
  run_dir="$(dirname -- "$report_path")"
  if ! mkdir -p -- "$run_dir"; then
    echo "init_run_report: could not create '$run_dir'" >&2
    return 1
  fi

  local started_at
  started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  local completed_at_placeholder="1970-01-01T00:00:00Z"

  local tmp_path="${report_path}.tmp.$$"

  if command -v jq >/dev/null 2>&1; then
    if ! jq -n \
      --arg run_id "$run_id" \
      --arg commit_sha "$commit_sha" \
      --arg started_at "$started_at" \
      --arg completed_at "$completed_at_placeholder" \
      '{
        run_id: $run_id,
        commit_sha: $commit_sha,
        started_at: $started_at,
        completed_at: $completed_at,
        outcome: "BLOCKED",
        phases: [],
        build_artifacts: [],
        evidence_summary: {total: 0, passed: 0, failed: 0, skipped: 0, rejected_by_anti_bluff: 0},
        distributions: [],
        submodule_advances: []
      }' > "$tmp_path"; then
      echo "init_run_report: jq failed to build report.json" >&2
      rm -f -- "$tmp_path"
      return 1
    fi
  else
    if ! python3 - "$tmp_path" "$run_id" "$commit_sha" "$started_at" "$completed_at_placeholder" <<'PYEOF'
import json
import sys

tmp_path, run_id, commit_sha, started_at, completed_at = sys.argv[1:6]
report = {
    "run_id": run_id,
    "commit_sha": commit_sha,
    "started_at": started_at,
    "completed_at": completed_at,
    "outcome": "BLOCKED",
    "phases": [],
    "build_artifacts": [],
    "evidence_summary": {"total": 0, "passed": 0, "failed": 0, "skipped": 0, "rejected_by_anti_bluff": 0},
    "distributions": [],
    "submodule_advances": [],
}
with open(tmp_path, "w") as f:
    json.dump(report, f, indent=2, ensure_ascii=False)
    f.write("\n")
PYEOF
    then
      echo "init_run_report: python3 fallback failed to build report.json" >&2
      rm -f -- "$tmp_path"
      return 1
    fi
  fi

  if ! _run_report_validate_json "$tmp_path"; then
    echo "init_run_report: internal error — wrote invalid JSON to '$tmp_path'" >&2
    rm -f -- "$tmp_path"
    return 1
  fi

  if ! mv -f -- "$tmp_path" "$report_path"; then
    echo "init_run_report: could not move '$tmp_path' into place at '$report_path'" >&2
    rm -f -- "$tmp_path"
    return 1
  fi

  printf '%s\n' "$report_path"
}

# append_phase_result <run_id> <phase_name> <result> <duration_seconds> <evidence_dir>
#
# Appends one entry to the report's "phases" array:
#   {name, result, duration_seconds, evidence_dir}
# per contracts/pipeline-run-report.schema.json's "phases.items" shape.
# Exposed here (rather than folded into finalize_run_report) because other
# pipeline phases, built independently in parallel, each need to append
# their own outcome into this same shared report as they complete —
# finalize_run_report is only ever called once, at the very end.
append_phase_result() {
  if [[ "$#" -ne 5 ]]; then
    echo "append_phase_result: expected 5 arguments, got $#" >&2
    echo "usage: append_phase_result <run_id> <phase_name> <result> <duration_seconds> <evidence_dir>" >&2
    return 1
  fi

  local run_id="$1" phase_name="$2" result="$3" duration_seconds="$4" evidence_dir="$5"

  if ! _run_report_is_one_of "$phase_name" "${_RUN_REPORT_PHASE_NAMES[@]}"; then
    echo "append_phase_result: invalid phase name '$phase_name' (must be one of: ${_RUN_REPORT_PHASE_NAMES[*]})" >&2
    return 1
  fi

  if ! _run_report_is_one_of "$result" "${_RUN_REPORT_PHASE_RESULTS[@]}"; then
    echo "append_phase_result: invalid result '$result' (must be one of: ${_RUN_REPORT_PHASE_RESULTS[*]})" >&2
    return 1
  fi

  if [[ ! "$duration_seconds" =~ ^[0-9]+(\.[0-9]+)?$ ]]; then
    echo "append_phase_result: duration_seconds '$duration_seconds' must be a non-negative number" >&2
    return 1
  fi

  if [[ -z "$evidence_dir" ]]; then
    echo "append_phase_result: evidence_dir must not be empty" >&2
    return 1
  fi

  local report_path
  report_path="$(_run_report_path "$run_id")"

  if [[ ! -f "$report_path" ]]; then
    echo "append_phase_result: no report.json found at '$report_path' — call init_run_report first" >&2
    return 1
  fi

  local tmp_path="${report_path}.tmp.$$"

  if command -v jq >/dev/null 2>&1; then
    if ! jq \
      --arg name "$phase_name" \
      --arg result "$result" \
      --argjson duration_seconds "$duration_seconds" \
      --arg evidence_dir "$evidence_dir" \
      '.phases += [{
        name: $name,
        result: $result,
        duration_seconds: $duration_seconds,
        evidence_dir: $evidence_dir
      }]' "$report_path" > "$tmp_path"; then
      echo "append_phase_result: jq failed to update report.json" >&2
      rm -f -- "$tmp_path"
      return 1
    fi
  else
    if ! python3 - "$report_path" "$tmp_path" "$phase_name" "$result" "$duration_seconds" "$evidence_dir" <<'PYEOF'
import json
import sys

report_path, tmp_path, phase_name, result, duration_seconds, evidence_dir = sys.argv[1:7]
with open(report_path) as f:
    report = json.load(f)

duration = float(duration_seconds)
if duration == int(duration):
    duration = int(duration)

report.setdefault("phases", []).append({
    "name": phase_name,
    "result": result,
    "duration_seconds": duration,
    "evidence_dir": evidence_dir,
})

with open(tmp_path, "w") as f:
    json.dump(report, f, indent=2, ensure_ascii=False)
    f.write("\n")
PYEOF
    then
      echo "append_phase_result: python3 fallback failed to update report.json" >&2
      rm -f -- "$tmp_path"
      return 1
    fi
  fi

  if ! _run_report_validate_json "$tmp_path"; then
    echo "append_phase_result: internal error — wrote invalid JSON to '$tmp_path'" >&2
    rm -f -- "$tmp_path"
    return 1
  fi

  if ! mv -f -- "$tmp_path" "$report_path"; then
    echo "append_phase_result: could not move '$tmp_path' into place at '$report_path'" >&2
    rm -f -- "$tmp_path"
    return 1
  fi

  printf '%s\n' "$report_path"
}

# finalize_run_report <run_id>
#
# Reads the current report.json, sets completed_at to now (UTC), and
# computes `outcome` per data-model.md's Validation rule, copied exactly:
#
#   outcome is "PASS" if and only if:
#     - phases[] is non-empty, AND
#     - every entry in phases[] has result == "PASS", AND
#     - evidence_summary.rejected_by_anti_bluff == 0
#   otherwise outcome is "FAIL".
#
# An empty phases[] is explicitly NOT a pass (an empty run proved nothing).
# Note this literal rule does not special-case a "SKIPPED" *phase* result
# (phases[].result, the whole-phase-level enum) — a run with any SKIPPED
# phase is FAIL, exactly as a run with any FAIL phase is, matching the
# rule as specified (this is deliberate, not an oversight: this function
# does not soften the rule for SKIPPED phases).
#
# This is a DIFFERENT thing from evidence_summary.skipped (the count of
# individual, per-test SKIPPED Evidence Records within a phase that
# otherwise ran and reported PASS). Per data-model.md's Evidence Record
# design decision, evidence_summary.skipped is deliberately NOT read by
# this computation at all — a phase containing one or more legitimately-
# skipped, anti-bluff-validated tests, with everything else PASS and
# rejected_by_anti_bluff == 0, still yields outcome PASS. Only
# rejected_by_anti_bluff (a SKIPPED record that FAILED anti-bluff
# validation counts here, same as a PASS/FAIL record would) and each
# phase's own result gate the outcome.
finalize_run_report() {
  if [[ "$#" -ne 1 ]]; then
    echo "finalize_run_report: expected 1 argument, got $#" >&2
    echo "usage: finalize_run_report <run_id>" >&2
    return 1
  fi

  local run_id="$1"
  local report_path
  report_path="$(_run_report_path "$run_id")"

  if [[ ! -f "$report_path" ]]; then
    echo "finalize_run_report: no report.json found at '$report_path' — call init_run_report first" >&2
    return 1
  fi

  local completed_at
  completed_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

  local tmp_path="${report_path}.tmp.$$"

  if command -v jq >/dev/null 2>&1; then
    if ! jq \
      --arg completed_at "$completed_at" \
      '
      def all_phases_pass: (.phases | length) > 0 and (.phases | all(.result == "PASS"));
      .completed_at = $completed_at
      | .outcome = (
          if (all_phases_pass and .evidence_summary.rejected_by_anti_bluff == 0)
          then "PASS"
          else "FAIL"
          end
        )
      ' "$report_path" > "$tmp_path"; then
      echo "finalize_run_report: jq failed to update report.json" >&2
      rm -f -- "$tmp_path"
      return 1
    fi
  else
    if ! python3 - "$report_path" "$tmp_path" "$completed_at" <<'PYEOF'
import json
import sys

report_path, tmp_path, completed_at = sys.argv[1:4]
with open(report_path) as f:
    report = json.load(f)

report["completed_at"] = completed_at

phases = report.get("phases", [])
all_pass = len(phases) > 0 and all(p.get("result") == "PASS" for p in phases)
rejected = report.get("evidence_summary", {}).get("rejected_by_anti_bluff", 1)
no_rejects = rejected == 0

report["outcome"] = "PASS" if (all_pass and no_rejects) else "FAIL"

with open(tmp_path, "w") as f:
    json.dump(report, f, indent=2, ensure_ascii=False)
    f.write("\n")
PYEOF
    then
      echo "finalize_run_report: python3 fallback failed to update report.json" >&2
      rm -f -- "$tmp_path"
      return 1
    fi
  fi

  if ! _run_report_validate_json "$tmp_path"; then
    echo "finalize_run_report: internal error — wrote invalid JSON to '$tmp_path'" >&2
    rm -f -- "$tmp_path"
    return 1
  fi

  if ! mv -f -- "$tmp_path" "$report_path"; then
    echo "finalize_run_report: could not move '$tmp_path' into place at '$report_path'" >&2
    rm -f -- "$tmp_path"
    return 1
  fi

  printf '%s\n' "$report_path"
}

# recompute_evidence_summary <run_id>
#
# Rebuilds report.json's `evidence_summary` object by scanning the REAL
# Evidence Record JSON files that phase wrappers actually wrote to disk for
# this run, then writes the resulting counts back into report.json.
#
# WHY THIS EXISTS (forensic anchor, 2026-08-21):
# data-model.md's Validation rule makes `outcome: "PASS"` conditional on
# `evidence_summary.rejected_by_anti_bluff == 0`, and finalize_run_report
# implements that rule literally. But `init_run_report` seeds
# evidence_summary to all-zeros and, until this function existed, NOTHING
# ever updated it: phase-02-test.sh tallies the identical counters locally
# and prints them in its console SUMMARY, but never writes them back into
# report.json. Net effect: the anti-bluff half of the outcome rule read a
# counter that was permanently 0, so a run whose Evidence Records had been
# REJECTED could still finalize to "PASS". The rule looked enforced and was
# not — precisely the bluff class this project's constitution exists to
# evict. Regression coverage:
# tests/pipeline/test_run_report_evidence_summary.sh CASE 3.
#
# Deliberately derives every count from the physical artifacts on disk
# rather than from a counter each phase must remember to increment. A phase
# that forgets to report cannot thereby hide its own rejected evidence: the
# records are either in the run directory or they are not.
#
# Record selection: a real Evidence Record always lives at exactly
# "<run_dir>/phase-NN/<category>/<test_id>.json" (see evidence.sh's
# write_evidence_record). A wrapper's raw-output companion files always
# live one level deeper, under "<category>/raw/", and are not .json. So
# per-phase-dir `-mindepth 2 -maxdepth 2 -name '*.json'` selects exactly
# the real Evidence Records — the same proven selector phase-02-test.sh
# uses. report.json itself sits at the run-dir root and is never matched.
#
# Counting rules, mirroring phase-02-test.sh's aggregator exactly:
#   - result PASS/FAIL/SKIPPED increment passed/failed/skipped respectively
#   - a record whose `result` cannot be interpreted counts as FAILED, never
#     silently ignored (an unreadable record is not evidence of success)
#   - `rejected_by_anti_bluff` counts every record whose anti_bluff_status
#     begins with "REJECTED", INDEPENDENTLY of its result — a REJECTED
#     record that claims PASS is the exact case this counter exists for
#   - `total` counts every record found
#
# Prints the report path on stdout on success.
#
# Exit codes: 0 on success; 1 on usage error, missing report.json, or an
# internal write failure.
recompute_evidence_summary() {
  if [[ "$#" -ne 1 ]]; then
    echo "recompute_evidence_summary: expected 1 argument, got $#" >&2
    echo "usage: recompute_evidence_summary <run_id>" >&2
    return 1
  fi

  local run_id="$1"
  local report_path
  report_path="$(_run_report_path "$run_id")"

  if [[ ! -f "$report_path" ]]; then
    echo "recompute_evidence_summary: no report.json found at '$report_path' — call init_run_report first" >&2
    return 1
  fi

  local run_dir
  run_dir="$(dirname -- "$report_path")"

  local total=0 passed=0 failed=0 skipped=0 rejected=0

  local phase_dir record_path result status
  # Iterate phase directories explicitly so the per-phase selector below is
  # byte-for-byte the one phase-02-test.sh already proves in production.
  while IFS= read -r -d '' phase_dir; do
    while IFS= read -r -d '' record_path; do
      if command -v jq >/dev/null 2>&1; then
        result="$(jq -r '.result // empty' "$record_path" 2>/dev/null)"
        status="$(jq -r '.anti_bluff_status // empty' "$record_path" 2>/dev/null)"
      else
        result="$(python3 -c "import json,sys
try:
    print(json.load(open(sys.argv[1])).get('result',''))
except Exception:
    print('')" "$record_path" 2>/dev/null)"
        status="$(python3 -c "import json,sys
try:
    print(json.load(open(sys.argv[1])).get('anti_bluff_status',''))
except Exception:
    print('')" "$record_path" 2>/dev/null)"
      fi

      total=$((total + 1))
      case "$result" in
        PASS)    passed=$((passed + 1)) ;;
        FAIL)    failed=$((failed + 1)) ;;
        SKIPPED) skipped=$((skipped + 1)) ;;
        *)       failed=$((failed + 1)) ;;
      esac

      if [[ "$status" == REJECTED* ]]; then
        rejected=$((rejected + 1))
      fi
    done < <(find "$phase_dir" -mindepth 2 -maxdepth 2 -type f -name '*.json' -print0 2>/dev/null)
  done < <(find "$run_dir" -mindepth 1 -maxdepth 1 -type d -name 'phase-*' -print0 2>/dev/null)

  local tmp_path="${report_path}.tmp.$$"

  if command -v jq >/dev/null 2>&1; then
    if ! jq \
      --argjson total "$total" \
      --argjson passed "$passed" \
      --argjson failed "$failed" \
      --argjson skipped "$skipped" \
      --argjson rejected "$rejected" \
      '.evidence_summary = {
         total: $total,
         passed: $passed,
         failed: $failed,
         skipped: $skipped,
         rejected_by_anti_bluff: $rejected
       }' "$report_path" > "$tmp_path"; then
      echo "recompute_evidence_summary: jq failed to update report.json" >&2
      rm -f -- "$tmp_path"
      return 1
    fi
  else
    if ! python3 - "$report_path" "$tmp_path" "$total" "$passed" "$failed" "$skipped" "$rejected" <<'PYEOF'
import json
import sys

report_path, tmp_path = sys.argv[1:3]
total, passed, failed, skipped, rejected = (int(v) for v in sys.argv[3:8])

with open(report_path) as f:
    report = json.load(f)

report["evidence_summary"] = {
    "total": total,
    "passed": passed,
    "failed": failed,
    "skipped": skipped,
    "rejected_by_anti_bluff": rejected,
}

with open(tmp_path, "w") as f:
    json.dump(report, f, indent=2, ensure_ascii=False)
    f.write("\n")
PYEOF
    then
      echo "recompute_evidence_summary: python3 fallback failed to update report.json" >&2
      rm -f -- "$tmp_path"
      return 1
    fi
  fi

  if ! _run_report_validate_json "$tmp_path"; then
    echo "recompute_evidence_summary: internal error — wrote invalid JSON to '$tmp_path'" >&2
    rm -f -- "$tmp_path"
    return 1
  fi

  if ! mv -f -- "$tmp_path" "$report_path"; then
    echo "recompute_evidence_summary: could not move '$tmp_path' into place at '$report_path'" >&2
    rm -f -- "$tmp_path"
    return 1
  fi

  printf '%s\n' "$report_path"
}

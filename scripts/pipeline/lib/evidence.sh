#!/usr/bin/env bash
# scripts/pipeline/lib/evidence.sh — Evidence Record writer (FR-003/FR-004).
#
# Source this file from any pipeline phase script that executes tests, per
# specs/002-build-test-distribute-pipeline/contracts/cli-contract.md's
# "Shared library contract". Do NOT `exec` it directly — it defines shell
# functions for the calling script's own shell, it has no standalone
# behavior of its own.
#
#   # shellcheck source=scripts/pipeline/lib/evidence.sh
#   source "$(dirname "${BASH_SOURCE[0]}")/lib/evidence.sh"
#
# ---------------------------------------------------------------------------
# FINAL SIGNATURE (deviates from contracts/cli-contract.md's originally
# documented 6-arg form — see "Deviation from the contract doc" below):
#
#   write_evidence_record <phase_dir> <test_id> <category> <command> \
#                          <result> <assertion_summary> <raw_output_path>
#
# Arguments (all 7 required, in this order):
#   phase_dir          The phase-level evidence directory the caller already
#                       knows it is running under, e.g.
#                       ".lava-ci-evidence/pipeline-runs/<run_id>/phase-02"
#                       (per data-model.md's Evidence Record path convention:
#                       "<run_dir>/phase-<NN>/<category>/<test_id>.json").
#                       This function appends "<category>/<sanitized
#                       test_id>.json" itself — it does NOT infer, parse, or
#                       validate the phase number embedded in this path; the
#                       caller (a specific phase-NN-*.sh script) always knows
#                       its own phase number statically, so making it pass
#                       the phase directory explicitly is strictly simpler
#                       and more robust than having this shared function try
#                       to derive "NN" from a test_id string, which does not
#                       reliably encode it (per data-model.md: test_id is a
#                       fully-qualified test name like
#                       "lava.login.LoginViewModelTest#validateUsername...").
#   test_id             Fully-qualified test name (data-model.md).
#   category             One of the 8 enum values in
#                       evidence-record.schema.json's "category" property.
#   command             The exact command invoked, for independent re-run
#                       (FR-003).
#   result              "PASS" or "FAIL".
#   assertion_summary    The real, specific checked outcome (never a generic
#                       phrase — see research.md R-009 rule (a); this
#                       function does not enforce that rule itself, it is
#                       anti-bluff-validate.sh's job per FR-004).
#   raw_output_path      Reference to the file already holding this test's
#                       captured stdout/stderr, given either as an
#                       absolute path or as a path relative to the current
#                       working directory (the same convention used for
#                       phase_dir itself). This function does NOT create,
#                       move, or inspect that file's contents — whether it
#                       exists and is non-empty is
#                       scripts/pipeline/lib/anti-bluff-validate.sh's
#                       R-009 rule (c) check, not this function's. It IS,
#                       however, re-pathed (see "raw_output_ref
#                       normalization" below) before being written into
#                       the record as "raw_output_ref".
#
# raw_output_ref normalization: the schema's own description calls this
# field a "Relative path to a file containing captured stdout/stderr" —
# but relative to WHAT base matters, because
# scripts/pipeline/lib/anti-bluff-validate.sh (the sibling component that
# actually reads this field back, per R-009) resolves a non-absolute
# raw_output_ref relative to *the JSON record file's own directory*
# (`resolved_raw_ref="${record_dir}/${raw_output_ref}"` in that script),
# NOT relative to the pipeline's CWD/repo root. If this function stored
# raw_output_path verbatim whenever a caller naturally passed something
# CWD-relative (e.g. "$phase_dir/raw/test.log", following the exact same
# convention phase_dir itself uses everywhere else in this pipeline),
# anti-bluff-validate.sh would resolve it against the wrong base directory
# (one level too deep — the record lives at
# "<phase_dir>/<category>/<id>.json", not at "<phase_dir>/") and
# incorrectly report the file missing. To prevent shipping that latent
# integration bug, this function re-expresses whatever raw_output_path it
# is given (absolute, or CWD-relative) as a path relative to
# "<phase_dir>/<category>/" — the record's own directory — via
# `python3 -c "... os.path.relpath(...)"` (pure path-string arithmetic,
# no filesystem access required, so it works even before the raw output
# file is flushed to disk). This has been verified against the real,
# already-implemented anti-bluff-validate.sh in this repo (not merely
# assumed) — see the verification output in this task's final report.
#
# Writes:  <phase_dir>/<category>/<sanitized-test_id>.json
# Prints:  the full path written, on stdout, and NOTHING else, on success —
#          so a caller can do:  record_path="$(write_evidence_record ...)"
#          (matches this project's existing CLI convention of a command
#          printing what it did, e.g. the workable-items binary's commands).
# Returns: 0 on success. Non-zero + a message on stderr on any failure
#          (wrong arg count, invalid category/result enum value, empty
#          required field, or a self-check JSON-validity failure after
#          writing — see "Anti-bluff note" below).
#
# anti_bluff_status is ALWAYS written as a NOT-YET-VALIDATED placeholder by
# this function (see _EVIDENCE_SH_UNVALIDATED_PLACEHOLDER below). This is
# NOT a verdict — it is the honest statement that no verdict exists yet,
# held until a separate, independent component
# (scripts/pipeline/lib/anti-bluff-validate.sh, per research.md R-009 /
# FR-004) inspects the finished record and overwrites this field for real.
# This function MUST NOT be extended to perform that check itself — doing
# so would collapse the "an independent validator can catch a test lying
# about itself" property FR-004 exists to guarantee.
#
# WHY THE PLACEHOLDER IS NOT "validated" (fixed 2026-08-26; forensic
# anchor). Until this change the placeholder was the literal string
# "validated" — the exact value the real validator writes on ACCEPT. That
# made "the independent validator examined this record and accepted it"
# and "no validator has ever looked at this record" BYTE-IDENTICAL on
# disk, and every consumer reads this field as a verdict. Measured
# consequence, captured end-to-end through the real
# scripts/pipeline/phase-02-test.sh: a `real-device-challenge` Evidence
# Record whose entire assertion_summary was "did not crash", written
# without any validator ever running, produced
#   Evidence Records found: 1 / PASS: 1 / REJECTED (anti-bluff): 0
#   phase-02-test: PASSED
# while the real validator's verdict on that same record was
#   REJECTED: assertion_summary matches generic bluff pattern 'did not
#   crash' with no other specific content
# Per §6.Z clause 4 a cold-start "did not crash" check is the MINIMUM and
# explicitly not sufficient on its own; §6.AK exists because a C00-only
# gate green-lit a release whose claimed fixes were never exercised. A
# defaulted stamp is not a validation — it is the ABSENCE of one being
# read as its presence, which is the §6.J bluff class by construction.
#
# The placeholder therefore takes the REJECTED form, which is (a) the
# only other value evidence-record.schema.json's
# `^(validated|REJECTED: .+)$` pattern permits, so no schema change is
# needed, and (b) fail-CLOSED: every existing consumer that tests
# `== "validated"` correctly refuses it, and every consumer that tests
# `REJECTED*` correctly flags it. A record nobody validated can no longer
# pass as a record somebody validated.
#
# Deviation from the contract doc: contracts/cli-contract.md's "Shared
# library contract" section originally documented this function's
# signature without any run-directory/phase-directory argument at all
# (`write_evidence_record <test_id> <category> <command> <result>
# <assertion_summary> <raw_output_path>`), leaving unstated how the
# function would know the "<run_dir>/phase-<NN>/" path prefix required by
# data-model.md. Rather than inferring the phase number from the test_id
# (which does not reliably encode it) or depending on ambient global state
# (an exported env var another phase script might forget to set), this
# implementation adds an explicit leading `phase_dir` parameter. Every
# caller is itself a phase-NN-*.sh script that already knows its own phase
# number as a compile-time fact, so passing it explicitly costs the caller
# nothing and removes an entire class of "which phase does this belong to"
# bugs. contracts/cli-contract.md HAS SINCE BEEN UPDATED to match this
# signature (2026-08-21) — it previously recorded a six-argument form with
# no phase_dir. This paragraph is kept as the forensic record of why the
# implementation deviated from the contract doc rather than the reverse; it
# is no longer an outstanding action.
#
# Anti-bluff note: this function does not assume printf-based string
# concatenation produces valid JSON. It builds the record with `jq -n`
# (which handles all JSON string escaping correctly) when `jq` is on PATH,
# falling back to `python3 -c` (`json.dump`) otherwise — never hand-rolled
# string interpolation into a JSON literal. After writing, it re-parses the
# file with `python3 -c "import json; json.load(...)"` as a mechanical
# self-check that the bytes on disk are strictly valid JSON before
# reporting success; a parse failure here is treated as this function's own
# bug and reported as such (exit non-zero), never silently swallowed.
#
# Note: unlike most of this project's top-level scripts, this file does
# NOT set `set -euo pipefail` at its own top level, because it is a
# sourceable library — doing so would silently change the shell-option
# behavior of whatever script sources it (surprising side effect on a
# caller that hasn't opted in). This matches the existing precedent in
# submodules/containers/scripts/lib/durable-run.sh. Callers are still
# expected to run under `set -euo pipefail` themselves per this project's
# existing script convention (contracts/cli-contract.md line 3).
# ---------------------------------------------------------------------------

_EVIDENCE_SH_CATEGORIES=(
  "kotlin-unit"
  "go-unit-integration"
  "real-binary-contract"
  "real-device-challenge"
  "hermetic-script"
  "stress-chaos"
  "release-canary"
  "constitutional-gate-sweep"
)

# _evidence_is_valid_category <value> — exit 0 iff value is one of the 8
# enum values from evidence-record.schema.json's "category" property.
# The not-yet-validated placeholder written by write_evidence_record. MUST
# match evidence-record.schema.json's `^(validated|REJECTED: .+)$` pattern,
# and MUST NOT be "validated" — see the long note in write_evidence_record's
# header for the measured reason.
_EVIDENCE_SH_UNVALIDATED_PLACEHOLDER="REJECTED: anti-bluff validation has not run on this record (placeholder written by write_evidence_record; scripts/pipeline/lib/anti-bluff-validate.sh must evaluate this record and overwrite this field)"

_evidence_is_valid_category() {
  local candidate="$1" known
  for known in "${_EVIDENCE_SH_CATEGORIES[@]}"; do
    if [[ "$candidate" == "$known" ]]; then
      return 0
    fi
  done
  return 1
}

# _evidence_sanitize_test_id <test_id> — produce a filesystem-safe basename
# (no extension) from an arbitrary fully-qualified test name. Replaces any
# character outside [A-Za-z0-9._-] with "_", collapses runs of "_", and
# trims leading/trailing "_". Prints the sanitized id on stdout; falls back
# to "unnamed-test" if sanitization would otherwise produce an empty string.
_evidence_sanitize_test_id() {
  local raw="$1" sanitized
  sanitized="$(printf '%s' "$raw" | tr -c 'A-Za-z0-9._-' '_')"
  sanitized="$(printf '%s' "$sanitized" | sed -E 's/_{2,}/_/g; s/^_+//; s/_+$//')"
  if [[ -z "$sanitized" ]]; then
    sanitized="unnamed-test"
  fi
  printf '%s' "$sanitized"
}

# _evidence_relpath <target-path> <start-dir> — prints the path to
# <target-path>, expressed relative to <start-dir>. Pure path-string
# arithmetic via python3's os.path.relpath (both arguments may themselves
# be relative — resolved against the current process's CWD exactly the
# way bash itself would, since it's the same process's CWD — or absolute;
# neither path needs to actually exist on disk). Used to re-express
# raw_output_path relative to the Evidence Record's own directory, per the
# "raw_output_ref normalization" note in this file's header.
_evidence_relpath() {
  python3 -c "import os, sys; print(os.path.relpath(sys.argv[1], start=sys.argv[2]))" "$1" "$2"
}

# write_evidence_record <phase_dir> <test_id> <category> <command> <result> \
#                        <assertion_summary> <raw_output_path>
# See the file header above for the full contract.
write_evidence_record() {
  if [[ "$#" -ne 7 ]]; then
    echo "write_evidence_record: expected 7 arguments, got $#" >&2
    echo "usage: write_evidence_record <phase_dir> <test_id> <category> <command> <result> <assertion_summary> <raw_output_path>" >&2
    return 1
  fi

  local phase_dir="$1"
  local test_id="$2"
  local category="$3"
  local command_str="$4"
  local result="$5"
  local assertion_summary="$6"
  local raw_output_path="$7"

  if [[ -z "$phase_dir" || -z "$test_id" || -z "$category" || -z "$command_str" || -z "$result" || -z "$assertion_summary" || -z "$raw_output_path" ]]; then
    echo "write_evidence_record: no argument may be empty" >&2
    return 1
  fi

  if ! _evidence_is_valid_category "$category"; then
    echo "write_evidence_record: invalid category '$category' (must be one of: ${_EVIDENCE_SH_CATEGORIES[*]})" >&2
    return 1
  fi

  if [[ "$result" != "PASS" && "$result" != "FAIL" && "$result" != "SKIPPED" ]]; then
    echo "write_evidence_record: invalid result '$result' (must be PASS, FAIL, or SKIPPED)" >&2
    return 1
  fi

  local target_dir="${phase_dir%/}/${category}"
  if ! mkdir -p -- "$target_dir"; then
    echo "write_evidence_record: could not create directory '$target_dir'" >&2
    return 1
  fi

  local sanitized_id out_path tmp_path raw_output_ref
  sanitized_id="$(_evidence_sanitize_test_id "$test_id")"
  out_path="${target_dir}/${sanitized_id}.json"
  tmp_path="${out_path}.tmp.$$"

  # Normalize raw_output_path into a path relative to target_dir (the
  # record's own directory) — see "raw_output_ref normalization" above.
  if ! raw_output_ref="$(_evidence_relpath "$raw_output_path" "$target_dir")"; then
    echo "write_evidence_record: could not compute a relative path from '$target_dir' to '$raw_output_path'" >&2
    return 1
  fi

  if command -v jq >/dev/null 2>&1; then
    if ! jq -n \
      --arg test_id "$test_id" \
      --arg category "$category" \
      --arg command "$command_str" \
      --arg result "$result" \
      --arg assertion_summary "$assertion_summary" \
      --arg raw_output_ref "$raw_output_ref" \
      --arg anti_bluff_status "$_EVIDENCE_SH_UNVALIDATED_PLACEHOLDER" \
      '{
        test_id: $test_id,
        category: $category,
        command: $command,
        result: $result,
        assertion_summary: $assertion_summary,
        raw_output_ref: $raw_output_ref,
        anti_bluff_status: $anti_bluff_status
      }' > "$tmp_path"; then
      echo "write_evidence_record: jq failed to build the JSON record" >&2
      rm -f -- "$tmp_path"
      return 1
    fi
  else
    if ! python3 - "$tmp_path" "$test_id" "$category" "$command_str" "$result" "$assertion_summary" "$raw_output_ref" "$_EVIDENCE_SH_UNVALIDATED_PLACEHOLDER" <<'PYEOF'
import json
import sys

tmp_path, test_id, category, command, result, assertion_summary, raw_output_ref, anti_bluff_status = sys.argv[1:9]
record = {
    "test_id": test_id,
    "category": category,
    "command": command,
    "result": result,
    "assertion_summary": assertion_summary,
    "raw_output_ref": raw_output_ref,
    "anti_bluff_status": anti_bluff_status,
}
with open(tmp_path, "w") as f:
    json.dump(record, f, indent=2, ensure_ascii=False)
    f.write("\n")
PYEOF
    then
      echo "write_evidence_record: python3 fallback failed to build the JSON record" >&2
      rm -f -- "$tmp_path"
      return 1
    fi
  fi

  # Anti-bluff self-check: never trust that the bytes we just wrote are
  # valid JSON just because the writer didn't report an error.
  if ! python3 -c "import json,sys; json.load(open(sys.argv[1]))" "$tmp_path" >/dev/null 2>&1; then
    echo "write_evidence_record: internal error — wrote invalid JSON to '$tmp_path'" >&2
    rm -f -- "$tmp_path"
    return 1
  fi

  if ! mv -f -- "$tmp_path" "$out_path"; then
    echo "write_evidence_record: could not move '$tmp_path' into place at '$out_path'" >&2
    rm -f -- "$tmp_path"
    return 1
  fi

  printf '%s\n' "$out_path"
}

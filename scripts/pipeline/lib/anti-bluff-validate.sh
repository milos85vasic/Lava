#!/usr/bin/env bash
# scripts/pipeline/lib/anti-bluff-validate.sh
#
# Anti-bluff Evidence Record gate for the local build-test-distribute
# pipeline (spec 002-build-test-distribute-pipeline, FR-004, research.md
# R-009). Every phase of the pipeline writes one Evidence Record JSON file
# per executed test (see
# specs/002-build-test-distribute-pipeline/contracts/evidence-record.schema.json).
# This library is the mechanical gate that decides whether a record's
# claimed PASS/FAIL/SKIPPED genuinely reflects a real, falsifiable,
# evidenced outcome, or is a bluff per the Anti-Bluff Pact (Sixth Law /
# Seventh Law clause 4 Forbidden Test Patterns). SKIPPED is a first-class
# outcome (not a workaround forced into PASS or FAIL): a test that
# legitimately, honestly did not execute (e.g. a Go test's own
# `t.Skip("POSTGRES_TEST_URL not set")`, or an entire check BLOCKED by a
# missing host precondition) goes through the EXACT SAME anti-bluff rules
# below as PASS/FAIL -- it still needs a real, specific, non-generic
# assertion_summary (quoting the actual skip reason) and a real, non-empty
# raw_output_ref. A SKIPPED record is not exempt from any rule; see
# data-model.md's Evidence Record section for the full design decision.
#
# Public function: validate_evidence_record <path-to-record.json>
#
# CONTRACT (read before calling):
#   - Requires `jq` on PATH.
#   - Mutates the record file IN PLACE: rewrites the `anti_bluff_status`
#     field to exactly "validated" (accept) or "REJECTED: <reason>"
#     (reject). No other field is ever touched.
#   - SIGNALING CONVENTION: exit code only. 0 == accepted/validated,
#     1 == rejected (or a usage/environment error, e.g. missing jq or
#     missing file — those also print a message to stderr). A short
#     human-readable line ("validated" or "REJECTED: <reason>") is also
#     printed to stdout as a courtesy for interactive callers; nothing
#     depends on parsing it, the exit code + the rewritten JSON field are
#     the real signal.
#   - IDEMPOTENT / NOT STICKY: every call re-evaluates the record from its
#     OTHER fields (category/assertion_summary/raw_output_ref) from
#     scratch. The record's own PRIOR anti_bluff_status value is NEVER
#     read as an input to the decision — otherwise a rejected record
#     could never become valid after a real fix, and (worse) a bug could
#     get permanently rubber-stamped by an old "validated" value sitting
#     in the file. Call this function again any time the record's other
#     fields change (e.g. after the underlying test is re-run).
#
# NOTE ON SHELL OPTIONS: this file is designed to be `source`d into other
# scripts, not executed directly (it defines functions; it has no
# standalone entry point). It therefore deliberately does NOT set `-e`/
# `-u`/`-o pipefail` — doing so from a sourced file would silently change
# the CALLING script's shell options. Every function below checks its own
# command results explicitly via `if`/explicit `return`, instead of
# relying on inherited errexit behavior. This also sidesteps a well-known
# bash gotcha: `set -e` is suppressed while a function is invoked as the
# condition of `if`/`while`/`&&`/`||`, which could otherwise make one
# Evidence Record's *expected* internal rejection silently swallow a
# real, unrelated failure occurring inside that same guarded call.
#
# RULES (first match wins; order matches this component's task spec):
#   0. (defensive, not one of the 4 mandated rules) the record is not
#      parseable JSON -> rejected with a generic "malformed" reason.
#   0b. (defensive, not one of the 4 mandated rules; added alongside the
#      SKIPPED result value) the record's `result` field is not one of
#      the schema's enum values (PASS, FAIL, SKIPPED) -> rejected. Without
#      this check the function never inspected `result`'s value at all,
#      so a record claiming an arbitrary/invalid result would have sailed
#      through unexamined as long as its other fields looked fine -- a
#      latent gap this explicit check closes as a side effect of wiring
#      in SKIPPED as a real, valid, mechanically-recognized value rather
#      than merely an unenforced string the schema happens to allow.
#   0c. (defensive, not one of the 4 mandated rules; added 2026-08-21) a
#      required field from evidence-record.schema.json's "required" list is
#      missing or empty -> rejected. Rule 0 above claimed to cover this
#      ("or is missing required fields") and did not: `jq -r '.foo // empty'`
#      exits 0 and yields "" for an ABSENT field, so absence never reached
#      the `!` test. The consequence was a vacuous pass of every rule below
#      -- an empty assertion_summary contains none of rule 1's bluff
#      phrases, and an empty raw_output_ref resolves to "${record_dir}/",
#      the record's OWN directory, which satisfies both rule 2's -e test and
#      rule 3's -s test (a directory inode has a non-zero size). A record
#      that asserted nothing and pointed at nothing was stamped "validated".
#      Regression coverage:
#      tests/pipeline/test_anti_bluff_missing_evidence_fields.sh CASE 1/2.
#   0d. (defensive, added alongside 0c) category is not one of the 8 enum
#      values in evidence-record.schema.json -> rejected. Rule 4 is
#      selected by an exact string comparison against
#      "real-device-challenge"; an unrecognized category (a typo, a
#      renamed category) therefore silently skips it. Rejecting the
#      unrecognized value outright is the fail-closed reading -- the same
#      reasoning rule 0b already applies to `result`.
#   1. assertion_summary is a generic/bluff string per the Seventh Law
#      clause 4 Forbidden Test Patterns list: "did not crash",
#      "process completed", or "no crash" appears with no other specific
#      content in the summary once removed (<=20 trimmed characters left
#      over), or the record is exactly "build successful" with nothing
#      else specific said (exact match) — see judgment call (a) below
#      (corrected 2026-08-21 after a real false-positive was found).
#   2. raw_output_ref does not point at a regular file (missing, or a
#      directory / other non-file). Tested with -f, NOT -e: a wrapper that
#      passes its raw DIRECTORY instead of a captured-output file reaches
#      this validator intact (write_evidence_record deliberately does not
#      inspect the path -- see its header), and -e/-s are both TRUE for a
#      directory, so the two rules whose entire job is "there is real
#      captured output behind this claim" both passed on an empty
#      directory. Regression coverage:
#      tests/pipeline/test_anti_bluff_missing_evidence_fields.sh CASE 3.
#   3. raw_output_ref points at a file that exists but is empty (0 bytes).
#   4. category == "real-device-challenge" and no falsifiability
#      rehearsal marker is found anywhere in the record's own text.
#   5. (none of the above) -> validated.
#
# DOCUMENTED JUDGMENT CALLS:
#
#   (a) Rule 1's "build successful" phrase is checked by EXACT match
#       (case-insensitive, trimmed, trailing '.'/'!' stripped) — a
#       genuinely specific assertion_summary MAY legitimately quote
#       "BUILD SUCCESSFUL" as one verbatim fact among several real
#       specifics without itself being a bluff. The other three phrases
#       ("did not crash", "process completed", "no crash") were
#       ORIGINALLY substring-matched anywhere in the string on the theory
#       that they "describe nothing but absence-of-crash / absence-of-
#       detail no matter what else surrounds them" — that theory was
#       falsified by real evidence on 2026-08-21: a real run of this
#       project's actual Kotlin test suite produced 5 genuine PASS
#       records rejected as false positives, because
#       phase-02-test-kotlin.sh's own PASS-path template quotes the real
#       test's own descriptive name verbatim, and several real test
#       authors chose names containing "no crash" as legitimate, specific
#       content (e.g. "observe emits empty list when key holder is locked
#       — no crash on search path" — a real, meaningful description of
#       what the test verifies, not a lazy summary). Corrected to use the
#       same "nothing else specific said" standard as "build successful":
#       reject only when removing the phrase leaves negligible (<=20
#       trimmed alphanumeric characters) content behind.
#
#   (b) Rule 4 (real-device-challenge falsifiability marker) mirrors
#       scripts/check-challenge-discrimination.sh's detection logic
#       (the "FALSIFIABILITY REHEARSAL" / "§6.AB-discrimination:"
#       markers), but CANNOT literally reuse its "or a companion JSON
#       file with a falsifiability_rehearsal/discrimination field"
#       branch: the Evidence Record schema
#       (specs/002-build-test-distribute-pipeline/contracts/evidence-record.schema.json)
#       declares "additionalProperties": false, so there is no room for
#       a new `falsifiability_ref` property without breaking schema
#       validation. Instead, this validator checks BOTH of the real-text
#       fields the schema already gives us: the record's own
#       `assertion_summary`, AND the full content of the file the
#       record's `raw_output_ref` already points at (which, by the time
#       rule 4 runs, rules 2/3 have already proven exists and is
#       non-empty real captured output). This is the schema-compliant
#       equivalent of the original script's "or a companion file"
#       branch — raw_output_ref IS the companion evidence here, reusing
#       a field the schema already defines rather than inventing one it
#       forbids.
#
# Inheritance: HelixConstitution anti-bluff covenant + Lava §6.J/§6.L/
# Sixth+Seventh Laws + §6.AB. Classification: project-specific (this
# script is Lava-side bash implementing this project's own pipeline
# feature; the anti-bluff principle it mechanizes is universal).

# _abv_reject <record_path> <reason> — overwrites anti_bluff_status to
# "REJECTED: <reason>", prints the same to stdout, returns 1.
_abv_reject() {
  local record_path="$1"
  local reason="$2"
  local tmp
  tmp="$(mktemp "${record_path}.XXXXXX")" || return 1
  if ! jq --arg status "REJECTED: ${reason}" '.anti_bluff_status = $status' \
      "$record_path" > "$tmp" 2>/dev/null; then
    rm -f "$tmp"
    echo "validate_evidence_record: failed to write REJECTED status to $record_path" >&2
    return 1
  fi
  mv "$tmp" "$record_path"
  echo "REJECTED: ${reason}"
  return 1
}

# _abv_accept <record_path> — overwrites anti_bluff_status to exactly
# "validated", prints the same to stdout, returns 0.
_abv_accept() {
  local record_path="$1"
  local tmp
  tmp="$(mktemp "${record_path}.XXXXXX")" || return 1
  if ! jq '.anti_bluff_status = "validated"' "$record_path" > "$tmp" 2>/dev/null; then
    rm -f "$tmp"
    echo "validate_evidence_record: failed to write validated status to $record_path" >&2
    return 1
  fi
  mv "$tmp" "$record_path"
  echo "validated"
  return 0
}

validate_evidence_record() {
  local record_path="${1:-}"

  if [[ -z "$record_path" ]]; then
    echo "validate_evidence_record: usage: validate_evidence_record <path-to-record.json>" >&2
    return 1
  fi
  if [[ ! -f "$record_path" ]]; then
    echo "validate_evidence_record: no such file: $record_path" >&2
    return 1
  fi
  if ! command -v jq >/dev/null 2>&1; then
    echo "validate_evidence_record: jq is required but not found on PATH" >&2
    return 1
  fi

  local record_dir category assertion_summary raw_output_ref result test_id command_str
  record_dir="$(cd "$(dirname "$record_path")" && pwd)"

  # Rule 0 (defensive, not one of the 4 mandated rules): malformed JSON.
  if ! category="$(jq -r '.category // empty' "$record_path" 2>/dev/null)" \
      || ! assertion_summary="$(jq -r '.assertion_summary // empty' "$record_path" 2>/dev/null)" \
      || ! raw_output_ref="$(jq -r '.raw_output_ref // empty' "$record_path" 2>/dev/null)" \
      || ! result="$(jq -r '.result // empty' "$record_path" 2>/dev/null)" \
      || ! test_id="$(jq -r '.test_id // empty' "$record_path" 2>/dev/null)" \
      || ! command_str="$(jq -r '.command // empty' "$record_path" 2>/dev/null)"; then
    _abv_reject "$record_path" "record is not valid JSON or is missing required fields"
    return $?
  fi

  # Rule 0b (defensive, not one of the 4 mandated rules): result must be
  # one of the schema's enum values. PASS/FAIL/SKIPPED are all real,
  # mechanically-recognized outcomes -- anything else is rejected outright
  # rather than silently accepted.
  if [[ "$result" != "PASS" && "$result" != "FAIL" && "$result" != "SKIPPED" ]]; then
    _abv_reject "$record_path" "record's result field must be PASS, FAIL, or SKIPPED (got: '${result}')"
    return $?
  fi

  # Rule 0c: every field evidence-record.schema.json marks "required" must
  # be present AND non-empty. `jq -r '.x // empty'` cannot distinguish an
  # absent field from an empty one -- both arrive here as "" -- so this is
  # the only place absence can be caught, and it must be caught BEFORE the
  # rules below, every one of which an empty value satisfies vacuously.
  # Explicit `if` blocks rather than `[[ ... ]] && arr+=(...)`: a trailing
  # false `&&` chain returns 1, which under a caller's inherited errexit
  # would abort mid-function. This file's NOTE ON SHELL OPTIONS (above)
  # commits to explicit checks for exactly that reason.
  local _abv_missing=()
  local _abv_field
  for _abv_field in test_id category command assertion_summary raw_output_ref; do
    case "$_abv_field" in
      test_id)           [[ -n "$test_id" ]] || _abv_missing+=("$_abv_field") ;;
      category)          [[ -n "$category" ]] || _abv_missing+=("$_abv_field") ;;
      command)           [[ -n "$command_str" ]] || _abv_missing+=("$_abv_field") ;;
      assertion_summary) [[ -n "$assertion_summary" ]] || _abv_missing+=("$_abv_field") ;;
      raw_output_ref)    [[ -n "$raw_output_ref" ]] || _abv_missing+=("$_abv_field") ;;
    esac
  done
  if [[ "${#_abv_missing[@]}" -gt 0 ]]; then
    _abv_reject "$record_path" "required field(s) missing or empty: ${_abv_missing[*]} (a record that asserts nothing and points at nothing is not evidence)"
    return $?
  fi

  # Rule 0d: category must be one of the schema's 8 enum values. Rule 4 is
  # selected by an exact match on "real-device-challenge", so an
  # unrecognized category would silently skip it.
  local _abv_known_category=1
  case "$category" in
    kotlin-unit|go-unit-integration|real-binary-contract|real-device-challenge|hermetic-script|stress-chaos|release-canary|constitutional-gate-sweep) ;;
    *) _abv_known_category=0 ;;
  esac
  if [[ "$_abv_known_category" -ne 1 ]]; then
    _abv_reject "$record_path" "category '${category}' is not one of evidence-record.schema.json's 8 enum values"
    return $?
  fi

  local resolved_raw_ref
  if [[ "$raw_output_ref" == /* ]]; then
    resolved_raw_ref="$raw_output_ref"
  else
    resolved_raw_ref="${record_dir}/${raw_output_ref}"
  fi

  # ----- Rule 1: generic/bluff assertion_summary ------------------------
  local lower_summary pattern
  lower_summary="$(printf '%s' "$assertion_summary" | tr '[:upper:]' '[:lower:]')"

  # Real-world false-positive found during this feature's own verification
  # pass (2026-08-21): phase-02-test-kotlin.sh's PASS-path template quotes
  # the REAL test's own name verbatim inside an otherwise rich, specific
  # summary (e.g. `real JUnit XML testcase for "observe emits empty list
  # when key holder is locked -- no crash on search path" reports no
  # <failure>/<error> element (Gradle test task genuinely executed it in
  # 0.01s)`) -- a genuinely-passing test whose AUTHOR chose a descriptive
  # name that happens to contain "no crash" as real, specific content, not
  # a lazy bluff. The original substring-anywhere design (see judgment
  # call (a) below, kept for its own historical record) rejected 5 such
  # real PASS records from a single real run across this project's actual
  # test suite -- concrete proof the "no legitimate summary would ever
  # contain one of these phrases" assumption was false. Fix: apply the
  # SAME "nothing else specific said" test the "build successful" phrase
  # already used (judgment call (a) below) to these three phrases too --
  # reject only when removing every occurrence of the phrase leaves
  # negligible (<=20 trimmed characters, chosen to comfortably exceed any
  # bare phrase + minor punctuation padding like "no crash." while
  # confidently under any real added specific) content behind.
  for pattern in "did not crash" "process completed" "no crash"; do
    if [[ "$lower_summary" == *"$pattern"* ]]; then
      local remainder
      remainder="$(printf '%s' "$lower_summary" | sed "s/${pattern}//g")"
      remainder="$(printf '%s' "$remainder" | sed -E 's/[^a-z0-9]+//g')"
      if [[ ${#remainder} -le 20 ]]; then
        _abv_reject "$record_path" "assertion_summary matches generic bluff pattern '${pattern}' with no other specific content"
        return $?
      fi
    fi
  done

  # "build successful" with nothing else specific said: exact match only
  # (trimmed, punctuation-stripped) — see judgment call (a) above.
  local normalized_summary
  normalized_summary="$(printf '%s' "$lower_summary" | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//; s/[.!]+$//')"
  if [[ "$normalized_summary" == "build successful" ]]; then
    _abv_reject "$record_path" "assertion_summary is exactly 'build successful' with nothing else specific said"
    return $?
  fi

  # ----- Rule 2: raw_output_ref is not a regular file --------------------
  # -f, not -e: a directory satisfies -e (and -s), so testing existence
  # alone let a raw DIRECTORY stand in for captured output.
  if [[ ! -f "$resolved_raw_ref" ]]; then
    if [[ -d "$resolved_raw_ref" ]]; then
      _abv_reject "$record_path" "raw_output_ref '${raw_output_ref}' is a directory, not a captured-output file (resolved: ${resolved_raw_ref})"
    else
      _abv_reject "$record_path" "raw_output_ref '${raw_output_ref}' does not exist (resolved: ${resolved_raw_ref})"
    fi
    return $?
  fi

  # ----- Rule 3: raw_output_ref exists but is empty ----------------------
  if [[ ! -s "$resolved_raw_ref" ]]; then
    _abv_reject "$record_path" "raw_output_ref '${raw_output_ref}' exists but is empty (0 bytes)"
    return $?
  fi

  # ----- Rule 4: real-device-challenge lacking a falsifiability marker ---
  if [[ "$category" == "real-device-challenge" ]]; then
    local raw_content combined
    raw_content="$(cat "$resolved_raw_ref" 2>/dev/null || true)"
    combined="$(printf '%s\n%s\n' "$assertion_summary" "$raw_content")"
    # SIGPIPE/pipefail fix (2026-08-22). This was `printf '%s' "$combined" |
    # grep -qE …`. `grep -q` exits on first match, closing the pipe; `printf`
    # then takes SIGPIPE and exits 141, and `set -o pipefail` makes that 141 the
    # pipeline's status — which `if !` inverts into "marker not found". Since
    # $combined is the record's ENTIRE raw_output_ref, and real-device-challenge
    # logcats in this repo run 1.4 MB - 5 MB, this rule was decided by FILE SIZE
    # rather than by the marker. Verified directly: the identical marker at line
    # 1 of a 25-byte file was FOUND, and of a 2 MB file was NOT FOUND. It failed
    # CLOSED (rejecting evidence that genuinely carried its marker), so no bluff
    # slipped through — but it would have rejected legitimate real-device
    # evidence at gate time. A herestring has no upstream process to signal.
    if ! grep -qE 'FALSIFIABILITY[ \t]+REHEARSAL|§6\.AB-discrimination:' <<< "$combined"; then
      _abv_reject "$record_path" "category is real-device-challenge but no FALSIFIABILITY REHEARSAL / §6.AB-discrimination: marker found in assertion_summary or raw_output_ref content"
      return $?
    fi
  fi

  # ----- All rules passed -------------------------------------------------
  _abv_accept "$record_path"
  return $?
}

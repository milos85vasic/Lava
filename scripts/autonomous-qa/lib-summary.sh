#!/usr/bin/env bash
# scripts/autonomous-qa/lib-summary.sh
# ---------------------------------------------------------------------------
# Pure, sourceable helpers for aggregating per-iteration verdict.json files
# into the matrix summary.md. Kept side-effect-free so they can be unit-tested
# in isolation (see tests/autonomous-qa/test_run_matrix_verdict_parse.sh).
#
# Historical bug this file fixes: the inline extraction anchored the value with
#   grep -oE '[A-Z]+$'
# but the grep -o match "verdict": "PASS" ends in a double-quote, so [A-Z]+$
# never matched -> the pipeline failed -> the `|| echo FAIL` fallback fired on
# EVERY row, mislabelling genuine PASS/SKIP iterations as FAIL in the totals.
# ---------------------------------------------------------------------------

# qa_parse_field <file> <key>
#   Extract the value of a top-level <key> from a flat verdict.json object.
#   Handles two value shapes used by run-iteration.sh:
#     - quoted ALL-CAPS string (e.g.  "verdict": "PASS")
#     - bare integer            (e.g.  "tests": 5)
#   Prints the value and returns 0 on success; prints nothing and returns 1
#   when the file is missing or the key has no parseable value (the caller then
#   applies its own `|| echo FAIL` / `|| echo 0` fallback).
qa_parse_field() {
  local file="$1" key="$2" val
  [[ -f "$file" ]] || return 1

  # 1) quoted uppercase string value (verdict). Tolerate any spacing after ':'.
  val="$(grep -oE "\"$key\": *\"[A-Z]+\"" "$file" 2>/dev/null | grep -oE '[A-Z]+' | tail -1)"
  if [[ -n "$val" ]]; then
    printf '%s' "$val"
    return 0
  fi

  # 2) bare integer value (tests/failures/errors/skipped).
  val="$(grep -oE "\"$key\": *[0-9]+" "$file" 2>/dev/null | grep -oE '[0-9]+' | tail -1)"
  if [[ -n "$val" ]]; then
    printf '%s' "$val"
    return 0
  fi

  return 1
}

# qa_classify <verdict>
#   Map a raw verdict token to its summary bucket. Anything that is not an
#   explicit PASS or SKIP counts as FAIL (defensive: an unknown/garbled verdict
#   is a failure, never a silent pass).
qa_classify() {
  case "$1" in
    PASS) printf 'PASS' ;;
    SKIP) printf 'SKIP' ;;
    *)    printf 'FAIL' ;;
  esac
}

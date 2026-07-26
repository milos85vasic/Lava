# `scripts/autonomous-qa/lib-summary.sh` — User Guide

**Last verified:** 2026-07-26 (LVA-018 script-docs backfill cycle)
**Inheritance:** HelixConstitution §11.4.18 (script docs); Lava §6.J (unknown verdict = FAIL)
**Classification:** project-specific

## Overview

Pure, sourceable helpers for aggregating the per-iteration `verdict.json` files
(written by `run-iteration.sh`) into the matrix `summary.md`. Kept
side-effect-free so they can be unit-tested in isolation — see
`tests/autonomous-qa/test_run_matrix_verdict_parse.sh`.

## Why it exists (historical bug)

The inline extraction it replaced anchored the value with `grep -oE '[A-Z]+$'`,
but the `grep -o` match `"verdict": "PASS"` ends in a double-quote, so
`[A-Z]+$` never matched → the pipeline failed → the `|| echo FAIL` fallback
fired on EVERY row, mislabelling genuine PASS/SKIP iterations as FAIL in the
matrix totals. These helpers are the fixed, test-covered extraction path.

## Functions

### `qa_parse_field <file> <key>`

Extract the value of a top-level `<key>` from a flat `verdict.json` object.
Handles the two value shapes `run-iteration.sh` emits:

- quoted ALL-CAPS string — `"verdict": "PASS"`
- bare integer — `"tests": 5`

Prints the value and returns `0` on success; prints nothing and returns `1`
when the file is missing or the key has no parseable value (the caller then
applies its own `|| echo FAIL` / `|| echo 0` fallback).

### `qa_classify <verdict>`

Map a raw verdict token to its summary bucket:

| Token | Bucket |
|---|---|
| `PASS` | `PASS` |
| `SKIP` | `SKIP` |
| anything else | `FAIL` (defensive — an unknown/garbled verdict is a failure, never a silent pass) |

## Usage

```bash
source scripts/autonomous-qa/lib-summary.sh
v="$(qa_parse_field "$evid/verdict.json" verdict || echo FAIL)"
t="$(qa_parse_field "$evid/verdict.json" tests   || echo 0)"
case "$(qa_classify "$v")" in PASS) … ;; SKIP) … ;; *) … ;; esac
```

## Hermetic test

`tests/autonomous-qa/test_run_matrix_verdict_parse.sh` — exercises both value
shapes, the missing-file/missing-key returns, and the classification table.

## Companion files

- `scripts/autonomous-qa/run-matrix.sh` — the consumer
- `scripts/autonomous-qa/run-iteration.sh` — the verdict.json producer

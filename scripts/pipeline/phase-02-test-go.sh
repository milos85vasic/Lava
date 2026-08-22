#!/usr/bin/env bash
# phase-02-test-go.sh — Test wrappers for the `lava-api-go` module, covering
# TWO of the 8 FR-002 Evidence Record categories (tasks.md T022 + T023):
#   - "go-unit-integration"  — every Go unit/integration test under
#     lava-api-go/ EXCEPT the tests/contract package (see below).
#   - "real-binary-contract" — every test under lava-api-go/tests/contract/,
#     which specifically exercises real built binaries (cmd/lava-api-go,
#     cmd/healthprobe) and real compose/Dockerfile fixtures per the Anti-Bluff
#     Pact's §6.A "real-binary contract tests" clause.
#
# ---------------------------------------------------------------------------
# Why one `go test` invocation, not two
# ---------------------------------------------------------------------------
# `lava-api-go/tests/contract/` has no go.mod of its own — it is
# `package contract` inside the single `digital.vasic.lava.apigo` module
# (confirmed via `go list ./...`, which reports
# `digital.vasic.lava.apigo/tests/contract` as an ordinary package of the
# same module). The project's own `lava-api-go/Makefile` `test:` target —
#   GOMAXPROCS=2 go test -race -count=1 ./...
# — already runs it: `./...` recurses into every package including
# tests/contract. This script does NOT invent a separate invocation for the
# contract tests; it reuses the Makefile's exact flags (GOMAXPROCS=2, -race,
# -count=1) for ONE combined run, and appends `-json` so per-test PASS/FAIL/
# SKIP outcomes can be parsed mechanically. `-json` only changes Go's own
# OUTPUT FORMAT (structured protocol instead of human-readable text) — it
# does not change which tests run, in what order, or with what result, so
# reusing the Makefile's `test:` target here is a byte-for-byte match on
# behavior, not a reinvention of it. Which of the two Evidence Record
# categories each individual test belongs to is decided per-test from the
# real `Package` field `go test -json` reports for that test (anything under
# `digital.vasic.lava.apigo/tests/contract` → real-binary-contract; anything
# else → go-unit-integration) — never assumed from a static list.
#
# ---------------------------------------------------------------------------
# Real-Postgres (-Pintegration=true-equivalent) tests are honestly skipped,
# never bluffed
# ---------------------------------------------------------------------------
# lava-api-go has no Gradle-style `-Pintegration=true` flag (that convention
# is Android/Gradle-side). Its Go-side equivalent is the `POSTGRES_TEST_URL`
# environment variable: tests that need a real Postgres instance (e.g.
# internal/storage's `TestPostgresStorageConformance`, `TestCrossBackendParity`,
# `TestNilEmptyContract_Postgres`) call `t.Skip(...)` when it is unset — see
# `internal/storage/postgres_test.go`. `scripts/run-test-pg.sh` is the
# existing helper that boots a *disposable, throwaway* podman/docker Postgres
# container and sets `POSTGRES_TEST_URL` before invoking `go test`. This
# wrapper deliberately does NOT invoke `scripts/run-test-pg.sh` or otherwise
# set `POSTGRES_TEST_URL` itself: at task-authoring time, this host already
# has a *different*, long-running, `healthy` Postgres container (`lava-postgres`,
# `docker.io/library/postgres:16-alpine`, up 5h) that is the project's own
# live application database (the `lava-api-go` service container runs
# against it) — not a disposable test fixture, and its credentials are not
# in this script's scope. Repurposing a live application database as a test
# target, even into a dedicated schema, is not "trivially available test
# infrastructure"; it is a production dependency this script has no business
# touching. So: `POSTGRES_TEST_URL` is left unset, the gated tests honestly
# self-report SKIP (captured and enumerated in this script's own summary —
# see "Skips are reported, never silently dropped or force-PASSed" below),
# and nothing about that is hidden.
#
# ---------------------------------------------------------------------------
# Evidence Records: PASS/FAIL/SKIPPED, per-test, real per-test output captured
# ---------------------------------------------------------------------------
# For every individual Go test (`go test -json`'s per-Test pass/fail
# entries — this includes named subtests, e.g. `TestContract/get_forum
# .golden.json`, each of which gets its own Evidence Record), this script:
#   1. Captures that ONE test's own real captured stdout (the `Output`
#      lines `go test -json` attributes to it) to its own dedicated raw file
#      under `<phase_dir>/raw/go/`.
#   2. Calls `write_evidence_record` (scripts/pipeline/lib/evidence.sh) with
#      `category: real-binary-contract` when the test's own `Package` is
#      (or is nested under) `digital.vasic.lava.apigo/tests/contract`,
#      `category: go-unit-integration` otherwise.
#   3. Calls `validate_evidence_record` (scripts/pipeline/lib/anti-bluff-
#      validate.sh) on the freshly-written record and records whether it was
#      accepted (`validated`) or `REJECTED: <reason>`.
# `assertion_summary` is never a generic phrase: for a PASS it is the real
# `--- PASS: <name> (<elapsed>s)` line Go itself printed; for a FAIL it is
# the real `<file>.go:<line>: <message>` assertion line(s) Go captured (the
# actual `t.Errorf`/`t.Fatalf` text), falling back to the real `--- FAIL:`
# line only if no such assertion line was captured.
#
# A genuine package-level build/compile failure (or a fatal panic that kills
# a test binary before ANY of its individual tests report a per-test
# pass/fail/skip) is NOT silently absorbed into "0 tests, exit non-zero,
# nothing recorded" — see the `PKGFAIL` handling in the embedded parser
# below: if a package reports `Action: fail` and not one of its own tests
# ever reported a per-test terminal outcome, that IS a real, evidenced
# failure (the compiler's or runtime's own diagnostic output) and gets its
# own synthetic Evidence Record (`test_id` suffixed `#(build)`) rather than
# vanishing from the Evidence Record set entirely.
#
# ---------------------------------------------------------------------------
# Skips are reported, never silently dropped or force-PASSed
# ---------------------------------------------------------------------------
# `contracts/evidence-record.schema.json`'s `result` enum is `["PASS", "FAIL",
# "SKIPPED"]` — `SKIPPED` was added specifically for an honestly-reported
# non-execution (see data-model.md's Evidence Record section). Writing a PASS
# record for a test that did not actually execute (e.g. the Postgres-gated
# tests above) would be exactly the kind of Anti-Bluff Pact violation this
# project's own Sixth/Seventh Laws forbid: it would claim a behavior was
# verified when it was not. So this script writes ONE `SKIPPED` Evidence
# Record per `go test -json` SKIP outcome, quoting the test's own real,
# captured skip reason verbatim (the actual line Go printed, e.g.
# "postgres_test.go:22: POSTGRES_TEST_URL not set; run scripts/run-test-pg.sh
# (real podman Postgres required)") as its `assertion_summary`, and
# anti-bluff-validates it exactly like any PASS/FAIL record — never a bare
# console note. Skips are additionally enumerated in this script's own final
# summary (`SKIP (n): ...`), separate from the PASS/FAIL/validated/rejected
# counts, so a downstream reader can see exactly which tests did not run and
# exactly why, without this wrapper pretending they did or letting an honest
# skip masquerade as a pipeline failure.
#
# ---------------------------------------------------------------------------
# Usage
# ---------------------------------------------------------------------------
#   scripts/pipeline/phase-02-test-go.sh [repo-path] [phase-dir]
#
# repo-path  — Lava monorepo root. Defaults to `git rev-parse --show-toplevel`
#              (works from anywhere inside the real repo, matching every
#              other phase-*.sh script's convention).
# phase-dir  — the phase-02 evidence directory this run's Evidence Records
#              are written under (per data-model.md:
#              "<run_dir>/phase-<NN>/<category>/<test_id>.json"). Defaults to
#              a freshly-generated
#              "<repo-path>/.lava-ci-evidence/pipeline-runs/<UTC-run-id>/phase-02"
#              so this script is independently runnable/verifiable on its own
#              (per FR-005) even before the full orchestrator
#              (`scripts/pipeline/phase-02-test.sh`, not yet built) exists to
#              pass in a shared run's phase_dir explicitly.
#
# ---------------------------------------------------------------------------
# A non-zero `go test` exit that no Evidence Record explains is itself a
# failure (added 2026-08-21 after a wrapper audit)
# ---------------------------------------------------------------------------
# GO_TEST_RC was captured and only ever printed: the verdict came exclusively
# from the per-test events the parser recognized. The PKGFAIL path above does
# cover a package that fails with NO per-test outcome (a compile error —
# verified working), but it is suppressed the moment any test in that package
# reported an outcome. A package whose tests all PASS and which then fails AS
# A PACKAGE — the canonical `TestMain` shape, where teardown after `m.Run()`
# returns 0 fails (exactly how lava-api-go's container/database integration
# suites are written) — was therefore recorded as all-PASS while `go test`
# genuinely exited non-zero. Observed verbatim against a real go1.26.2 run of
# that shape:
#     phase-02-test-go: go test exited 1; parsing ...
#       total tests recorded: 1 / PASS: 1 / FAIL: 0
#     phase-02-test-go: PASSED — all recorded tests PASS, ...   (exit 0)
# Now: whenever `go test` exits non-zero and NOT ONE parsed per-test outcome
# is a FAIL, this script writes one synthetic FAIL Evidence Record quoting the
# real package-level output / build diagnostics / stderr it captured, and
# exits 1. Regression coverage: tests/pipeline/test_phase_02_go_wrapper.sh
# CASE 2.
#
# Exit codes:
#   0 - `go test` ran, at least one per-test outcome was parsed, every
#       Evidence Record was written and validated, every recorded test result
#       was PASS (no FAIL, no anti-bluff rejection), AND `go test`'s own exit
#       code was 0 (or was non-zero and fully explained by a recorded FAIL).
#       Real SKIPs do not affect this.
#   1 - at least one real Go test genuinely reported FAIL, at least one
#       Evidence Record was REJECTED by anti-bluff validation, or `go test`
#       exited non-zero with no recorded FAIL explaining it.
#   2 - usage/precondition error (repo path, lava-api-go/ dir, or Makefile
#       missing).
#   3 - internal wrapper error (required tool missing — jq/python3/go — or
#       `go test -json` produced literally zero parseable per-test events,
#       which means either the Go toolchain failed catastrophically before
#       running any test, or no test was compiled into the run at all (a
#       stray build tag excluding every `_test.go` file is the realistic
#       trigger) — the real stderr is printed for diagnosis. Until
#       2026-08-21 this exit code was documented but only ever reached when
#       the event-stream FILE was empty; a stream full of events carrying no
#       per-test outcome reported "PASSED — all recorded tests PASS" with
#       zero Evidence Records instead. Regression coverage:
#       tests/pipeline/test_phase_02_go_wrapper.sh CASE 3.
#
# This script writes ONLY Evidence Records (JSON) + their raw-output
# companions under `<phase_dir>/`; it never touches git state, never pushes,
# never distributes anything.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# shellcheck source=scripts/pipeline/lib/evidence.sh
source "${SCRIPT_DIR}/lib/evidence.sh"
# shellcheck source=scripts/pipeline/lib/anti-bluff-validate.sh
source "${SCRIPT_DIR}/lib/anti-bluff-validate.sh"

for tool in jq python3 go; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "phase-02-test-go: FAILED — required tool '$tool' not found on PATH" >&2
    exit 3
  fi
done

REPO_PATH="${1:-}"
if [[ -z "$REPO_PATH" ]]; then
  REPO_PATH="$(git rev-parse --show-toplevel)"
fi

LAVA_API_GO_DIR="${REPO_PATH}/lava-api-go"

if [[ ! -d "$LAVA_API_GO_DIR" ]]; then
  echo "phase-02-test-go: precondition failed — no lava-api-go/ directory under '${REPO_PATH}'" >&2
  exit 2
fi
if [[ ! -f "${LAVA_API_GO_DIR}/Makefile" ]]; then
  echo "phase-02-test-go: precondition failed — lava-api-go/Makefile not found" >&2
  exit 2
fi

PHASE_DIR="${2:-}"
if [[ -z "$PHASE_DIR" ]]; then
  RUN_ID="$(date -u +%Y-%m-%dT%H-%M-%SZ)"
  PHASE_DIR="${REPO_PATH}/.lava-ci-evidence/pipeline-runs/${RUN_ID}/phase-02"
fi

RAW_DIR="${PHASE_DIR}/raw/go"
mkdir -p "$RAW_DIR"

JSONL_PATH="${PHASE_DIR}/raw/go-test-json-stream.jsonl"
STDERR_PATH="${PHASE_DIR}/raw/go-test-stderr.log"

echo "phase-02-test-go: running lava-api-go's own 'make test' invocation (GOMAXPROCS=2 go test -race -count=1 ./...), +\"-json\" for structured per-test parsing, in ${LAVA_API_GO_DIR}"
echo "phase-02-test-go: raw JSON test-event stream -> ${JSONL_PATH}"

set +e
(
  cd "$LAVA_API_GO_DIR"
  export GOMAXPROCS=2
  go test -race -count=1 -json ./...
) > "$JSONL_PATH" 2> "$STDERR_PATH"
GO_TEST_RC=$?
set -e

if [[ ! -s "$JSONL_PATH" ]]; then
  echo "phase-02-test-go: FAILED — 'go test -json' produced no output at all (toolchain-level failure, exit ${GO_TEST_RC}); real stderr:" >&2
  cat "$STDERR_PATH" >&2
  exit 3
fi

echo "phase-02-test-go: go test exited ${GO_TEST_RC}; parsing ${JSONL_PATH} for per-test outcomes"

# Embedded parser: walks the go test -json event stream and, for every
# individual test, emits one record ("TEST" for pass/fail, "SKIP" for skip,
# "PKGFAIL" for a genuine package-level build/panic failure with no per-test
# breakdown at all) on stdout, RS ("\x1e")-delimited records of
# US ("\x1f")-delimited fields, so the bash driver loop below can consume
# them via plain `read -d`/IFS splitting without depending on any field ever
# containing those two control bytes (test names, package import paths,
# commands, and single-line summaries never legitimately contain them).
PARSER_PY="$(mktemp)"
trap 'rm -f "$PARSER_PY"' EXIT

cat > "$PARSER_PY" <<'PYEOF'
import json
import os
import re
import sys

jsonl_path, raw_dir = sys.argv[1], sys.argv[2]


def sanitize(s):
    return re.sub(r'[^A-Za-z0-9._-]', '_', s)


def categorize(pkg):
    if pkg == 'digital.vasic.lava.apigo/tests/contract' or pkg.startswith(
        'digital.vasic.lava.apigo/tests/contract/'
    ):
        return 'real-binary-contract'
    return 'go-unit-integration'


def build_run_command(pkg, test):
    parts = test.split('/')
    pattern = '/'.join('^' + re.escape(p) + '$' for p in parts)
    return (
        "cd lava-api-go && GOMAXPROCS=2 go test -race -count=1 "
        f"-run '{pattern}' {pkg}"
    )


def emit(fields):
    sys.stdout.write('\x1f'.join(fields) + '\x1e')


def one_line(s, limit=1000):
    return s.replace('\n', ' ').replace('\r', ' ').strip()[:limit]


test_output = {}       # (pkg, test) -> [output lines]
pkg_output = {}         # pkg -> [output lines]  (package-level, Test is None)
pkg_has_test_event = set()

with open(jsonl_path, encoding='utf-8') as f:
    for line in f:
        line = line.strip()
        if not line:
            continue
        try:
            ev = json.loads(line)
        except json.JSONDecodeError:
            continue

        pkg = ev.get('Package', '')
        test = ev.get('Test')
        action = ev.get('Action')

        if test is None:
            if action == 'output':
                pkg_output.setdefault(pkg, []).append(ev.get('Output', ''))
            elif action == 'fail' and pkg not in pkg_has_test_event:
                # Genuine package-level failure with NO per-test breakdown:
                # a compile error or a fatal panic that killed the test
                # binary before any individual test reported pass/fail/skip.
                # This must never be silently dropped.
                out_text = ''.join(pkg_output.get(pkg, []))
                lines = [ln for ln in out_text.splitlines() if ln.strip()]
                summary = (
                    ' | '.join(l.strip() for l in lines[:3])
                    if lines
                    else 'go test reported package-level FAIL with no captured output'
                )
                fname = sanitize(pkg) + '__BUILD.log'
                fpath = os.path.join(raw_dir, fname)
                with open(fpath, 'w', encoding='utf-8') as rf:
                    rf.write(out_text if out_text else '(no captured output)\n')
                test_id = f'{pkg}#(build)'
                command = f'cd lava-api-go && GOMAXPROCS=2 go test -race -count=1 {pkg}'
                emit(['PKGFAIL', test_id, categorize(pkg), command, 'FAIL', one_line(summary), fpath])
            continue

        key = (pkg, test)
        if action == 'output':
            test_output.setdefault(key, []).append(ev.get('Output', ''))
            continue

        if action not in ('pass', 'fail', 'skip'):
            continue

        pkg_has_test_event.add(pkg)
        out_text = ''.join(test_output.get(key, []))
        lines = out_text.splitlines()
        category = categorize(pkg)
        test_id = f'{pkg}#{test}'
        fname = sanitize(pkg) + '__' + sanitize(test) + '.log'
        fpath = os.path.join(raw_dir, fname)
        with open(fpath, 'w', encoding='utf-8') as rf:
            rf.write(out_text if out_text else '(no captured output)\n')

        command = build_run_command(pkg, test)

        if action == 'skip':
            skip_reason = None
            for i, ln in enumerate(lines):
                if '--- SKIP' in ln and i > 0:
                    skip_reason = lines[i - 1].strip()
                    break
            if not skip_reason:
                skip_reason = next((ln.strip() for ln in lines if ln.strip()), 'skipped (no reason captured)')
            summary = f'Genuinely did not execute: {one_line(skip_reason)}'
            emit(['SKIP', test_id, category, command, summary, fpath])
            continue

        result = 'PASS' if action == 'pass' else 'FAIL'

        if action == 'pass':
            pass_line = next((ln.strip() for ln in reversed(lines) if ln.strip().startswith('--- PASS')), None)
            summary = pass_line or f'go test reported PASS for {test_id}'
        else:
            assertion_lines = [ln.strip() for ln in lines if re.search(r'\.go:\d+:', ln)]
            if assertion_lines:
                summary = ' | '.join(assertion_lines[:3])
            else:
                fail_line = next((ln.strip() for ln in reversed(lines) if ln.strip().startswith('--- FAIL')), None)
                summary = fail_line or f'go test reported FAIL for {test_id}'

        emit(['TEST', test_id, category, command, result, one_line(summary), fpath])
PYEOF

total_tests=0
pass_count=0
fail_count=0
skip_count=0
validated_count=0
rejected_count=0
failed_test_ids=()
rejected_records=()
skipped_tests=()

while IFS= read -r -d $'\x1e' rec; do
  IFS=$'\x1f' read -r -a parts <<< "$rec"
  rtype="${parts[0]}"

  case "$rtype" in
    TEST|PKGFAIL)
      test_id="${parts[1]}"
      category="${parts[2]}"
      command_str="${parts[3]}"
      result="${parts[4]}"
      summary="${parts[5]}"
      raw_path="${parts[6]}"

      total_tests=$((total_tests + 1))
      if [[ "$result" == "PASS" ]]; then
        pass_count=$((pass_count + 1))
      else
        fail_count=$((fail_count + 1))
        failed_test_ids+=("$test_id")
      fi

      record_path="$(write_evidence_record "$PHASE_DIR" "$test_id" "$category" "$command_str" "$result" "$summary" "$raw_path")"

      if validate_evidence_record "$record_path" >/dev/null; then
        validated_count=$((validated_count + 1))
      else
        rejected_count=$((rejected_count + 1))
        rejected_records+=("$record_path")
      fi
      ;;
    SKIP)
      test_id="${parts[1]}"
      category="${parts[2]}"
      command_str="${parts[3]}"
      summary="${parts[4]}"
      raw_path="${parts[5]}"

      skip_count=$((skip_count + 1))
      skipped_tests+=("${test_id} :: ${summary}")

      record_path="$(write_evidence_record "$PHASE_DIR" "$test_id" "$category" "$command_str" "SKIPPED" "$summary" "$raw_path")"
      total_tests=$((total_tests + 1))

      if validate_evidence_record "$record_path" >/dev/null; then
        validated_count=$((validated_count + 1))
      else
        rejected_count=$((rejected_count + 1))
        rejected_records+=("$record_path")
      fi
      ;;
  esac
done < <(python3 "$PARSER_PY" "$JSONL_PATH" "$RAW_DIR")

# --- A non-zero `go test` exit that no recorded FAIL explains ---------------
# See the header note above. `go test`'s real exit code is a first-class
# signal, not decoration: if it says the run failed and every parsed per-test
# outcome says PASS/SKIP, the failure lives somewhere the per-test event
# stream does not represent (a TestMain/teardown failure after all tests
# passed, a post-test panic, a vet diagnostic). Record it with the REAL
# captured output behind it rather than discarding it.
if [[ "$GO_TEST_RC" -ne 0 && "$fail_count" -eq 0 ]]; then
  unexplained_raw="${PHASE_DIR}/raw/go-test-unexplained-exit.log"
  failed_pkgs="$(jq -r 'select(.Action == "fail" and (has("Test") | not)) | .Package' "$JSONL_PATH" 2>/dev/null | sort -u | tr '\n' ' ' | sed -E 's/[[:space:]]+$//' || true)"
  {
    echo "# 'go test -race -count=1 -json ./...' exited ${GO_TEST_RC}, but every one of the"
    echo "# ${total_tests} parsed per-test outcome(s) was PASS or SKIP — nothing in the per-test"
    echo "# event stream accounts for the failure. Real captured evidence follows."
    echo "# package(s) reporting a package-level FAIL event: ${failed_pkgs:-<none>}"
    echo "# --- real package-level output events (no Test field) ---"
    jq -r 'select(.Action == "output" and (has("Test") | not)) | .Output' "$JSONL_PATH" 2>/dev/null || true
    echo "# --- real build-output events (compile / vet diagnostics) ---"
    jq -r 'select(.Action == "build-output") | .Output' "$JSONL_PATH" 2>/dev/null || true
    echo "# --- real captured stderr ---"
    cat "$STDERR_PATH" 2>/dev/null || true
  } > "$unexplained_raw"

  unexplained_diag="$(grep -avE '^[[:space:]]*(#|$)' "$unexplained_raw" 2>/dev/null | tail -n 3 | tr '\n' ' ' | sed -E 's/[[:space:]]+/ /g' || true)"
  unexplained_summary="go test exited ${GO_TEST_RC} while all ${total_tests} parsed per-test outcome(s) were PASS/SKIP — package-level FAIL reported for [${failed_pkgs:-none}] with no failing test of its own. Real captured package-level output: \"${unexplained_diag:-<none captured>}\""

  echo ""
  echo "phase-02-test-go: go test exited ${GO_TEST_RC} but no parsed test reported FAIL — recording the unexplained failure" >&2

  unexplained_record="$(write_evidence_record "$PHASE_DIR" \
    "go-test-invocation#(unexplained-nonzero-exit)" "go-unit-integration" \
    "cd lava-api-go && GOMAXPROCS=2 go test -race -count=1 ./..." "FAIL" \
    "$unexplained_summary" "$unexplained_raw")"
  total_tests=$((total_tests + 1))
  fail_count=$((fail_count + 1))
  failed_test_ids+=("go-test-invocation#(unexplained-nonzero-exit)")
  if validate_evidence_record "$unexplained_record" >/dev/null; then
    validated_count=$((validated_count + 1))
  else
    rejected_count=$((rejected_count + 1))
    rejected_records+=("$unexplained_record")
  fi
fi

echo ""
echo "phase-02-test-go: SUMMARY"
echo "  go test exit code:        ${GO_TEST_RC}"
echo "  total tests recorded:     ${total_tests} (go-unit-integration + real-binary-contract combined)"
echo "  PASS:                     ${pass_count}"
echo "  FAIL:                     ${fail_count}"
echo "  SKIP (SKIPPED Evidence Record, anti-bluff-validated, honestly reported): ${skip_count}"
echo "  Evidence Records validated:  ${validated_count}"
echo "  Evidence Records REJECTED:   ${rejected_count}"

if [[ "$fail_count" -gt 0 ]]; then
  echo ""
  echo "  FAILED tests:"
  for t in "${failed_test_ids[@]}"; do
    echo "    - ${t}"
  done
fi

if [[ "$skip_count" -gt 0 ]]; then
  echo ""
  echo "  SKIPPED tests (real reason captured, SKIPPED Evidence Record written and anti-bluff-validated):"
  for s in "${skipped_tests[@]}"; do
    echo "    - ${s}"
  done
fi

if [[ "$rejected_count" -gt 0 ]]; then
  echo ""
  echo "  REJECTED Evidence Records (anti-bluff validation failed):"
  for r in "${rejected_records[@]}"; do
    reason="$(jq -r '.anti_bluff_status' "$r" 2>/dev/null || echo "unknown")"
    echo "    - ${r}: ${reason}"
  done
fi

echo ""
echo "phase-02-test-go: Evidence Records under ${PHASE_DIR}/go-unit-integration/ and ${PHASE_DIR}/real-binary-contract/"

if [[ "$total_tests" -eq 0 ]]; then
  echo "phase-02-test-go: FAILED — 'go test -json' produced ZERO parseable per-test events" >&2
  echo "  (the event stream at ${JSONL_PATH} is non-empty but contains no pass/fail/skip" >&2
  echo "  outcome for any individual test, and no package-level failure either). Nothing was" >&2
  echo "  proven and no Evidence Record exists — reporting PASS here would be vacuous. The" >&2
  echo "  realistic cause is that no test was compiled into the run at all (e.g. a build tag" >&2
  echo "  excluding every _test.go file); the real captured stream + stderr are:" >&2
  head -n 20 "$JSONL_PATH" >&2
  cat "$STDERR_PATH" >&2
  exit 3
fi

if [[ "$fail_count" -gt 0 || "$rejected_count" -gt 0 ]]; then
  echo "phase-02-test-go: FAILED — ${fail_count} real test failure(s), ${rejected_count} anti-bluff rejection(s)" >&2
  exit 1
fi

echo "phase-02-test-go: PASSED — all recorded tests PASS, all Evidence Records validated"
exit 0

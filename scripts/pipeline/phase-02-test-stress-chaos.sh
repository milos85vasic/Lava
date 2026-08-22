#!/usr/bin/env bash
# phase-02-test-stress-chaos.sh — Test-category wrapper: stress-chaos (T026 of
# specs/002-build-test-distribute-pipeline/tasks.md).
#
# This project ALREADY has a real §11.4.85 (HelixConstitution Stress + Chaos
# Test Mandate) scaffold, referenced in project docs as "LVA-7":
#   - scripts/run-chaos-stress.sh — Lava-side glue that invokes the harness.
#   - lava-api-go/tests/stress/{api_stress_test.go,stress_harness.go} — the
#     harness itself: a single Go test, `TestStressChaos`, that drives a real
#     Gin HTTP handler over a real loopback httptest.Server through 6 real
#     in-process dimensions (no Postgres, no emulator, no sudo — see
#     lava-api-go/tests/stress/README.md), then writes ONE real evidence pair
#     (JSON + Markdown) under lava-api-go/tests/stress/evidence/<UTC-ts>/.
#   - docs/chaos-stress/{DESIGN.md,EVIDENCE-phase1.md} — the design doc and a
#     real prior run's captured evidence, used here to confirm the expected
#     shape of stress-chaos.json before writing this wrapper (verified,
#     not assumed — see "Verified output shape" below).
#
# This script does NOT reimplement any stress/chaos test logic (§11.4.74 —
# no reinventing existing capability). It:
#   1. Invokes scripts/run-chaos-stress.sh for real (capturing full stdout/
#      stderr).
#   2. Locates the NEW evidence directory the invocation produced (diffed
#      against the evidence/ dir's pre-invocation contents — see "Anti-bluff:
#      proving THIS invocation produced THIS evidence" below; never just
#      "most recent by mtime", which could silently replay a stale prior
#      run's evidence as if it were fresh).
#   3. Converts the real per-dimension results in that evidence's
#      `dimensions[]` array into one Evidence Record per dimension
#      (category: stress-chaos), quoting each dimension's own real, specific
#      numbers in `assertion_summary` — never a generic phrase.
#   4. Validates every record via anti-bluff-validate.sh.
#
# ---------------------------------------------------------------------------
# Verified real invocation contract (read scripts/run-chaos-stress.sh in full
# before writing this wrapper — this is what it actually does, not assumed):
# ---------------------------------------------------------------------------
#   scripts/run-chaos-stress.sh                # Phase 1: in-process only
#   scripts/run-chaos-stress.sh --with-podman  # + Phase 1.b note (see below)
#
# Under the hood it `cd`s into lava-api-go/ and runs exactly:
#   GOMAXPROCS=2 go test -tags stress -run TestStressChaos -v ./tests/stress/...
# (build-tagged `stress`, so it never runs in the project's default
# `go test ./...`). Exit code is `go test`'s own exit code: 0 = suite PASS
# (every dimension that ran reported PASS AND no goroutine/FD leak), non-zero
# = at least one ran dimension FAILed.
#
# --with-podman does NOT invoke any additional real test in this codebase
# today: it only guards on `command -v podman || command -v docker` and, if
# found, prints an informational NOTE that the Phase 1.b real-Postgres chaos
# driver (build tag `stresspg`) is "owed" — confirmed by grepping this
# codebase for `stresspg` (zero matches; see "Precondition-gap: C3/C4b real
# infra" below). This wrapper still passes --with-podman (podman IS on this
# host's PATH) purely so the captured log honestly shows that check running,
# rather than this wrapper silently assuming the answer.
#
# ---------------------------------------------------------------------------
# Verified output shape (cross-checked against docs/chaos-stress/
# EVIDENCE-phase1.md, a real prior run's captured evidence, before writing
# the parser below):
# ---------------------------------------------------------------------------
# `TestStressChaos` (lava-api-go/tests/stress/api_stress_test.go) is a SINGLE
# Go test with NO t.Run() subtests — it runs 8 named "dimensions" inline (S1,
# S2, C1, C2, C4a, C5 real; C3, C4b operator-gated) and writes them all into
# ONE JSON document's `dimensions[]` array (see stress_harness.go's
# `DimensionResult` / `Evidence` structs). The natural per-scenario
# granularity for Evidence Records is therefore one record PER DIMENSION
# (matching this project's own `.lava-ci-evidence/stress-chaos/<provider>/`
# convention elsewhere of one evidence file per distinct chaos scenario),
# NOT one record for the whole `go test` invocation.
#
# Each dimension object carries: id, name, ran (bool), status ("PASS"/"FAIL"
# when ran=true; "OPERATOR_GATED" when ran=false), requests, status_2xx/4xx/
# 5xx, error_rate, latency.{p50,p95,p99,max}_ms, and (chaos dimensions only)
# fault_type / error_rate_during_fault / error_rate_after_fault /
# recovery_requests / notes.
#
# ---------------------------------------------------------------------------
# Operator-gated dimensions (C3, C4b) get a SKIPPED Evidence Record
# ---------------------------------------------------------------------------
# contracts/evidence-record.schema.json's `result` enum is ["PASS", "FAIL",
# "SKIPPED"] — SKIPPED was added specifically for an honestly-reported
# non-execution (see data-model.md's Evidence Record section). C3
# (Postgres-kill) and C4b (pool-exhaustion) are recorded `ran:false,
# status:"OPERATOR_GATED"` by the Go harness ITSELF (never faked by it — see
# stress_harness.go's own comments), because they need a real Postgres
# instance under podman that this invocation does not have running. Writing
# a PASS record for a dimension that never ran would be exactly the
# Anti-Bluff Pact violation this project's Sixth/Seventh Laws forbid; forcing
# it to FAIL would misrepresent a missing host precondition as a real defect
# — the same false-FAIL class SKIPPED exists to prevent (matching the
# precedent already established in this pipeline by
# phase-02-test-challenge.sh's SKIPPED handling for blocked Challenge
# classes). This wrapper writes one SKIPPED Evidence Record per ran=false
# dimension, quoting the harness's own real `status`/`notes` fields
# verbatim, anti-bluff-validates it exactly like any PASS/FAIL record, and
# additionally reports it by name in this script's own final summary — a
# downstream reader sees exactly which dimensions did not run and exactly
# why, with real machine evidence backing the claim rather than a bare
# console note.
#
# ---------------------------------------------------------------------------
# Precondition-gap: C3/C4b real infra (reported honestly, not faked)
# ---------------------------------------------------------------------------
# C3/C4b need a real Postgres instance under podman/docker PLUS a Phase 1.b
# "real-Postgres chaos driver" (build tag `stresspg`) that does not exist yet
# in this codebase (`grep -rl stresspg --include='*.go' lava-api-go` returns
# nothing, confirmed before writing this script). Standing up that driver is
# out of scope for this wrapper task (per this task's own instructions: "if
# starting it is out of scope / would take very long, report that honestly
# as a real precondition gap rather than faking it") — it is pre-existing,
# already-documented constitutional debt (root CLAUDE.md §11.4.85 adoption
# note, tracked as LVA-7). This wrapper does not attempt to build that
# driver, does not stand up Postgres, and does not fabricate C3/C4b results;
# it reports the gap in its own summary and moves on, exactly as the
# upstream script and its own README already do.
#
# ---------------------------------------------------------------------------
# Anti-bluff: proving THIS invocation produced THIS evidence
# ---------------------------------------------------------------------------
# `ls -1dt .../evidence/*/ | head -1` (the approach scripts/run-chaos-stress.sh
# itself uses just to print a path) is NOT safe for this wrapper to rely on
# for SOURCING records from: if a stale evidence directory already existed
# from a prior run and this invocation somehow failed before reaching
# `ev.Write()`, "most recent by mtime" would silently replay that stale
# directory's dimensions as if they came from this run — a textbook bluff.
# This script instead snapshots the evidence/ directory's immediate
# subdirectories BEFORE invoking run-chaos-stress.sh and diffs against an
# AFTER snapshot, so only a genuinely NEW directory (proven to not have
# existed before this exact invocation) is ever used as the source of
# Evidence Records. If no new directory appears, that is treated as a real,
# recorded failure (a synthetic "#(build)" FAIL record, mirroring
# phase-02-test-go.sh's PKGFAIL handling) — never silently swallowed, and
# never a bluffed PASS.
#
# ---------------------------------------------------------------------------
# Unreadable / unrecognized / unexplained: all fail closed (2026-08-21 audit)
# ---------------------------------------------------------------------------
# The "Anti-bluff: proving THIS invocation produced THIS evidence" section
# above guards the case where NO new evidence directory appears. Three
# adjacent cases had no guard at all, and each reported "PASSED — all
# recorded dimensions PASS":
#
#   (a) The evidence JSON exists but is UNREADABLE (truncated by a killed
#       harness). `mapfile -t DIM_LINES < <(jq -c '.dimensions[]' ...)` simply
#       yields nothing when jq errors, so the loop body never runs.
#       Observed verbatim against a truncated file whose own readable content
#       said `"verdict":"FAIL"`: "dimensions recorded (Evidence Records): 0
#       / PASS: 0 / FAIL: 0 ... PASSED" and exit 0.
#   (b) The evidence JSON is readable but carries ZERO dimensions — same
#       empty loop, same vacuous success.
#   (c) run-chaos-stress.sh exits NON-ZERO while every dimension reports
#       PASS. RUN_RC was captured and only printed; this header itself states
#       exit 0 additionally means "no goroutine/FD leak", i.e. the harness
#       can fail for reasons no single dimension's status carries.
#
# A fourth case is the same downgrade the `.all_passed // "unknown"` bug
# produced in phase-02-test-constitutional-gate-sweep.sh: `ran` is read with
# `jq -r '.ran'` and compared to the string "true", so a dimension object
# that does not carry that key yields "null" and silently takes the
# OPERATOR_GATED path — a NON-BLOCKING skip. With `ran`/`status` renamed to
# `executed`/`result`, a genuinely FAILing dimension was reported as
# "S1 sustained: null — (no notes captured)" and the run PASSED. A dimension
# whose shape is not recognized is now a FAIL, not a fabricated skip: only a
# real boolean `ran:false` with a real string `status` is an honest gate.
#
# Regression coverage: tests/pipeline/test_phase_02_stress_chaos_wrapper.sh
# CASE 2/3/4/5.
#
# ---------------------------------------------------------------------------
# Usage
# ---------------------------------------------------------------------------
#   scripts/pipeline/phase-02-test-stress-chaos.sh [repo-path] [phase-dir]
#
# repo-path — Lava monorepo root. Defaults to `git rev-parse --show-toplevel`.
# phase-dir — the phase-02 evidence directory Evidence Records are written
#             under (per data-model.md: "<run_dir>/phase-<NN>/<category>/
#             <test_id>.json"). Defaults to a freshly-generated
#             "<repo-path>/.lava-ci-evidence/pipeline-runs/<UTC-run-id>/phase-02"
#             so this script is independently runnable/verifiable on its own
#             (per FR-005).
#
# Exit codes:
#   0 - run-chaos-stress.sh produced a new evidence directory, that
#       directory's stress-chaos.json was readable and carried at least one
#       recognizable dimension, every dimension that ran (ran=true) reported
#       PASS, run-chaos-stress.sh's own exit code was 0 (or non-zero and
#       explained by a recorded FAIL), and every Evidence Record was
#       anti-bluff-validated.
#   1 - at least one ran dimension genuinely reported FAIL, at least one
#       Evidence Record was REJECTED by anti-bluff validation, the
#       invocation produced no new evidence directory at all, the evidence
#       JSON could not be parsed, it carried zero dimensions, a dimension's
#       shape was not recognized, or run-chaos-stress.sh exited non-zero with
#       no recorded FAIL explaining it. Each of these is recorded as a real,
#       evidenced FAIL record, never silently dropped.
#   2 - usage/precondition error (repo path, scripts/run-chaos-stress.sh, or
#       lava-api-go/tests/stress/ missing).
#   3 - required tool missing on PATH (jq or go).
#
# This script writes ONLY Evidence Records (JSON) + their raw-output
# companions under `<phase_dir>/`; it never touches git state, never pushes,
# never distributes anything.

set -uo pipefail
# Deliberately NOT `set -e`: run-chaos-stress.sh's own exit code is a REAL
# signal this wrapper must inspect (a non-zero exit means a real dimension
# FAILed), not something to let errexit convert into an uninspected abort.
# Every risky command below is explicitly guarded via `if`/direct `$?`
# capture instead.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/pipeline/lib/evidence.sh
source "${SCRIPT_DIR}/lib/evidence.sh"
# shellcheck source=scripts/pipeline/lib/anti-bluff-validate.sh
source "${SCRIPT_DIR}/lib/anti-bluff-validate.sh"

for tool in jq go; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "phase-02-test-stress-chaos: FAILED — required tool '$tool' not found on PATH" >&2
    exit 3
  fi
done

REPO_PATH="${1:-}"
if [[ -z "$REPO_PATH" ]]; then
  REPO_PATH="$(git rev-parse --show-toplevel)"
fi

RUN_SCRIPT="${REPO_PATH}/scripts/run-chaos-stress.sh"
STRESS_DIR="${REPO_PATH}/lava-api-go/tests/stress"
EVIDENCE_ROOT="${STRESS_DIR}/evidence"

if [[ ! -f "$RUN_SCRIPT" ]]; then
  echo "phase-02-test-stress-chaos: precondition failed — scripts/run-chaos-stress.sh not found under '${REPO_PATH}'" >&2
  exit 2
fi
if [[ ! -d "$STRESS_DIR" ]]; then
  echo "phase-02-test-stress-chaos: precondition failed — lava-api-go/tests/stress/ not found under '${REPO_PATH}'" >&2
  exit 2
fi

PHASE_DIR="${2:-}"
if [[ -z "$PHASE_DIR" ]]; then
  RUN_ID="$(date -u +%Y-%m-%dT%H-%M-%SZ)"
  PHASE_DIR="${REPO_PATH}/.lava-ci-evidence/pipeline-runs/${RUN_ID}/phase-02"
fi

RAW_DIR="${PHASE_DIR}/stress-chaos/raw"
mkdir -p "$RAW_DIR"

echo "phase-02-test-stress-chaos: repo=${REPO_PATH}"
echo "phase-02-test-stress-chaos: phase_dir=${PHASE_DIR}"

# --- Precondition-gap honesty check: does --with-podman actually run more? -
WITH_PODMAN_ARGS=()
if command -v podman >/dev/null 2>&1 || command -v docker >/dev/null 2>&1; then
  echo "phase-02-test-stress-chaos: podman/docker detected on PATH; passing --with-podman so the real invocation itself demonstrates this"
  WITH_PODMAN_ARGS+=(--with-podman)
  if ! find "${REPO_PATH}/lava-api-go" -name '*.go' -print0 2>/dev/null | xargs -0 grep -l 'stresspg' >/dev/null 2>&1; then
    echo "phase-02-test-stress-chaos: NOTE (honest precondition gap, not faked) — the Phase 1.b real-Postgres"
    echo "  chaos driver (build tag 'stresspg') does not exist yet in lava-api-go/ (grep confirmed zero"
    echo "  matches). --with-podman therefore adds no additional REAL test execution today; C3"
    echo "  (dependency-kill-postgres) and C4b (pool-exhaustion) remain OPERATOR_GATED by the harness's"
    echo "  own design, exactly as lava-api-go/tests/stress/README.md documents. This is pre-existing"
    echo "  constitutional debt (root CLAUDE.md §11.4.85 adoption note, tracked as LVA-7), not a gap"
    echo "  introduced by this wrapper, and out of scope to close here (would require standing up a real"
    echo "  Postgres instance + writing a new chaos driver)."
  fi
else
  echo "phase-02-test-stress-chaos: NOTE — neither podman nor docker found on PATH; running in-process suite only (this is the documented default scope; C3/C4b are OPERATOR_GATED regardless)"
fi

# --- Snapshot evidence/ BEFORE invoking, so we can prove the NEW dir is real
BEFORE_SNAPSHOT="$(mktemp)"
find "$EVIDENCE_ROOT" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort > "$BEFORE_SNAPSHOT"

FULL_LOG="${RAW_DIR}/run-chaos-stress-invocation.log"
CMD_DISPLAY="scripts/run-chaos-stress.sh${WITH_PODMAN_ARGS:+ ${WITH_PODMAN_ARGS[*]}}"
echo "phase-02-test-stress-chaos: invoking (real): ${CMD_DISPLAY}"
echo "phase-02-test-stress-chaos: full captured stdout/stderr -> ${FULL_LOG#"$REPO_PATH"/}"

(
  cd "$REPO_PATH" || exit 2
  bash "$RUN_SCRIPT" "${WITH_PODMAN_ARGS[@]}"
) > "$FULL_LOG" 2>&1
RUN_RC=$?

echo "phase-02-test-stress-chaos: run-chaos-stress.sh exited ${RUN_RC}"

AFTER_SNAPSHOT="$(mktemp)"
find "$EVIDENCE_ROOT" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort > "$AFTER_SNAPSHOT"
NEW_DIR="$(comm -13 "$BEFORE_SNAPSHOT" "$AFTER_SNAPSHOT" | sort | tail -1)"
rm -f "$BEFORE_SNAPSHOT" "$AFTER_SNAPSHOT"

TOTAL_RECORDED=0
PASS_COUNT=0
FAIL_COUNT=0
SKIPPED_COUNT=0
VALIDATED_COUNT=0
REJECTED_COUNT=0
declare -a FAILED_IDS=()
declare -a REJECTED_RECORDS=()
declare -a GATED_LINES=()

RERUN_CMD="cd lava-api-go && GOMAXPROCS=2 go test -tags stress -run TestStressChaos -v ./tests/stress/..."

if [[ -z "$NEW_DIR" || ! -f "${NEW_DIR}/stress-chaos.json" ]]; then
  # Genuine failure: the invocation never reached ev.Write(). Record it as a
  # real, evidenced FAIL — never silently dropped (mirrors phase-02-test-go.sh's
  # PKGFAIL handling for a package-level build/panic failure with no
  # per-test breakdown).
  echo "phase-02-test-stress-chaos: no NEW evidence directory appeared under ${EVIDENCE_ROOT#"$REPO_PATH"/} — treating as a real run failure"
  test_id="digital.vasic.lava.apigo/tests/stress#TestStressChaos#(build)"
  tail_lines="$(tail -n 5 "$FULL_LOG" 2>/dev/null | tr '\n' ' ' | sed -E 's/[[:space:]]+/ /g')"
  summary="run-chaos-stress.sh exited ${RUN_RC} and produced no new evidence directory under lava-api-go/tests/stress/evidence/ (go test never reached ev.Write()); last captured output: ${tail_lines:-"(no output captured)"}"
  record_path="$(write_evidence_record "$PHASE_DIR" "$test_id" "stress-chaos" "$RERUN_CMD" "FAIL" "$summary" "$FULL_LOG")"
  TOTAL_RECORDED=1
  FAIL_COUNT=1
  FAILED_IDS+=("$test_id")
  if validate_evidence_record "$record_path" >/dev/null; then
    VALIDATED_COUNT=1
  else
    REJECTED_COUNT=1
    REJECTED_RECORDS+=("$record_path")
  fi
else
  EVIDENCE_JSON="${NEW_DIR}/stress-chaos.json"
  REL_EVIDENCE_JSON="${EVIDENCE_JSON#"$REPO_PATH"/}"
  echo "phase-02-test-stress-chaos: real new evidence found -> ${REL_EVIDENCE_JSON}"

  # Keep a copy of the real source evidence pair for provenance.
  SOURCE_COPY_DIR="${RAW_DIR}/source-evidence"
  mkdir -p "$SOURCE_COPY_DIR"
  cp -f "$EVIDENCE_JSON" "${SOURCE_COPY_DIR}/stress-chaos.json" 2>/dev/null || true
  if [[ -f "${NEW_DIR}/stress-chaos.md" ]]; then
    cp -f "${NEW_DIR}/stress-chaos.md" "${SOURCE_COPY_DIR}/stress-chaos.md" 2>/dev/null || true
  fi

  # --- (a)/(b): is this evidence actually readable + non-empty? ------------
  # See the header note "Unreadable / unrecognized / unexplained". Both
  # branches below write a real FAIL Evidence Record quoting the real bytes /
  # real structure that defeated the parse, then skip the dimension loop —
  # never a silent empty loop reporting success.
  DIMENSIONS_USABLE=1

  if ! jq -e . "$EVIDENCE_JSON" >/dev/null 2>&1; then
    DIMENSIONS_USABLE=0
    jq_err="$(jq -e . "$EVIDENCE_JSON" 2>&1 >/dev/null | head -n 3 | tr '\n' ' ' | sed -E 's/[[:space:]]+/ /g' || true)"
    bad_raw="${RAW_DIR}/unreadable-stress-chaos-json.log"
    {
      echo "# run-chaos-stress.sh produced a new evidence directory, but its"
      echo "# stress-chaos.json could NOT be parsed as JSON, so not one dimension"
      echo "# result from this run could be read."
      echo "# source: ${REL_EVIDENCE_JSON}"
      echo "# jq error: ${jq_err}"
      echo "# harness exit code: ${RUN_RC}"
      echo "# --- real first 2000 bytes of the unreadable evidence file ---"
      head -c 2000 "$EVIDENCE_JSON" 2>/dev/null || echo "(file unreadable)"
      echo ""
      echo "# --- tail of the real harness stdout/stderr ---"
      tail -n 20 "$FULL_LOG" 2>/dev/null || true
    } > "$bad_raw"
    bad_head="$(head -c 300 "$EVIDENCE_JSON" 2>/dev/null | tr '\n' ' ' | sed -E 's/[[:space:]]+/ /g' || true)"
    bad_summary="run-chaos-stress.sh exited ${RUN_RC} and wrote ${REL_EVIDENCE_JSON}, but that evidence file could not be parsed as JSON (${jq_err}) — zero dimension results could be read from this run. Real first bytes of the unreadable file: \"${bad_head}\""
    echo "phase-02-test-stress-chaos: evidence JSON is unreadable — recording a real FAIL" >&2
    bad_record="$(write_evidence_record "$PHASE_DIR" \
      "digital.vasic.lava.apigo/tests/stress#TestStressChaos#(unreadable-evidence)" \
      "stress-chaos" "$RERUN_CMD" "FAIL" "$bad_summary" "$bad_raw")"
    TOTAL_RECORDED=$((TOTAL_RECORDED + 1))
    FAIL_COUNT=$((FAIL_COUNT + 1))
    FAILED_IDS+=("digital.vasic.lava.apigo/tests/stress#TestStressChaos#(unreadable-evidence)")
    if validate_evidence_record "$bad_record" >/dev/null; then
      VALIDATED_COUNT=$((VALIDATED_COUNT + 1))
    else
      REJECTED_COUNT=$((REJECTED_COUNT + 1))
      REJECTED_RECORDS+=("$bad_record")
    fi
  else
    DIM_COUNT="$(jq -r 'if (.dimensions | type) == "array" then (.dimensions | length) else -1 end' "$EVIDENCE_JSON")"
    if [[ "$DIM_COUNT" -le 0 ]]; then
      DIMENSIONS_USABLE=0
      empty_raw="${RAW_DIR}/no-dimensions-in-evidence.log"
      {
        echo "# run-chaos-stress.sh produced a new, parseable evidence file that carries"
        if [[ "$DIM_COUNT" -lt 0 ]]; then
          echo "# NO 'dimensions' array at all (the key is absent or is not an array)."
        else
          echo "# an EMPTY 'dimensions' array — not one stress/chaos dimension result."
        fi
        echo "# source: ${REL_EVIDENCE_JSON}"
        echo "# harness exit code: ${RUN_RC}"
        echo "# --- real top-level keys present in the evidence document ---"
        jq -r 'keys | join(", ")' "$EVIDENCE_JSON" 2>/dev/null || true
        echo "# --- real evidence document (first 2000 bytes) ---"
        head -c 2000 "$EVIDENCE_JSON" 2>/dev/null || true
        echo ""
      } > "$empty_raw"
      empty_keys="$(jq -r 'keys | join(", ")' "$EVIDENCE_JSON" 2>/dev/null | tr -d '\n' || true)"
      empty_summary="run-chaos-stress.sh exited ${RUN_RC} and wrote ${REL_EVIDENCE_JSON}, but that evidence file reports ZERO stress/chaos dimensions (dimensions array length=${DIM_COUNT}; real top-level keys present: ${empty_keys:-<none>}) — the suite proved nothing about sustained load, injected faults or recovery."
      echo "phase-02-test-stress-chaos: evidence JSON carries no dimensions — recording a real FAIL" >&2
      empty_record="$(write_evidence_record "$PHASE_DIR" \
        "digital.vasic.lava.apigo/tests/stress#TestStressChaos#(no-dimensions)" \
        "stress-chaos" "$RERUN_CMD" "FAIL" "$empty_summary" "$empty_raw")"
      TOTAL_RECORDED=$((TOTAL_RECORDED + 1))
      FAIL_COUNT=$((FAIL_COUNT + 1))
      FAILED_IDS+=("digital.vasic.lava.apigo/tests/stress#TestStressChaos#(no-dimensions)")
      if validate_evidence_record "$empty_record" >/dev/null; then
        VALIDATED_COUNT=$((VALIDATED_COUNT + 1))
      else
        REJECTED_COUNT=$((REJECTED_COUNT + 1))
        REJECTED_RECORDS+=("$empty_record")
      fi
    fi
  fi

  GIT_SHA="$(jq -r '.git_sha // "UNKNOWN"' "$EVIDENCE_JSON" 2>/dev/null || echo UNKNOWN)"
  GO_VER="$(jq -r '.go_version // "UNKNOWN"' "$EVIDENCE_JSON" 2>/dev/null || echo UNKNOWN)"
  GOOS_V="$(jq -r '.goos // "UNKNOWN"' "$EVIDENCE_JSON" 2>/dev/null || echo UNKNOWN)"
  RUN_VERDICT="$(jq -r '.verdict // "UNKNOWN"' "$EVIDENCE_JSON" 2>/dev/null || echo UNKNOWN)"
  echo "phase-02-test-stress-chaos: source run — git_sha=${GIT_SHA} go=${GO_VER} os=${GOOS_V} overall_verdict=${RUN_VERDICT}"

  # Only walk dimensions when the evidence was genuinely readable AND carried
  # at least one — the (a)/(b) guards above already recorded a real FAIL for
  # each of those cases, and a partially-readable document must not be allowed
  # to contribute a partial, silently-shortened dimension set on top of it.
  if [[ "$DIMENSIONS_USABLE" == "1" ]]; then
    mapfile -t DIM_LINES < <(jq -c '.dimensions[]' "$EVIDENCE_JSON")

    for line in "${DIM_LINES[@]}"; do
      id="$(jq -r '.id' <<<"$line")"
      name="$(jq -r '.name' <<<"$line")"
      ran="$(jq -r '.ran' <<<"$line")"
      status="$(jq -r '.status' <<<"$line")"
      notes="$(jq -r '.notes // empty' <<<"$line")"

      # A dimension is only honestly "operator-gated" when the harness itself
      # said so with a real boolean `ran:false` AND a real string `status`.
      # Reading `.ran` as a string and comparing it to "true" also matches a
      # dimension that carries no `ran` key at all (jq yields "null"), which
      # silently routed an unrecognized — possibly FAILING — dimension into the
      # non-blocking SKIPPED path. See the header note; this is the same
      # downgrade class as `.all_passed // "unknown"`.
      ran_type="$(jq -r 'if has("ran") then (.ran | type) else "missing" end' <<<"$line")"
      status_type="$(jq -r 'if has("status") then (.status | type) else "missing" end' <<<"$line")"
      if [[ "$ran_type" != "boolean" || "$status_type" != "string" ]]; then
        dim_label="${id:-unknown}-${name:-unnamed}"
        shape_raw="${RAW_DIR}/dimension-${dim_label}-unrecognized-shape.txt"
        dim_keys="$(jq -r 'keys | join(", ")' <<<"$line" 2>/dev/null || true)"
        {
          echo "# lava-api-go Stress + Chaos — dimension with an UNRECOGNIZED shape"
          echo "# source evidence file: ${REL_EVIDENCE_JSON}"
          echo "# harness exit code: ${RUN_RC}"
          echo "# expected: a boolean 'ran' and a string 'status'"
          echo "# actually present: ran=${ran_type}, status=${status_type}; keys: ${dim_keys}"
          echo "# This dimension's real outcome could NOT be determined, so it is recorded"
          echo "# as a FAIL rather than downgraded into a non-blocking operator-gated skip."
          echo
          jq '.' <<<"$line"
        } > "$shape_raw"
        shape_summary="Dimension ${id:-<no id>} (${name:-<no name>}) has an unrecognized shape: expected a boolean 'ran' and a string 'status', got ran=${ran_type} and status=${status_type} (real keys present: ${dim_keys:-<none>}). Its real outcome could not be determined from ${REL_EVIDENCE_JSON}, so it is NOT reported as an operator-gated skip."
        shape_record="$(write_evidence_record "$PHASE_DIR" \
          "digital.vasic.lava.apigo/tests/stress#TestStressChaos/${dim_label}#(unrecognized-shape)" \
          "stress-chaos" "$RERUN_CMD" "FAIL" "$shape_summary" "$shape_raw")"
        TOTAL_RECORDED=$((TOTAL_RECORDED + 1))
        FAIL_COUNT=$((FAIL_COUNT + 1))
        FAILED_IDS+=("dimension ${dim_label} (unrecognized shape)")
        if validate_evidence_record "$shape_record" >/dev/null; then
          VALIDATED_COUNT=$((VALIDATED_COUNT + 1))
        else
          REJECTED_COUNT=$((REJECTED_COUNT + 1))
          REJECTED_RECORDS+=("$shape_record")
        fi
        continue
      fi

      if [[ "$ran" != "true" ]]; then
        GATED_LINES+=("${id} ${name}: ${status} — ${notes:-"(no notes captured)"}")

        # SKIPPED Evidence Record (contracts/evidence-record.schema.json's
        # result enum gained SKIPPED specifically for this case — see
        # data-model.md's Evidence Record section). An operator-gated
        # dimension that genuinely did not run is neither a PASS (nothing was
        # proven) nor a FAIL (nothing was broken) — it is an honest,
        # anti-bluff-validated non-execution, quoting the harness's OWN real
        # status/notes fields verbatim, never a generic placeholder.
        skip_test_id="digital.vasic.lava.apigo/tests/stress#TestStressChaos/${id}-${name}"
        skip_summary="Genuinely did not execute: dimension ${id} (${name}) is ${status} by the harness's own real precondition check — ${notes:-"(no notes captured)"}"
        skip_raw_file="${RAW_DIR}/dimension-${id}-${name}.txt"
        {
          echo "# lava-api-go Stress + Chaos — dimension ${id} (${name}) — real, harness-reported OPERATOR_GATED status"
          echo "# source evidence file: ${REL_EVIDENCE_JSON}"
          echo "# produced by: ${RERUN_CMD}"
          echo "# full invocation stdout/stderr: ${FULL_LOG#"$REPO_PATH"/}"
          echo "# run provenance: git_sha=${GIT_SHA} go=${GO_VER} os=${GOOS_V}"
          echo
          jq '.' <<<"$line"
        } > "$skip_raw_file"

        skip_record_path="$(write_evidence_record "$PHASE_DIR" "$skip_test_id" "stress-chaos" "$RERUN_CMD" "SKIPPED" "$skip_summary" "$skip_raw_file")"
        TOTAL_RECORDED=$((TOTAL_RECORDED + 1))
        SKIPPED_COUNT=$((SKIPPED_COUNT + 1))

        if validate_evidence_record "$skip_record_path" >/dev/null; then
          VALIDATED_COUNT=$((VALIDATED_COUNT + 1))
        else
          REJECTED_COUNT=$((REJECTED_COUNT + 1))
          REJECTED_RECORDS+=("$skip_record_path")
        fi

        continue
      fi

      requests="$(jq -r '.requests' <<<"$line")"
      s2="$(jq -r '.status_2xx' <<<"$line")"
      s4="$(jq -r '.status_4xx' <<<"$line")"
      s5="$(jq -r '.status_5xx' <<<"$line")"
      errrate="$(jq -r '.error_rate' <<<"$line")"
      p50="$(jq -r '.latency.p50_ms' <<<"$line")"
      p95="$(jq -r '.latency.p95_ms' <<<"$line")"
      p99="$(jq -r '.latency.p99_ms' <<<"$line")"
      maxms="$(jq -r '.latency.max_ms' <<<"$line")"
      faulttype="$(jq -r '.fault_type // empty' <<<"$line")"
      # Go's `json:"...,omitempty"` DROPS these three fields entirely whenever
      # their real recorded value is the zero value (0 / 0.0) — e.g. C1's real
      # error_rate_after_fault==0.0 (perfect recovery) is indistinguishable via
      # plain `.foo // default` from "this dimension never tracks recovery at
      # all" (e.g. C2/C4a/C5, which never set these three fields together).
      # Disambiguate via has(): only dimensions where at least one of the three
      # keys is literally present (i.e. C1's group, which Go always sets as a
      # trio in one struct literal) get the full recovery clause, with the
      # other two safely defaulted to a real 0 rather than left blank.
      has_recovery_group="$(jq -r 'if (has("error_rate_during_fault") or has("error_rate_after_fault") or has("recovery_requests")) then "true" else "false" end' <<<"$line")"
      edf="$(jq -r '.error_rate_during_fault // 0' <<<"$line")"
      eaf="$(jq -r '.error_rate_after_fault // 0' <<<"$line")"
      recov="$(jq -r '.recovery_requests // 0' <<<"$line")"

      summary="${id} ${name}: ${status} under real load — ${requests} requests (2xx=${s2} 4xx=${s4} 5xx=${s5}), error_rate=${errrate}, latency p50=${p50}ms p95=${p95}ms p99=${p99}ms max=${maxms}ms."
      if [[ -n "$faulttype" ]]; then
        if [[ "$has_recovery_group" == "true" ]]; then
          summary="${summary} Injected fault: ${faulttype} — error_rate_during_fault=${edf}, error_rate_after_fault=${eaf}, recovery_requests=${recov}."
        else
          summary="${summary} Injected fault: ${faulttype}."
        fi
      fi
      if [[ -n "$notes" ]]; then
        summary="${summary} ${notes}"
      fi

      test_id="digital.vasic.lava.apigo/tests/stress#TestStressChaos/${id}-${name}"

      raw_file="${RAW_DIR}/dimension-${id}-${name}.txt"
      {
        echo "# lava-api-go Stress + Chaos — dimension ${id} (${name}) — real captured result"
        echo "# source evidence file: ${REL_EVIDENCE_JSON}"
        echo "# produced by: ${RERUN_CMD}"
        echo "# full invocation stdout/stderr: ${FULL_LOG#"$REPO_PATH"/}"
        echo "# run provenance: git_sha=${GIT_SHA} go=${GO_VER} os=${GOOS_V}"
        echo
        jq '.' <<<"$line"
      } > "$raw_file"

      record_path="$(write_evidence_record "$PHASE_DIR" "$test_id" "stress-chaos" "$RERUN_CMD" "$status" "$summary" "$raw_file")"
      TOTAL_RECORDED=$((TOTAL_RECORDED + 1))

      if [[ "$status" == "PASS" ]]; then
        PASS_COUNT=$((PASS_COUNT + 1))
      else
        FAIL_COUNT=$((FAIL_COUNT + 1))
        FAILED_IDS+=("$test_id")
      fi

      if validate_evidence_record "$record_path" >/dev/null; then
        VALIDATED_COUNT=$((VALIDATED_COUNT + 1))
      else
        REJECTED_COUNT=$((REJECTED_COUNT + 1))
        REJECTED_RECORDS+=("$record_path")
      fi
    done
  fi
fi

# --- (c): a non-zero harness exit that no recorded FAIL explains -----------
# See the header note. run-chaos-stress.sh's exit code is `go test`'s own, and
# per this script's header exit 0 additionally means "no goroutine/FD leak" —
# so a non-zero exit with every dimension PASSing is a real failure that the
# dimensions[] array does not carry.
if [[ "$RUN_RC" -ne 0 && "$FAIL_COUNT" -eq 0 ]]; then
  unexplained_raw="${RAW_DIR}/unexplained-harness-exit.log"
  {
    echo "# run-chaos-stress.sh exited ${RUN_RC} but not one recorded dimension FAILed."
    echo "# Per this wrapper's own contract, harness exit 0 means every dimension that ran"
    echo "# reported PASS *and* no goroutine/FD leak — so a non-zero exit with all-PASS"
    echo "# dimensions is a real failure the dimensions[] array does not represent."
    echo "# dimensions recorded: ${TOTAL_RECORDED} (PASS=${PASS_COUNT} SKIPPED=${SKIPPED_COUNT})"
    echo "# --- real full captured stdout/stderr of the invocation ---"
    cat "$FULL_LOG" 2>/dev/null || true
  } > "$unexplained_raw"
  unexplained_tail="$(grep -av '^[[:space:]]*$' "$FULL_LOG" 2>/dev/null | tail -n 5 | tr '\n' ' ' | sed -E 's/[[:space:]]+/ /g' || true)"
  unexplained_summary="run-chaos-stress.sh exited ${RUN_RC} while all ${TOTAL_RECORDED} recorded dimension(s) reported PASS/SKIPPED — the failure is not represented by any dimension status (per this wrapper's contract, exit 0 also asserts no goroutine/FD leak). Real captured harness output: \"${unexplained_tail:-<no output captured>}\""
  echo "phase-02-test-stress-chaos: harness exited ${RUN_RC} with no failing dimension — recording the unexplained failure" >&2
  unexplained_record="$(write_evidence_record "$PHASE_DIR" \
    "digital.vasic.lava.apigo/tests/stress#TestStressChaos#(unexplained-nonzero-exit)" \
    "stress-chaos" "$RERUN_CMD" "FAIL" "$unexplained_summary" "$unexplained_raw")"
  TOTAL_RECORDED=$((TOTAL_RECORDED + 1))
  FAIL_COUNT=$((FAIL_COUNT + 1))
  FAILED_IDS+=("digital.vasic.lava.apigo/tests/stress#TestStressChaos#(unexplained-nonzero-exit)")
  if validate_evidence_record "$unexplained_record" >/dev/null; then
    VALIDATED_COUNT=$((VALIDATED_COUNT + 1))
  else
    REJECTED_COUNT=$((REJECTED_COUNT + 1))
    REJECTED_RECORDS+=("$unexplained_record")
  fi
fi

echo ""
echo "phase-02-test-stress-chaos: SUMMARY"
echo "  run-chaos-stress.sh exit code: ${RUN_RC}"
echo "  dimensions recorded (Evidence Records): ${TOTAL_RECORDED}"
echo "  PASS: ${PASS_COUNT}"
echo "  FAIL: ${FAIL_COUNT}"
echo "  SKIPPED (operator-gated, honestly reported): ${SKIPPED_COUNT}"
echo "  Evidence Records validated: ${VALIDATED_COUNT}"
echo "  Evidence Records REJECTED:  ${REJECTED_COUNT}"

if [[ ${#GATED_LINES[@]} -gt 0 ]]; then
  echo ""
  echo "  OPERATOR_GATED dimensions (ran=false; each still gets a SKIPPED Evidence Record, anti-bluff-validated, per data-model.md):"
  for g in "${GATED_LINES[@]}"; do
    echo "    - ${g}"
  done
fi

if [[ ${#FAILED_IDS[@]} -gt 0 ]]; then
  echo ""
  echo "  FAILED:"
  for f in "${FAILED_IDS[@]}"; do
    echo "    - ${f}"
  done
fi

if [[ ${#REJECTED_RECORDS[@]} -gt 0 ]]; then
  echo ""
  echo "  REJECTED Evidence Records (anti-bluff validation failed):"
  for r in "${REJECTED_RECORDS[@]}"; do
    reason="$(jq -r '.anti_bluff_status' "$r" 2>/dev/null || echo "unknown")"
    echo "    - ${r}: ${reason}"
  done
fi

echo ""
echo "phase-02-test-stress-chaos: Evidence Records under ${PHASE_DIR}/stress-chaos/"

if [[ "$FAIL_COUNT" -gt 0 || "$REJECTED_COUNT" -gt 0 ]]; then
  echo "phase-02-test-stress-chaos: FAILED — ${FAIL_COUNT} real dimension failure(s), ${REJECTED_COUNT} anti-bluff rejection(s)" >&2
  exit 1
fi

echo "phase-02-test-stress-chaos: PASSED — all recorded dimensions PASS, all Evidence Records validated"
exit 0

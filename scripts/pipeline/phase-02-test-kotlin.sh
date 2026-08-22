#!/usr/bin/env bash
# phase-02-test-kotlin.sh — Phase 02 test-category wrapper: kotlin-unit
# (T021 of specs/002-build-test-distribute-pipeline/tasks.md).
#
# This project already has real JUnit4 unit tests across many Gradle
# modules (`./gradlew test`). This script does NOT write new tests — it
# invokes the existing real test run for real, parses Gradle's own
# authoritative structured JUnit XML reports (NOT the console log), and
# records one Evidence Record (category: kotlin-unit) per INDIVIDUAL test
# method that ran, per
# specs/002-build-test-distribute-pipeline/contracts/evidence-record.schema.json,
# then anti-bluff-validates each record via
# scripts/pipeline/lib/anti-bluff-validate.sh.
#
# Usage:
#   scripts/pipeline/phase-02-test-kotlin.sh [repo-path] [phase-dir]
#
# With no arguments: repo-path resolves via `git rev-parse --show-toplevel`
# (works from anywhere inside the real repo, matching
# phase-00-precondition.sh / phase-02-test-hermetic.sh's existing
# convention); phase-dir defaults to a freshly-created
# `.lava-ci-evidence/pipeline-runs/<UTC-run-id>/phase-02` under repo-path
# (per data-model.md's Evidence Record path convention:
# "<run_dir>/phase-<NN>/<category>/<test_id>.json"), so this script is
# independently runnable/testable without an orchestrator having already
# created a run directory. An orchestrator that already has a run_id MAY
# pass its own phase-dir explicitly as the second argument instead.
#
# --- The real command ---------------------------------------------------
#
#   ./gradlew --no-daemon --continue --rerun-tasks test
#
# `--no-daemon` matches this project's standing convention for scripted/CI
# Gradle invocations (see scripts/ci.sh:99,110,117,234,261,301 — every
# scripted invocation in this repo uses it).
#
# `--continue` (added after a real bug found during this feature's own
# verification pass) is load-bearing: WITHOUT it, Gradle's default
# fail-fast behavior means a single genuine test failure in ONE module
# aborts the ENTIRE multi-module `test` task graph before most of the
# other ~58 modules' test tasks ever run — starving this wrapper's whole
# purpose (evidence for every module) over one module's real, unrelated
# bug. Confirmed empirically: a real run without `--continue` that hit a
# genuine failure in `:core:tracker:kinozal:test` reported "0 real,
# freshly-written JUnit XML report file(s) found" after 943s — not because
# the marker/find logic was wrong (independently re-verified working), but
# because most other modules' test tasks never got the chance to execute
# and write their XML report at all before the build aborted. `--continue`
# makes Gradle run every independent task regardless of an unrelated
# task's failure, while still exiting non-zero overall if anything failed
# — exactly the semantics this wrapper needs (full coverage AND an honest
# non-zero exit on real failures).
#
# `--rerun-tasks` is a deliberate addition beyond the task description's
# literal `./gradlew test`: this repository's working tree, at the time
# this script was authored, already had LEFTOVER `build/test-results/`
# directories from previous, unrelated build sessions sitting on disk for
# several modules. Gradle's own incremental-build "UP-TO-DATE" mechanism
# would silently SKIP re-executing a module's test task (and skip
# rewriting its XML) if it judges nothing relevant changed since the last
# successful run — which would make this script's real, freshly-invoked
# command produce STALE evidence for those modules (evidence genuinely
# reflecting a PRIOR invocation, not the one this script actually just
# ran). Per this project's Anti-Bluff Pact, an Evidence Record claiming to
# summarize "the real outcome of running this test" MUST correspond to a
# test that actually, freshly executed as part of THIS invocation — not a
# cache hit. `--rerun-tasks` forces every task in the requested graph
# (including `test` itself and its per-variant/per-module dependents) to
# execute for real regardless of up-to-date state, which costs more wall
# time but removes any ambiguity about staleness. The task description
# explicitly authorizes unlimited wall time for this run ("this may take
# several minutes ... that's fine, there's no time ceiling"), so trading
# time for certainty here is the correct, non-bluff choice.
#
# --- Enumeration ("which XML files are real evidence of THIS run") -------
#
# A `MARKER_FILE` is `touch`ed immediately before invoking Gradle. After
# Gradle finishes (successfully OR not — a non-zero Gradle exit commonly
# just means "some tests genuinely failed", which is itself real evidence,
# not a reason to skip parsing whatever DID get written), this script
# searches for every `TEST-*.xml` file newer than that marker under
# `build/test-results/<task-name>/` anywhere in the repo tree, EXCLUDING
# `submodules/`, `Submodules/` (both directories exist side-by-side in
# this working tree; neither is part of this project's own root Gradle
# build — Tracker-SDK is consumed via `includeBuild(...)` composite-build
# substitution, whose OWN `test` task is not part of the root build's task
# graph), `.claude/` (other agents' worktrees), `.git/`, and
# `.lava-ci-evidence/` (this pipeline's own evidence tree, never a source
# of Gradle XML). The `-newer` filter is the load-bearing anti-staleness
# guarantee: combined with `--rerun-tasks` above, every matched file is
# guaranteed to be freshly written by the invocation this script itself
# just ran.
#
# Deliberate deviation from the task description's literal
# `build/test-results/test/TEST-*.xml` path: this project's pure-Kotlin/JVM
# modules (e.g. `core/tracker/rutracker`) DO write there, under the plain
# `test` task name — but this project's ANDROID modules (`:app`,
# `:api-app`, every `feature/*` module, `core/data`, `core/network/impl`,
# etc.) write per-variant JUnit XML under
# `build/test-results/testDebugUnitTest/` and/or
# `build/test-results/testReleaseUnitTest/` instead (confirmed by
# inspecting this repo's own already-present build output before writing
# this script — `core/data`, `feature/onboarding`,
# `feature/credentials_manager`, `core/downloads` all have
# `testDebugUnitTest/` only; `core/network/impl` has BOTH
# `testDebugUnitTest/` and `testReleaseUnitTest/`). Following the task's
# literal `test/`-only path would silently DROP every real unit test in
# every Android-application/library/feature module from this category's
# evidence — undercounting real, already-passing (or failing) test
# coverage, which is itself the exact class of bluff-by-omission the
# Anti-Bluff Pact exists to prevent (per the task's own explicit
# instruction: "enumerate every individual test method that ran, across
# every module"). This script therefore matches
# `build/test-results/*/TEST-*.xml` (any task-name directory), not just
# `.../test/TEST-*.xml`.
#
# --- test_id uniqueness (a real, discovered edge case) --------------------
#
# `core/network/impl` produces BOTH `testDebugUnitTest` and
# `testReleaseUnitTest` XML for the SAME shared `src/test/kotlin` source
# set — i.e. the exact same `classname`+`name` pair genuinely, separately
# executes twice (once per build-variant classpath) in a single
# `./gradlew test` invocation. Naively using the bare `ClassName#method`
# form (data-model.md's documented shape) for both would collide at
# `write_evidence_record`'s own filename layer (sanitize(test_id) +
# ".json"), silently OVERWRITING one real, distinct execution's Evidence
# Record with the other's — real evidence quietly lost, which would be a
# constitutional violation in its own right. This script's embedded parser
# therefore does a dedup pass: any bare `ClassName#method` string that
# would be produced by MORE THAN ONE distinct (module, task-variant) pair
# in this run has ` [<task-variant>]` appended to every one of its
# occurrences, making each one unique while leaving the overwhelming
# majority of test_ids (every module with only one variant, i.e. every
# pure-Kotlin/JVM module) in the exact plain `ClassName#method` shape
# data-model.md documents.
#
# --- result derivation -----------------------------------------------------
#
# PASS: the real `<testcase>` element has no `<failure>` or `<error>`
#   child (Gradle's own JUnit XML writer only emits one of those two
#   elements when the test genuinely did not complete cleanly).
# FAIL: a `<failure>` or `<error>` child is present — assertion_summary
#   quotes the REAL `type`/`message` attributes (and a short stack
#   excerpt) verbatim from that element, never a generic phrase.
# SKIPPED: a `<skipped/>` child is present. Per this project's Anti-Bluff
#   Pact a skipped/ignored test proves NOTHING about the feature it claims
#   to cover — recording it as PASS would be exactly the kind of bluff this
#   pipeline exists to catch. But per data-model.md's Evidence Record design
#   (contracts/evidence-record.schema.json's `result` enum is
#   `["PASS", "FAIL", "SKIPPED"]`), forcing it into FAIL would just as
#   dishonestly misrepresent "the test body never ran" as "a real assertion
#   failed" — a false-FAIL that would needlessly block distribution over a
#   test the operator may have deliberately, legitimately disabled (e.g. a
#   `@RequiresDevice`-gated test with no device attached). This script
#   writes result=SKIPPED with an assertion_summary that says plainly the
#   test body never executed, anti-bluff-validates it exactly like any
#   PASS/FAIL record, and separately tallies + lists it in its own summary
#   so a skip is never silently conflated with either a genuine pass or a
#   genuine assertion failure when a human reads the report.
#
# --- Evidence this script could not read, and failures no test explains ----
#
# Both added 2026-08-21 after a wrapper audit; both were silent before.
#
# (i) An UNPARSEABLE report file is a real, recorded FAIL. The embedded
#     parser catches a per-file XML parse error, prints `WARN: failed to
#     parse ... as XML`, and continues -- so a report it could not read
#     contributed nothing and blocked nothing. A JUnit XML file is truncated
#     exactly when the JVM writing it dies (OOM-kill, crash, host power
#     event), which is also exactly when its content is most likely to have
#     been a failure. Observed verbatim with one good report and one
#     truncated report whose visible content was a real `<failure>`:
#         2 real, freshly-written JUnit XML report file(s) found
#         WARN: failed to parse '.../TEST-lava.login.LoginViewModelTest.xml'
#               as XML: unclosed token: line 4, column 4
#         1 individual test(s) parsed ... PASS: 1 / FAIL: 0   (exit 0)
#     Now: one FAIL Evidence Record per unreadable report, quoting the real
#     parse error and the file's real first bytes.
#
# (ii) A non-zero GRADLE EXIT that no recorded FAIL explains is a real,
#     recorded FAIL. GRADLE_EXIT_CODE was captured and only printed. Because
#     this script deliberately passes `--continue` (see above), Gradle exits
#     non-zero for failures that produce NO JUnit XML at all -- a Kotlin
#     test-source compile error being the everyday case. Observed verbatim
#     against a stub whose `:feature:onboarding:compileDebugUnitTestKotlin`
#     FAILED with an `Unresolved reference` while every other module's XML
#     was green: "exited 1 ... total individual tests: 1 / PASS: 1 / FAIL: 0"
#     and wrapper exit 0. A whole module's tests never ran and the category
#     reported success. Now: when Gradle exits non-zero and not one parsed
#     testcase FAILed, one synthetic FAIL Evidence Record is written quoting
#     Gradle's own real failure lines.
#
# Regression coverage for both, plus the zero-parsed-tests case:
# tests/pipeline/test_phase_02_kotlin_wrapper.sh CASE 2/3/4.
#
# Exit codes:
#   0 - at least one Evidence Record was produced, every one of them is
#       PASS or SKIPPED (no genuine FAIL), every one of them was
#       anti-bluff-validated, every JUnit XML report found was readable, and
#       Gradle's own exit code was 0 (or non-zero and explained by a
#       recorded FAIL).
#   1 - at least one test genuinely FAILed, at least one Evidence Record
#       was REJECTED by anti-bluff-validate.sh (this includes a SKIPPED
#       record rejected for a generic/non-specific assertion_summary),
#       zero real JUnit XML evidence was produced by this invocation, ZERO
#       individual tests were parsed out of the reports that were found, at
#       least one report file could not be parsed, or Gradle exited non-zero
#       with no recorded FAIL explaining it.
#   2 - usage/precondition error (repo path or ./gradlew missing).

set -uo pipefail
# Deliberately NOT `set -e`: this script's whole job is to keep going after
# an individual test FAILs (that failure IS the real, wanted signal for
# that test's own Evidence Record) — every risky command below is
# explicitly guarded, never relying on inherited errexit to stop the
# script.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/pipeline/lib/evidence.sh
source "${SCRIPT_DIR}/lib/evidence.sh"
# shellcheck source=scripts/pipeline/lib/anti-bluff-validate.sh
source "${SCRIPT_DIR}/lib/anti-bluff-validate.sh"

REPO_PATH="${1:-}"
PHASE_DIR="${2:-}"

if [[ -z "$REPO_PATH" ]]; then
  REPO_PATH="$(git rev-parse --show-toplevel)"
fi

if [[ ! -x "${REPO_PATH}/gradlew" ]]; then
  echo "phase-02-test-kotlin: precondition failed — no executable ./gradlew under '${REPO_PATH}'" >&2
  exit 2
fi

if [[ -z "$PHASE_DIR" ]]; then
  RUN_ID="$(date -u +%Y-%m-%dT%H-%M-%SZ)"
  PHASE_DIR="${REPO_PATH}/.lava-ci-evidence/pipeline-runs/${RUN_ID}/phase-02"
fi

CATEGORY_DIR="${PHASE_DIR}/kotlin-unit"
RAW_DIR="${CATEGORY_DIR}/raw"
mkdir -p "$RAW_DIR"

MARKER_FILE="${CATEGORY_DIR}/.gradle-test-run-marker"
GRADLE_LOG="${RAW_DIR}/_gradle-test-full-output.log"
PARSED_JSONL="${RAW_DIR}/_parsed-tests.jsonl"
PARSER_STDERR="${RAW_DIR}/_parser-stderr.log"

echo "phase-02-test-kotlin: repo=${REPO_PATH}"
echo "phase-02-test-kotlin: phase_dir=${PHASE_DIR}"
echo "phase-02-test-kotlin: invoking: ./gradlew --no-daemon --continue --rerun-tasks test"
echo "phase-02-test-kotlin: (large multi-module project — this may take several minutes; no time ceiling)"

touch "$MARKER_FILE"
GRADLE_START_EPOCH=$(date +%s)
( cd "$REPO_PATH" && ./gradlew --no-daemon --continue --rerun-tasks test ) > "$GRADLE_LOG" 2>&1
GRADLE_EXIT_CODE=$?
GRADLE_END_EPOCH=$(date +%s)
GRADLE_DURATION=$((GRADLE_END_EPOCH - GRADLE_START_EPOCH))

echo "phase-02-test-kotlin: ./gradlew --no-daemon --continue --rerun-tasks test exited ${GRADLE_EXIT_CODE} after ${GRADLE_DURATION}s"
echo "phase-02-test-kotlin: full real Gradle output captured at ${GRADLE_LOG#$REPO_PATH/}"

# --- Enumerate real, freshly-produced JUnit XML reports ---------------------
declare -a XML_FILES=()
while IFS= read -r -d '' f; do
  XML_FILES+=("$f")
done < <(find "$REPO_PATH" \
  \( -path "${REPO_PATH}/submodules" -o -path "${REPO_PATH}/Submodules" \
     -o -path "${REPO_PATH}/.claude" -o -path "${REPO_PATH}/.git" \
     -o -path "${REPO_PATH}/.lava-ci-evidence" \) -prune \
  -o -type f -path '*/build/test-results/*/TEST-*.xml' -newer "$MARKER_FILE" -print0 \
  | sort -z)

echo "phase-02-test-kotlin: ${#XML_FILES[@]} real, freshly-written JUnit XML report file(s) found"

if [[ ${#XML_FILES[@]} -eq 0 ]]; then
  echo "phase-02-test-kotlin: ERROR — zero JUnit XML reports were produced by this invocation" >&2
  echo "  (see ${GRADLE_LOG#$REPO_PATH/} for the real Gradle output; a compile failure before any" >&2
  echo "  test task ran is the most likely cause of a genuine zero-evidence outcome)" >&2
  exit 1
fi

# --- Parse every real <testcase> out of every real XML file -----------------
# Embedded Python (mirrors scripts/pipeline/lib/evidence.sh's own
# `python3 - <args> <<'PYEOF'` pattern) does the actual XML parsing via the
# standard-library `xml.etree.ElementTree` — real, structured parsing, not
# ad-hoc grep/awk against XML text. It performs, in order:
#   1. Parse every real <testcase> across every real XML file.
#   2. Derive each one's module (Gradle project path) + task-variant from
#      its own real file path (relative to REPO_PATH).
#   3. Dedup pass: disambiguate any bare "ClassName#method" test_id that
#      would otherwise collide across more than one (module, variant) pair
#      — see header comment "test_id uniqueness" above.
#   4. Write one real, human-readable raw-output snippet file per test
#      under RAW_DIR (this becomes each record's raw_output_ref).
#   5. Print one JSON object per test, one per line, to stdout.
python3 - "$REPO_PATH" "$RAW_DIR" "${XML_FILES[@]}" > "$PARSED_JSONL" 2> "$PARSER_STDERR" <<'PYEOF'
import hashlib
import json
import os
import re
import sys

try:
    # Prefer defusedxml when available: these XML files are Gradle's own
    # local output (not attacker-supplied), so the classic XXE/billion-
    # laughs threat model barely applies here -- but hardening a parser
    # against external-entity/DTD expansion is free when the library is
    # already on the host, so prefer it defensively.
    import defusedxml.ElementTree as ET
except ImportError:  # pragma: no cover - falls back to stdlib if absent
    import xml.etree.ElementTree as ET

repo_path = os.path.realpath(sys.argv[1])
raw_dir = sys.argv[2]
xml_files = sys.argv[3:]

_CTRL_RE = re.compile(r'[\x00-\x08\x0b\x0c\x0e-\x1f]')
_WS_RE = re.compile(r'\s+')
_SANITIZE_RE = re.compile(r'[^A-Za-z0-9._-]')
_COLLAPSE_RE = re.compile(r'_{2,}')


def one_line(s, maxlen=1000):
    """Collapse to a single, control-character-free line — safe to embed
    in a JSON string that a bash reader will treat as one logical field."""
    if not s:
        return ""
    s = s.replace("\r", " ").replace("\n", " | ")
    s = _CTRL_RE.sub("", s)
    s = _WS_RE.sub(" ", s).strip()
    if len(s) > maxlen:
        s = s[:maxlen] + "...(truncated)"
    return s


def sanitize_for_filename(s):
    s = _SANITIZE_RE.sub("_", s)
    s = _COLLAPSE_RE.sub("_", s).strip("_")
    return s or "unnamed-test"


MARKER = "/build/test-results/"
records = []

for xf in xml_files:
    try:
        tree = ET.parse(xf)
    except Exception as e:  # noqa: BLE001 - malformed/rejected XML must not abort the whole run
        print(f"WARN: failed to parse '{xf}' as XML: {e}", file=sys.stderr)
        continue

    idx = xf.find(MARKER)
    if idx == -1:
        module_rel_dir = os.path.relpath(os.path.dirname(xf), repo_path)
        task_variant = "test"
    else:
        module_rel_dir = os.path.relpath(xf[:idx], repo_path)
        rest = xf[idx + len(MARKER):]
        task_variant = rest.split("/", 1)[0] if "/" in rest else "test"
    gradle_module_path = ":" + module_rel_dir.replace("/", ":")

    root = tree.getroot()
    for tc in root.iter("testcase"):
        classname = tc.get("classname", "") or ""
        name = tc.get("name", "") or ""
        time_s = tc.get("time", "") or ""
        bare_test_id = f"{classname}#{name}"

        failure = tc.find("failure")
        error = tc.find("error")
        skipped = tc.find("skipped")
        fail_node = failure if failure is not None else error

        if fail_node is not None:
            kind = "failure" if failure is not None else "error"
            ftype = fail_node.get("type", "") or ""
            fmsg = fail_node.get("message", "") or ""
            ftext = fail_node.text or ""
            excerpt = one_line(ftext, 500)
            result = "FAIL"
            summary_bits = [f'JUnit XML reports a real {kind} for this test']
            if ftype:
                summary_bits.append(f'type="{ftype}"')
            if fmsg:
                summary_bits.append(f'message="{one_line(fmsg, 400)}"')
            if excerpt:
                summary_bits.append(f'stack-excerpt="{excerpt}"')
            assertion_summary = one_line(" ".join(summary_bits))
            raw_lines = [
                f"module: {module_rel_dir}",
                f"gradle task: {gradle_module_path}:{task_variant}",
                f"classname: {classname}",
                f"testcase name: {name}",
                f"time: {time_s}s",
                f"outcome kind: {kind}",
                f"failure type: {ftype}",
                f"failure message: {fmsg}",
                "--- real stack trace text (from JUnit XML) ---",
                ftext,
                "--- source XML file ---",
                xf,
            ]
        elif skipped is not None:
            result = "SKIPPED"
            assertion_summary = one_line(
                f'Genuinely did not execute: real JUnit XML reports a <skipped/> '
                f'child for testcase "{name}" (classname="{classname}") -- the test '
                f'body never ran, so this is not proof the feature works, but it is '
                f'also not a real assertion failure; recorded honestly as SKIPPED '
                f'per data-model.md\'s Evidence Record design, never as a fabricated '
                f'PASS or a misleading FAIL'
            )
            raw_lines = [
                f"module: {module_rel_dir}",
                f"gradle task: {gradle_module_path}:{task_variant}",
                f"classname: {classname}",
                f"testcase name: {name}",
                f"time: {time_s}s",
                "outcome kind: skipped",
                "--- source XML file ---",
                xf,
            ]
        else:
            result = "PASS"
            assertion_summary = one_line(
                f'real JUnit XML testcase for "{name}" reports no <failure>/<error> '
                f'element (Gradle test task genuinely executed it in {time_s}s)'
            )
            raw_lines = [
                f"module: {module_rel_dir}",
                f"gradle task: {gradle_module_path}:{task_variant}",
                f"classname: {classname}",
                f"testcase name: {name}",
                f"time: {time_s}s",
                "outcome kind: pass (no failure/error child element in the real JUnit XML testcase)",
                "--- source XML file ---",
                xf,
            ]

        command = f'./gradlew --no-daemon {gradle_module_path}:{task_variant} --tests "{classname}"'

        records.append({
            "bare_test_id": bare_test_id,
            "module_rel_dir": module_rel_dir,
            "task_variant": task_variant,
            "result": result,
            "assertion_summary": assertion_summary,
            "raw_content": "\n".join(raw_lines) + "\n",
            "command": command,
        })

# --- Dedup pass: disambiguate any bare test_id shared by >1 (module,
# task_variant) pair (the real core/network/impl debug+release case, and
# any future analogous collision) — see header comment above.
key_counts = {}
for r in records:
    key = (r["bare_test_id"], r["module_rel_dir"], r["task_variant"])
    seen = key_counts.setdefault(r["bare_test_id"], set())
    seen.add((r["module_rel_dir"], r["task_variant"]))

for r in records:
    distinct_contexts = key_counts[r["bare_test_id"]]
    if len(distinct_contexts) > 1:
        r["test_id"] = f'{r["bare_test_id"]} [{r["task_variant"]}]'
    else:
        r["test_id"] = r["bare_test_id"]

# --- Write raw snippet files + emit one JSON line per test ------------------
used_filenames = set()
for r in records:
    base = sanitize_for_filename(r["test_id"])
    fname = base + ".txt"
    if fname in used_filenames:
        h = hashlib.sha1(
            (r["module_rel_dir"] + "|" + r["task_variant"] + "|" + r["test_id"]).encode("utf-8")
        ).hexdigest()[:8]
        fname = f"{base}_{h}.txt"
    used_filenames.add(fname)

    raw_path = os.path.join(raw_dir, fname)
    with open(raw_path, "w", encoding="utf-8") as fh:
        fh.write(r["raw_content"])

    out = {
        "test_id": r["test_id"],
        "module_rel_dir": r["module_rel_dir"],
        "task_variant": r["task_variant"],
        "result": r["result"],
        "assertion_summary": r["assertion_summary"],
        "raw_file": raw_path,
        "command": r["command"],
    }
    print(json.dumps(out, ensure_ascii=False))
PYEOF
PARSER_EXIT=$?

if [[ -s "$PARSER_STDERR" ]]; then
  echo "phase-02-test-kotlin: parser warnings (see ${PARSER_STDERR#$REPO_PATH/}):"
  sed 's/^/  /' "$PARSER_STDERR"
fi

if [[ $PARSER_EXIT -ne 0 ]]; then
  echo "phase-02-test-kotlin: ERROR — the embedded XML parser itself failed (exit ${PARSER_EXIT})" >&2
  exit 1
fi

TOTAL_TESTS=$(wc -l < "$PARSED_JSONL" | tr -d '[:space:]')
echo "phase-02-test-kotlin: ${TOTAL_TESTS} individual test(s) parsed from real JUnit XML"

# --- Write + validate one Evidence Record per real test ---------------------
PASS_COUNT=0
FAIL_COUNT=0
SKIPPED_COUNT=0
VALIDATED_COUNT=0
REJECTED_COUNT=0
declare -a FAILED_TESTS=()
declare -a SKIPPED_TESTS=()
declare -a REJECTED_RECORDS=()
declare -A MODULE_SEEN=()
declare -A MODULE_TOTAL=()
declare -A MODULE_PASS=()
declare -A MODULE_FAIL=()

# Representative-example bookkeeping for the final human-readable report
# (per this task's own instruction: don't print every record's path at
# this scale — just a rollup + a handful of real examples).
declare -a EXAMPLE_PASS_RECORDS=()
declare -a EXAMPLE_FAIL_RECORDS=()

while IFS= read -r line; do
  [[ -z "$line" ]] && continue

  IFS=$'\t' read -r f_test_id f_module f_variant f_result f_summary f_rawfile f_command \
    <<< "$(jq -r '[.test_id, .module_rel_dir, .task_variant, .result, .assertion_summary, .raw_file, .command] | @tsv' <<< "$line")"

  module_key="${f_module} (${f_variant})"
  MODULE_SEEN["$f_module"]=1
  MODULE_TOTAL["$module_key"]=$(( ${MODULE_TOTAL["$module_key"]:-0} + 1 ))

  if [[ "$f_result" == "PASS" ]]; then
    PASS_COUNT=$((PASS_COUNT + 1))
    MODULE_PASS["$module_key"]=$(( ${MODULE_PASS["$module_key"]:-0} + 1 ))
  elif [[ "$f_result" == "SKIPPED" ]]; then
    SKIPPED_COUNT=$((SKIPPED_COUNT + 1))
    SKIPPED_TESTS+=("${f_module} (${f_variant}) :: ${f_test_id} -- ${f_summary}")
  else
    FAIL_COUNT=$((FAIL_COUNT + 1))
    MODULE_FAIL["$module_key"]=$(( ${MODULE_FAIL["$module_key"]:-0} + 1 ))
    FAILED_TESTS+=("${f_module} (${f_variant}) :: ${f_test_id} -- ${f_summary}")
  fi

  record_path=""
  if ! record_path="$(write_evidence_record "$PHASE_DIR" "$f_test_id" "kotlin-unit" "$f_command" "$f_result" "$f_summary" "$f_rawfile")"; then
    echo "  ERROR: write_evidence_record failed for ${f_test_id}" >&2
    REJECTED_COUNT=$((REJECTED_COUNT + 1))
    REJECTED_RECORDS+=("${f_test_id} (evidence-write failure)")
    continue
  fi

  if validate_evidence_record "$record_path" >/dev/null 2>&1; then
    VALIDATED_COUNT=$((VALIDATED_COUNT + 1))
    if [[ "$f_result" == "PASS" && ${#EXAMPLE_PASS_RECORDS[@]} -lt 3 ]]; then
      EXAMPLE_PASS_RECORDS+=("$record_path")
    fi
    if [[ "$f_result" == "FAIL" ]]; then
      EXAMPLE_FAIL_RECORDS+=("$record_path")
    fi
  else
    REJECTED_COUNT=$((REJECTED_COUNT + 1))
    REJECTED_RECORDS+=("${f_test_id} ($(jq -r '.anti_bluff_status' "$record_path" 2>/dev/null || echo REJECTED))")
  fi
done < "$PARSED_JSONL"

# --- (ii) A non-zero Gradle exit that no parsed testcase explains -----------
# See the header note. Evaluated BEFORE the unparseable-report pass below so
# that "did any real TEST fail" is what decides whether Gradle's own non-zero
# exit is already accounted for.
UNEXPLAINED_GRADLE=0
if [[ $GRADLE_EXIT_CODE -ne 0 && $FAIL_COUNT -eq 0 ]]; then
  UNEXPLAINED_GRADLE=1
  gradle_fail_raw="${RAW_DIR}/_gradle-unexplained-nonzero-exit.log"
  gradle_fail_lines="$(grep -aE '^(FAILURE|e: |\* What went wrong|> Task .* FAILED|Execution failed)' "$GRADLE_LOG" 2>/dev/null | head -n 8 || true)"
  {
    echo "# './gradlew --no-daemon --continue --rerun-tasks test' exited ${GRADLE_EXIT_CODE}"
    echo "# after ${GRADLE_DURATION}s, but not one of the ${TOTAL_TESTS} parsed testcase(s)"
    echo "# reported a failure. Because this invocation uses --continue, a non-zero exit"
    echo "# with no failing testcase means a task failed WITHOUT producing JUnit XML at"
    echo "# all -- a test-source compile failure being the everyday case, in which case"
    echo "# that module's tests never ran and are missing from this run's evidence."
    echo "# --- real Gradle failure lines ---"
    if [[ -n "$gradle_fail_lines" ]]; then
      printf '%s\n' "$gradle_fail_lines"
    else
      echo "(no FAILURE/e:/What-went-wrong line matched; full log tail follows)"
    fi
    echo "# --- tail of the real full Gradle log (${GRADLE_LOG#$REPO_PATH/}) ---"
    tail -n 40 "$GRADLE_LOG" 2>/dev/null || true
  } > "$gradle_fail_raw"

  gradle_summary_bits="$(printf '%s' "$gradle_fail_lines" | tr '\n' ' ' | sed -E 's/[[:space:]]+/ /g' | cut -c1-500)"
  gradle_summary="./gradlew --continue test exited ${GRADLE_EXIT_CODE} after ${GRADLE_DURATION}s while all ${TOTAL_TESTS} parsed testcase(s) reported no failure — a task failed without producing any JUnit XML, so its module's tests are missing from this evidence set. Real Gradle output: \"${gradle_summary_bits:-<no FAILURE line matched; see raw log>}\""

  gradle_record=""
  if gradle_record="$(write_evidence_record "$PHASE_DIR" "gradlew-test-invocation#(unexplained-nonzero-exit)" \
      "kotlin-unit" "./gradlew --no-daemon --continue --rerun-tasks test" "FAIL" \
      "$gradle_summary" "$gradle_fail_raw")"; then
    FAIL_COUNT=$((FAIL_COUNT + 1))
    FAILED_TESTS+=("(gradle invocation) :: exited ${GRADLE_EXIT_CODE} with no failing testcase to explain it")
    if validate_evidence_record "$gradle_record" >/dev/null 2>&1; then
      VALIDATED_COUNT=$((VALIDATED_COUNT + 1))
    else
      REJECTED_COUNT=$((REJECTED_COUNT + 1))
      REJECTED_RECORDS+=("gradlew-test-invocation#(unexplained-nonzero-exit) ($(jq -r '.anti_bluff_status' "$gradle_record" 2>/dev/null || echo REJECTED))")
    fi
  else
    echo "  ERROR: write_evidence_record failed for the unexplained Gradle exit" >&2
    REJECTED_COUNT=$((REJECTED_COUNT + 1))
    REJECTED_RECORDS+=("gradlew-test-invocation#(unexplained-nonzero-exit) (evidence-write failure)")
  fi
fi

# --- (i) One FAIL Evidence Record per report file that could not be read ----
UNPARSEABLE_COUNT=0
while IFS= read -r warn_line; do
  [[ "$warn_line" =~ ^WARN:\ failed\ to\ parse\ \'(.*)\'\ as\ XML:\ (.*)$ ]] || continue
  bad_xml="${BASH_REMATCH[1]}"
  parse_err="${BASH_REMATCH[2]}"
  bad_rel="${bad_xml#$REPO_PATH/}"
  UNPARSEABLE_COUNT=$((UNPARSEABLE_COUNT + 1))

  bad_raw="${RAW_DIR}/$(printf '%s' "$bad_rel" | tr '/' '_').unparseable.log"
  {
    echo "# JUnit XML report file written by this run that could NOT be parsed"
    echo "# path: ${bad_rel}"
    echo "# parser error: ${parse_err}"
    echo "# Every test this report covers is MISSING from this run's Evidence Records."
    echo "# --- real first 2000 bytes of the unreadable file ---"
    head -c 2000 "$bad_xml" 2>/dev/null || echo "(file unreadable)"
    echo ""
  } > "$bad_raw"

  bad_head="$(head -c 300 "$bad_xml" 2>/dev/null | tr '\n' ' ' | sed -E 's/[[:space:]]+/ /g' || true)"
  bad_summary="Gradle wrote JUnit XML report '${bad_rel}' but it could not be parsed as XML (${parse_err}) — every test it covers is MISSING from this run's evidence, so this run's per-test counts are incomplete. Real first bytes of the unreadable file: \"${bad_head}\""

  bad_record=""
  if bad_record="$(write_evidence_record "$PHASE_DIR" "${bad_rel}#(unparseable-report)" "kotlin-unit" \
      "./gradlew --no-daemon --continue --rerun-tasks test" "FAIL" "$bad_summary" "$bad_raw")"; then
    FAIL_COUNT=$((FAIL_COUNT + 1))
    FAILED_TESTS+=("(unreadable report) :: ${bad_rel} -- ${parse_err}")
    if validate_evidence_record "$bad_record" >/dev/null 2>&1; then
      VALIDATED_COUNT=$((VALIDATED_COUNT + 1))
    else
      REJECTED_COUNT=$((REJECTED_COUNT + 1))
      REJECTED_RECORDS+=("${bad_rel}#(unparseable-report) ($(jq -r '.anti_bluff_status' "$bad_record" 2>/dev/null || echo REJECTED))")
    fi
  else
    echo "  ERROR: write_evidence_record failed for unparseable report ${bad_rel}" >&2
    REJECTED_COUNT=$((REJECTED_COUNT + 1))
    REJECTED_RECORDS+=("${bad_rel}#(unparseable-report) (evidence-write failure)")
  fi
done < "$PARSER_STDERR"

# --- Summary ------------------------------------------------------------
echo ""
echo "phase-02-test-kotlin: SUMMARY"
echo "  modules with tests:   ${#MODULE_SEEN[@]}"
echo "  total individual tests: ${TOTAL_TESTS}"
echo "  PASS:                  ${PASS_COUNT}"
echo "  FAIL:                  ${FAIL_COUNT}"
echo "  SKIPPED (real, anti-bluff-validated, honestly reported): ${SKIPPED_COUNT}"
echo "  anti_bluff validated:  ${VALIDATED_COUNT}"
echo "  anti_bluff REJECTED:   ${REJECTED_COUNT}"
echo "  unreadable JUnit XML report file(s) (each a FAIL record): ${UNPARSEABLE_COUNT}"
echo "  unexplained non-zero Gradle exit (a FAIL record):         ${UNEXPLAINED_GRADLE}"
echo ""
echo "  Per-module breakdown (module (gradle task variant): total/pass/fail):"
for key in "${!MODULE_TOTAL[@]}"; do
  echo "    - ${key}: ${MODULE_TOTAL[$key]}/${MODULE_PASS[$key]:-0}/${MODULE_FAIL[$key]:-0}"
done | sort

if [[ ${#FAILED_TESTS[@]} -gt 0 ]]; then
  echo ""
  echo "  Failed tests:"
  for t in "${FAILED_TESTS[@]}"; do
    echo "    - ${t}"
  done
fi

if [[ ${#SKIPPED_TESTS[@]} -gt 0 ]]; then
  echo ""
  echo "  Skipped tests (real reason captured, SKIPPED Evidence Record written and anti-bluff-validated):"
  for t in "${SKIPPED_TESTS[@]}"; do
    echo "    - ${t}"
  done
fi

if [[ ${#REJECTED_RECORDS[@]} -gt 0 ]]; then
  echo ""
  echo "  Rejected Evidence Records:"
  for r in "${REJECTED_RECORDS[@]}"; do
    echo "    - ${r}"
  done
fi

echo ""
echo "  Representative PASS examples:"
for p in "${EXAMPLE_PASS_RECORDS[@]}"; do
  echo "    - ${p#$REPO_PATH/}"
done

if [[ ${#EXAMPLE_FAIL_RECORDS[@]} -gt 0 ]]; then
  echo ""
  echo "  All FAIL examples (full detail, not truncated per this task's own instruction):"
  for f in "${EXAMPLE_FAIL_RECORDS[@]}"; do
    echo "    - ${f#$REPO_PATH/}"
  done
fi

if [[ "${TOTAL_TESTS:-0}" -eq 0 ]]; then
  echo "" >&2
  echo "phase-02-test-kotlin: ERROR — ${#XML_FILES[@]} JUnit XML report file(s) were found but ZERO" >&2
  echo "  individual testcases were parsed out of them. Nothing was proven and (apart from any" >&2
  echo "  unreadable-report records above) no Evidence Record exists; reporting PASS here would" >&2
  echo "  be vacuous. See ${PARSER_STDERR#$REPO_PATH/} and ${GRADLE_LOG#$REPO_PATH/} for the real output." >&2
  exit 1
fi

if [[ $FAIL_COUNT -gt 0 || $REJECTED_COUNT -gt 0 ]]; then
  exit 1
fi

exit 0

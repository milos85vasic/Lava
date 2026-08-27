#!/usr/bin/env bash
# scripts/ci.sh — local-only CI gate for Lava.
#
# Per the Local-Only CI/CD constitutional rule, this script IS the
# project's CI/CD apparatus. The same script runs in three modes:
#
#   --changed-only   Fast subset for the pre-push hook (Spotless,
#                    unit tests of changed modules, constitutional
#                    doc parser, forbidden-files check). No
#                    real-device tests; no mutation tests.
#
#   --full           All gates — unit tests across every module,
#                    parity gate, mutation tests where wired,
#                    fixture freshness, Compose UI Challenge Tests
#                    (requires a connected Android device or
#                    emulator). Used at tag time.
#
#   (default)        Same as --full.
#
# Per Sixth Law clause 5: passing CI is necessary, NOT sufficient for
# a release. The operator real-device verification per Task 5.22 of
# SP-3a is the load-bearing acceptance gate; this script certifies the
# codebase is shippable, not that the user-visible feature is shipped.

set -euo pipefail

cd "$(dirname "$0")/.."

MODE="${1:---full}"
EVIDENCE_DIR=".lava-ci-evidence/$(date -u +%Y-%m-%dT%H-%M-%SZ)"
mkdir -p "$EVIDENCE_DIR"

# ---------------------------------------------------------------------
# 1. Hosted-CI forbidden-files check (Local-Only CI/CD rule).
#
# Scoped to `git ls-files` -- i.e. files actually TRACKED on the
# current branch -- not a raw filesystem `find .`. A raw find also
# walks (a) OTHER worktrees checked out under .claude/worktrees/ (each
# is a different branch's file tree, physically nested under this
# repo's working directory by the harness, but irrelevant to what THIS
# branch is about to push) and (b) vendored/nested third-party content
# inside submodules-of-submodules (e.g. HelixQA's tools/opensource/*),
# which legitimately ship upstream .github/workflows/ files that are
# that nested repo's own concern, not this branch's. `git ls-files`
# naturally excludes both: worktree paths aren't part of this branch's
# tree, and submodules appear as gitlinks (not descended into) unless
# --recurse-submodules is passed, which it deliberately is not here --
# submodule-internal governance is scoped separately per §6.F.
# ---------------------------------------------------------------------
echo "==> Hosted-CI forbidden-files check"
forbidden=$(git ls-files -z | tr '\0' '\n' | grep -E \
  '(^|/)\.github/workflows/|(^|/)\.gitlab-ci\.yml$|(^|/)\.circleci/|(^|/)azure-pipelines\.yml$|(^|/)bitbucket-pipelines\.yml$|(^|/)Jenkinsfile$|(^|/)appveyor\.yml$|(^|/)\.travis\.yml$' \
  || true)
if [[ -n "$forbidden" ]]; then
  echo "FORBIDDEN HOSTED-CI FILES detected (Local-Only CI/CD rule):" >&2
  echo "$forbidden" >&2
  exit 1
fi

# ---------------------------------------------------------------------
# 2. Host-power forbidden-command regex check (Host Stability rule).
#
# This check looks for actual invocations (e.g. `systemctl suspend`)
# in scripts and code. We require an anchor that distinguishes a real
# command from a regex source in a docstring: either "$(", "`", "; ",
# at line-start (^), or after `&& `, `|| `. That is also what a real
# invocation will look like in a script. Documentation that quotes the
# rule (e.g. `the regex (systemctl\s+suspend|...)`) does NOT match.
# ---------------------------------------------------------------------
echo "==> Host-power forbidden-command regex check"
# Require a leading shell context that an invocation has but a regex
# source / KDoc / markdown block does not.
host_power_re='(^|[[:space:]&|;`(])(systemctl[[:space:]]+(suspend|hibernate|poweroff|halt|reboot|kill-user|kill-session)|loginctl[[:space:]]+(suspend|hibernate|poweroff|reboot|kill-user|kill-session|terminate-user|terminate-session)|pm-suspend|pm-hibernate|shutdown[[:space:]]+(-h|-r|-P|-H|now|--halt|--poweroff|--reboot))'
viol=$(grep -rE \
  --exclude-dir=.git --exclude-dir=build --exclude-dir=.gradle \
  --exclude-dir=node_modules --exclude-dir=docs \
  --include='*.sh' --include='*.kts' --include='*.kt' --include='*.go' \
  --include='*.yaml' --include='*.yml' --include='Makefile' \
  "$host_power_re" \
  scripts/ buildSrc/ submodules/ 2>/dev/null \
  | grep -v '^scripts/ci\.sh:' \
  | grep -v '^scripts/bluff-hunt\.sh:' \
  || true)
if [[ -n "$viol" ]]; then
  echo "FORBIDDEN HOST-POWER COMMAND in committed code:" >&2
  echo "$viol" >&2
  exit 1
fi

# ---------------------------------------------------------------------
# 3. Spotless / ktlint.
#
# In --changed-only mode, run spotless only on the modules touched by
# the changed tree (best-effort: SP-3a tracker modules + :app). In
# --full mode, run the whole-project spotlessCheck.
# ---------------------------------------------------------------------
if [[ "$MODE" == "--changed-only" ]]; then
  echo "==> Spotless (SP-3a-scoped subset)"
  ./gradlew --no-daemon \
    :app:spotlessKotlinCheck \
    :core:tracker:api:spotlessKotlinCheck \
    :core:tracker:client:spotlessKotlinCheck \
    :core:tracker:registry:spotlessKotlinCheck \
    :core:tracker:mirror:spotlessKotlinCheck \
    :core:tracker:rutracker:spotlessKotlinCheck \
    :core:tracker:rutor:spotlessKotlinCheck \
    :core:tracker:testing:spotlessKotlinCheck
else
  echo "==> Spotless (whole project)"
  ./gradlew --no-daemon spotlessCheck
fi

# ---------------------------------------------------------------------
# 4. Unit tests on the SP-3a tracker SDK and adjacent modules.
# ---------------------------------------------------------------------
echo "==> Unit tests"
./gradlew --no-daemon \
  :core:tracker:api:test \
  :core:tracker:client:test \
  :core:tracker:registry:test \
  :core:tracker:mirror:test \
  :core:tracker:rutracker:test \
  :core:tracker:rutor:test \
  :core:tracker:testing:test \
  :core:network:impl:test \
  :core:preferences:test

# ---------------------------------------------------------------------
# 5. Constitutional doc parser.
# ---------------------------------------------------------------------
echo "==> Constitutional doc parser"
./scripts/check-constitution.sh

# ---------------------------------------------------------------------
# 5a1. §6.AC non-fatal-coverage scanner (default STRICT-mode after the
# queue drained 2026-05-14). Set LAVA_NONFATAL_STRICT=0 to revert to
# advisory mode (e.g. during a heavy refactor that introduces new error
# paths in bulk).
# ---------------------------------------------------------------------
echo "==> §6.AC non-fatal-coverage scan (STRICT)"
./scripts/check-non-fatal-coverage.sh | tail -3

# ---------------------------------------------------------------------
# 5a2. §6.AB Challenge-Test discrimination scanner (default STRICT after
# all 29 existing Challenge tests carry FALSIFIABILITY REHEARSAL blocks
# in their KDocs). Set LAVA_CHALLENGE_DISCRIMINATION_STRICT=0 to revert.
# ---------------------------------------------------------------------
echo "==> §6.AB Challenge-discrimination scan (STRICT)"
./scripts/check-challenge-discrimination.sh | tail -3

# ---------------------------------------------------------------------
# 5a3. §6.AE per-feature Challenge coverage scanner (default STRICT
# after the queue drained 2026-05-15 — 18 covered + 1 exempted (account)
# + 0 uncovered). Set LAVA_CHALLENGE_COVERAGE_STRICT=0 to revert.
# ---------------------------------------------------------------------
echo "==> §6.AE Challenge-coverage scan (STRICT)"
./scripts/check-challenge-coverage.sh | tail -4

# ---------------------------------------------------------------------
# 5a4. §11.4.32 verify-all-constitution-rules sweep — the enforcement
# engine for every other §11.4.x and CONST-NNN rule. Wraps every
# individual gate above + every hermetic test suite. Per §11.4.32
# itself, this sweep is mandatory after every constitution submodule
# pull. Pre-push gating is INTENTIONAL — sweep failure rejects the push.
# Added 2026-05-15 (Phase 1 of constitution-compliance plan).
# ---------------------------------------------------------------------
echo "==> §11.4.32 verify-all-constitution-rules sweep (STRICT)"
./scripts/verify-all-constitution-rules.sh --json-only > /dev/null
echo "    ✓ §11.4.32 enforcement-engine sweep PASS"

# ---------------------------------------------------------------------
# 5a5. API↔embed source-sync gate (§11.4.69 / §6.J, added 2026-06-06).
# Guarantees the on-device Lava API embed (liblavaapi.so packaged by
# :core:apiengine) is built from EXACTLY the current lava-api-go source —
# no drift. Recomputes the single-source-of-truth hash and compares it to
# the committed manifest core/apiengine/src/main/resources/api-source.hash.
# Runs in BOTH --changed-only and --full (it is pure bash + git, fast, and
# a stale embed is a release blocker regardless of mode). No failure-swallow
# (§6.J): a mismatch exits non-zero and propagates via set -euo pipefail.
# ---------------------------------------------------------------------
echo "==> API↔embed source-sync gate (no drift)"
./scripts/check-api-app-sync.sh

# ---------------------------------------------------------------------
# 5b. Hermetic bash test suites (added 2026-05-05 to close the gap that
# regression tests under tests/ were only run on manual operator trigger).
# Each suite is independent and self-contained: a `run_all.sh` that runs
# every `test_*.sh` and exits non-zero if any fails. set -euo pipefail
# propagates the failure.
# ---------------------------------------------------------------------
echo "==> Hermetic bash test suites"
# §6.J corpus floor (added 2026-08-26, LVA vacuous-pass sweep F9). Three
# silent no-ops lived in this loop, and each one turned a FAILING suite into a
# clean run — the loop reported success having executed nothing:
#
#   BASELINE (tests/pre-push/check9_test.sh exits 1)                 EXIT=1
#   MUTATION A: delete ONLY check4_test.sh — check9 still present,
#               still exits 1 -> LOOP COMPLETED                      EXIT=0
#   MUTATION B: chmod -x tests/firebase/run_all.sh (runner exits 1)  EXIT=0
#   MUTATION C: delete the whole tests/check-constitution directory  EXIT=0
#
# MUTATION A is the sharpest: the flat-layout branch keys on ONE sentinel
# filename, so deleting an UNRELATED file disables an entire failing suite.
# verify-all-constitution-rules.sh fixed exactly this pattern in its own loops
# (an absent suite becomes a FAIL row rather than disappearing); ci.sh carried
# the unfixed copy. This mirrors verify-all's treatment: an absent directory, a
# non-executable runner, and an empty glob are all explicit failures.
#
# The expectation is DERIVED — the suite list below for the directories, and
# the git index for the flat-layout globs — so adding or removing a suite
# cannot silently lower the bar.
declare -a HERMETIC_SUITE_DIRS=(
  tests/firebase tests/ci-sh tests/compose-layout
  tests/tag-helper tests/pre-push tests/check-constitution
  tests/vm-images tests/vm-signing tests/vm-distro
)
suite_failures=()
suites_executed=0
for suite_dir in "${HERMETIC_SUITE_DIRS[@]}"; do
  if [[ ! -d "$suite_dir" ]]; then
    suite_failures+=("$suite_dir — DIRECTORY ABSENT (the suite did not run; before this floor it was skipped in silence)")
    continue
  fi
  runner="$suite_dir/run_all.sh"
  if [[ -x "$runner" ]]; then
    echo "    -> $suite_dir"
    if bash "$runner" >/dev/null; then
      suites_executed=$((suites_executed + 1))
    else
      suite_failures+=("$suite_dir — run_all.sh FAILED")
    fi
  elif [[ -f "$runner" ]]; then
    suite_failures+=("$suite_dir — run_all.sh EXISTS BUT IS NOT EXECUTABLE (chmod +x $runner); the suite did not run")
  elif [[ -f "$suite_dir/check_constitution_test.sh" ]]; then
    # tests/check-constitution has a flat layout (one test_*.sh entry +
    # additional test_*.sh files added per phase, e.g. §6.R Phase 1).
    echo "    -> $suite_dir (flat layout)"
    _ran=0
    if bash "$suite_dir/check_constitution_test.sh" >/dev/null; then
      _ran=$((_ran + 1))
    else
      suite_failures+=("$suite_dir/check_constitution_test.sh — FAILED")
    fi
    for t in "$suite_dir"/test_*.sh; do
      [[ -f "$t" ]] || continue
      if bash "$t" >/dev/null; then _ran=$((_ran + 1)); else suite_failures+=("$t — FAILED"); fi
    done
    _declared="$( { git ls-files -- "$suite_dir/test_*.sh" "$suite_dir/check_constitution_test.sh" 2>/dev/null || true; } | awk 'END{print NR+0}' )"
    if [[ "$_ran" -lt "$_declared" ]]; then
      suite_failures+=("$suite_dir — executed ${_ran} test file(s) but the git index declares ${_declared}: working-tree drift, not a smaller suite")
    fi
    suites_executed=$((suites_executed + 1))
  else
    # tests/pre-push has a flat layout (multiple check<N>_test.sh entries).
    # NOTE: keyed on the GLOB, never on one sentinel filename — MUTATION A above
    # is precisely what a sentinel key produces.
    _ran=0
    for t in "$suite_dir"/check*_test.sh; do
      [[ -f "$t" ]] || continue
      if bash "$t" >/dev/null; then _ran=$((_ran + 1)); else suite_failures+=("$t — FAILED"); fi
    done
    _declared="$( { git ls-files -- "$suite_dir/check*_test.sh" 2>/dev/null || true; } | awk 'END{print NR+0}' )"
    if [[ "$_ran" -eq 0 ]]; then
      suite_failures+=("$suite_dir — NO runner and NO check*_test.sh matched (git index declares ${_declared}); the suite did not run")
    else
      echo "    -> $suite_dir (flat layout, ${_ran} test file(s))"
      if [[ "$_ran" -lt "$_declared" ]]; then
        suite_failures+=("$suite_dir — executed ${_ran} test file(s) but the git index declares ${_declared}: working-tree drift, not a smaller suite")
      fi
      suites_executed=$((suites_executed + 1))
    fi
  fi
done

if [[ ${#suite_failures[@]} -gt 0 ]]; then
  echo "HERMETIC SUITE GATE FAILED:" >&2
  printf '    %s\n' "${suite_failures[@]}" >&2
  echo "  → Examined: ${suites_executed} of ${#HERMETIC_SUITE_DIRS[@]} declared suite(s)" >&2
  echo "  → A suite that is absent, non-executable, or whose glob matched nothing did" >&2
  echo "    NOT pass — it did not run. Skipping it reports 'nothing failed' for work" >&2
  echo "    that was never done (§6.J)." >&2
  echo "  → Do: restore the missing suite (git checkout -- tests/), or chmod +x its" >&2
  echo "    run_all.sh, then re-run." >&2
  exit 1
fi
if [[ "$suites_executed" -eq 0 ]]; then
  echo "HERMETIC SUITE GATE FAILED: ZERO suites executed." >&2
  echo "  → Examined: 0 of ${#HERMETIC_SUITE_DIRS[@]} declared suite(s)" >&2
  echo "  → A clean verdict over an empty suite set asserts nothing (§6.J)." >&2
  echo "  → Do: confirm tests/ is present in this checkout and re-run." >&2
  exit 1
fi
echo "    ${suites_executed}/${#HERMETIC_SUITE_DIRS[@]} hermetic suites executed"

if [[ "$MODE" == "--changed-only" ]]; then
  echo "==> --changed-only: skipping parity, mutation, fixture-freshness, Compose UI"
  echo "$MODE" > "$EVIDENCE_DIR/mode"
  git rev-parse HEAD > "$EVIDENCE_DIR/sha"
  # §6.J (LVA vacuous-pass sweep F21): record the device verdict on BOTH exit
  # paths, so a consumer never has to infer it from the absence of a file.
  # --changed-only declares up front that it does not run the device gate; the
  # field states that as a fact rather than leaving the directory silent.
  echo "skipped" > "$EVIDENCE_DIR/device_tests"
  echo "==> All --changed-only gates passed"
  exit 0
fi

# ---------------------------------------------------------------------
# 5c. Detekt static analysis (Phase 2 completeness program, 2026-06-04).
# Wired via buildSrc convention plugins with per-module config/detekt
# baselines capturing the existing findings; the gate FAILS on any NEW
# finding outside the baselines. Full-mode only — the whole-project run
# is heavier than the changed-only push budget (same rationale as the
# whole-project spotlessCheck above). No failure-swallow (§6.J).
# ---------------------------------------------------------------------
echo "==> Detekt static analysis (whole project)"
./gradlew --no-daemon detekt

# ---------------------------------------------------------------------
# 5d. Go vet gate for lava-api-go (Phase 2 completeness program). Native,
# no container, blocking. golangci-lint (containerized) + Kover coverage
# report are wired as runnable tools (lava-api-go `make lint`/`make cover`
# + Gradle `koverXmlReport`) but their ci.sh enforcement is OWED in a
# focused follow-up (exact task-name + container-availability handling)
# — tracked in docs/CONTINUATION.md; run them manually until then.
# ---------------------------------------------------------------------
echo "==> Go vet (lava-api-go)"
( cd lava-api-go && make vet )

# ---------------------------------------------------------------------
# 6. SwitchingNetworkApi parity gate (full mode).
# ---------------------------------------------------------------------
echo "==> SwitchingNetworkApi parity gate"
# 2026-05-05 anti-bluff fix (§6.J/§6.L): the previous form swallowed
# `gradle test --tests "*ParityTest*"` failures with `|| echo WARN`,
# making this gate report green regardless of whether the parity test
# actually passed or even existed. The new form: if the parity test
# class is wired AND the test passes, the gate passes; if either is
# false, the gate fails (exit 1) — propagated via set -euo pipefail.
# When the parity test class is intentionally not yet wired in a
# given branch, the gate must be EXPLICITLY skipped with a logged
# reason in this script, not silently swallowed.
if find core/network/impl/src/test -name '*ParityTest*' -type f 2>/dev/null | grep -q .; then
  ./gradlew --no-daemon :core:network:impl:test \
    -Pandroid.testInstrumentationRunnerArguments.class='*ParityTest*'
else
  echo "    Parity test class not present in this commit — gate SKIPPED with explicit reason."
  echo "    To re-enable, add a test under core/network/impl/src/test/.../*ParityTest*.kt."
fi

# ---------------------------------------------------------------------
# 7. Mutation tests (PITest). TODO(SP-3a-bridge): not wired yet.
# ---------------------------------------------------------------------
# TODO(SP-3a-bridge): :core:tracker:rutor:pitest and
# :core:tracker:rutracker:pitest are not yet configured. The
# documentation-polish follow-up plan adds the PIT plugin to the
# tracker module convention plugin.
# ./gradlew --no-daemon :core:tracker:rutor:pitest :core:tracker:rutracker:pitest

# ---------------------------------------------------------------------
# 8. Fixture freshness check.
# ---------------------------------------------------------------------
echo "==> Fixture freshness check"
./scripts/check-fixture-freshness.sh

# ---------------------------------------------------------------------
# 9. Compose UI Challenge Tests (requires a connected device).
# ---------------------------------------------------------------------
DEVICE_TESTS_RAN=skipped
if [[ -n "${ANDROID_SERIAL:-}" ]] || \
   ([[ -n "${ANDROID_HOME:-}" ]] && \
    "$ANDROID_HOME/platform-tools/adb" devices 2>/dev/null | \
    awk 'NR>1 && $2=="device"' | grep -q .); then
  echo "==> Compose UI Challenge Tests (connected device detected)"
  # 2026-05-05 anti-bluff fix (§6.J/§6.L): the previous form used the
  # gradle `--tests` flag which AGP 8.9+ rejects with "Unknown command-
  # line option", AND swallowed the resulting BUILD FAILED with
  # `|| echo WARN`, then unconditionally printed "All gates passed" —
  # a textbook §6.J bluff (gate reports green while reality is broken).
  # Replaced with the AGP-compatible androidTestRunnerArguments.package
  # filter, AND removed the WARN swallow: a real failure now propagates
  # via set -euo pipefail. Operator-environment trust-anchor failures
  # against personal devices are HONEST signals — they document a real
  # operator-environment gap, not a script-level bluff.
  ./gradlew --no-daemon :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.package=lava.app.challenges
  DEVICE_TESTS_RAN=ran
else
  echo "==> Compose UI Challenge Tests SKIPPED — no connected Android device"
  echo "    Per Sixth Law clause 5, operator real-device verification"
  echo "    (Task 5.22) is required before tagging. CI green here is"
  echo "    necessary, not sufficient."
fi

# ---------------------------------------------------------------------
# 10. Record evidence.
#
# §6.J (added 2026-08-26, LVA vacuous-pass sweep F21). `--full` used to print
# "All gates passed" and exit 0 with the Compose UI Challenge Tests skipped
# whenever no device was detected. The skip was announced on stdout, but the
# machine-readable output was byte-identical either way — the evidence
# directory recorded only `mode` and `sha`, with no field saying whether the
# device gate ran. A consumer reading the directory could not tell a full run
# from a device-less one, so `mode=--full` was a claim the evidence did not
# support.
#
# Two changes: the verdict is now RECORDED (device_tests), and a `--full` run
# whose device gate did not run is no longer reported as a full pass. There is
# deliberately NO bypass flag — §6.Z clause 6 forbids one, and a flag that
# converts "did not run" into "passed" is the bluff this floor exists to close.
echo "$MODE" > "$EVIDENCE_DIR/mode"
git rev-parse HEAD > "$EVIDENCE_DIR/sha"
echo "$DEVICE_TESTS_RAN" > "$EVIDENCE_DIR/device_tests"

if [[ "$DEVICE_TESTS_RAN" != "ran" ]]; then
  echo "" >&2
  echo "CI GATE INCOMPLETE: '$MODE' ran every gate EXCEPT the Compose UI Challenge Tests." >&2
  echo "  → Examined: 0 device Challenge test(s) — no connected Android device was detected." >&2
  echo "  → Expected: the device gate to run, because '$MODE' certifies a full run and" >&2
  echo "    scripts/tag.sh consumes this evidence directory as proof of one." >&2
  echo "  → Recorded: device_tests=skipped in $EVIDENCE_DIR (this run is NOT a full pass)." >&2
  echo "  → Cause distinguished: this is not a test failure. The tests did not run at all," >&2
  echo "    which is why reporting 'All gates passed' here would be a §6.J bluff." >&2
  echo "  → Do: bring up a §6.AH-conformant container/VM emulator via" >&2
  echo "    scripts/run-challenge-matrix.sh (never host-direct, never a live ADB device)," >&2
  echo "    or run 'scripts/ci.sh --changed-only' if a device-less subset is what you want." >&2
  exit 1
fi

echo "==> All gates passed (device_tests=$DEVICE_TESTS_RAN)"
echo "Evidence: $EVIDENCE_DIR"

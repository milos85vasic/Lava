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
# ---------------------------------------------------------------------
echo "==> Hosted-CI forbidden-files check"
forbidden=$(find . \
  \( -path './.git' -o -path './build' -o -path '*/build' -o -path './node_modules' -o -path './.gradle' \) -prune -o \
  \( -path '*/.github/workflows/*' -o \
     -name '.gitlab-ci.yml' -o \
     -path '*/.circleci/*' -o \
     -name 'azure-pipelines.yml' -o \
     -name 'bitbucket-pipelines.yml' -o \
     -name 'Jenkinsfile' -o \
     -name 'appveyor.yml' -o \
     -name '.travis.yml' \) \
  -print 2>/dev/null || true)
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
  scripts/ buildSrc/ Submodules/ 2>/dev/null \
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
# 5a3. §6.AE per-feature Challenge coverage scanner (advisory until the
# per-feature backfill pass closes §6.AE-debt). Set
# LAVA_CHALLENGE_COVERAGE_STRICT=1 to fail on uncovered features.
# ---------------------------------------------------------------------
echo "==> §6.AE Challenge-coverage scan (advisory)"
./scripts/check-challenge-coverage.sh | tail -4

# ---------------------------------------------------------------------
# 5b. Hermetic bash test suites (added 2026-05-05 to close the gap that
# regression tests under tests/ were only run on manual operator trigger).
# Each suite is independent and self-contained: a `run_all.sh` that runs
# every `test_*.sh` and exits non-zero if any fails. set -euo pipefail
# propagates the failure.
# ---------------------------------------------------------------------
echo "==> Hermetic bash test suites"
for suite_dir in tests/firebase tests/ci-sh tests/compose-layout \
                 tests/tag-helper tests/pre-push tests/check-constitution \
                 tests/vm-images tests/vm-signing tests/vm-distro; do
  if [[ -d "$suite_dir" ]]; then
    runner="$suite_dir/run_all.sh"
    if [[ -x "$runner" ]]; then
      echo "    -> $suite_dir"
      bash "$runner" >/dev/null
    elif [[ -f "$suite_dir/check_constitution_test.sh" ]]; then
      # tests/check-constitution has a flat layout (one test_*.sh entry +
      # additional test_*.sh files added per phase, e.g. §6.R Phase 1).
      bash "$suite_dir/check_constitution_test.sh" >/dev/null
      for t in "$suite_dir"/test_*.sh; do
        [[ -f "$t" ]] || continue
        bash "$t" >/dev/null
      done
    elif [[ -f "$suite_dir/check4_test.sh" ]]; then
      # tests/pre-push has a flat layout (multiple check<N>_test.sh entries).
      for t in "$suite_dir"/check*_test.sh; do
        bash "$t" >/dev/null
      done
    fi
  fi
done

if [[ "$MODE" == "--changed-only" ]]; then
  echo "==> --changed-only: skipping parity, mutation, fixture-freshness, Compose UI"
  echo "$MODE" > "$EVIDENCE_DIR/mode"
  git rev-parse HEAD > "$EVIDENCE_DIR/sha"
  echo "==> All --changed-only gates passed"
  exit 0
fi

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
else
  echo "==> Compose UI Challenge Tests SKIPPED — no connected Android device"
  echo "    Per Sixth Law clause 5, operator real-device verification"
  echo "    (Task 5.22) is required before tagging. CI green here is"
  echo "    necessary, not sufficient."
fi

# ---------------------------------------------------------------------
# 10. Record evidence.
# ---------------------------------------------------------------------
echo "$MODE" > "$EVIDENCE_DIR/mode"
git rev-parse HEAD > "$EVIDENCE_DIR/sha"
echo "==> All gates passed"
echo "Evidence: $EVIDENCE_DIR"

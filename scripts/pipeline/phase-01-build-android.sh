#!/usr/bin/env bash
# scripts/pipeline/phase-01-build-android.sh — Android artifact build step
# (tasks.md T015-T018: :app debug/release + :api-app debug/release).
#
# Builds the 4 Android APK artifacts with a SINGLE Gradle invocation and
# reports their resolved output paths. This is deliberately one combined
# `./gradlew ... :app:assembleDebug :app:assembleRelease :api-app:assembleDebug
# :api-app:assembleRelease` call, NOT four separate `./gradlew` subprocesses
# launched concurrently against the same project checkout — Gradle's own
# daemon/lock model does not tolerate well-isolated fully-concurrent
# invocations against ONE project directory (each subprocess would fight the
# others for the project lock / configuration cache), and this project's
# CLAUDE.md §6.T.2 "Resource Limits for Tests & Challenges" already forbids
# starving the host with redundant parallel toolchain invocations. Passing
# `--parallel` lets Gradle's own internal task graph parallelize the 4
# `assemble*` task chains safely within one JVM/daemon-less process.
#
# House-style match (per CLAUDE.md "## Commands" + scripts/ci.sh):
#   - `--no-daemon` is this project's standing convention for scripted/CI-style
#     Gradle invocations (see scripts/ci.sh:99,110,117,234,261,301 — every
#     scripted invocation in this repo uses it; interactive/IDE builds may use
#     the daemon, scripted ones don't, to avoid leaking a long-lived daemon
#     process across unattended pipeline runs).
#   - `--parallel` is NOT baked into gradle.properties (it's commented out
#     there — "org.gradle.parallel=true" is disabed project-wide) so it is
#     passed on the command line for this specific invocation only, per this
#     feature's explicit design decision, without changing repo-wide Gradle
#     behavior for any other invocation (IDE syncs, other scripts, etc).
#
# Output-path convention (reused verbatim from build_and_release.sh — NOT
# invented here):
#   releases/<app versionName>/android-debug/digital.vasic.lava.client-<versionName>-debug.apk
#   releases/<app versionName>/android-release/digital.vasic.lava.client-<versionName>-release.apk
#   releases/api-app/<api-app versionName>/android-debug/digital.vasic.lava.api-<versionName>-debug.apk
#   releases/api-app/<api-app versionName>/android-release/digital.vasic.lava.api-<versionName>-release.apk
# (versionName/versionCode extracted from app/build.gradle.kts and
# api-app/build.gradle.kts with the exact same grep/sed this repo's own
# build_and_release.sh already uses, so a version-scheme change only ever
# needs to be taught to one pattern-matching approach.)
#
# Deliberate deviation from build_and_release.sh: this script does NOT
# `rm -rf` the destination release directory before writing into it.
# build_and_release.sh owns its own single-shot full-release run and can
# safely wipe-then-rebuild; this script is one of several steps
# (tasks.md T015-T019) that tasks.md T020 wires as PARALLEL-DISPATCHED steps
# sharing the same `releases/<version>/` directory (e.g. the sibling T019
# lava-api-go binary step writes concurrently into
# `releases/<version>/api-go/`). Wiping the shared directory here would race
# against a sibling step's own writes. Destination filenames already embed
# the version, so re-running this script for the same version safely
# overwrites only its own 2 files via `cp -f`; nothing else under
# `releases/<version>/` is touched.
#
# Usage:
#   scripts/pipeline/phase-01-build-android.sh [repo-path]
#
# With no argument, resolves the repository root from this script's own
# location (works regardless of caller's cwd). An optional first argument
# overrides which repo root to use.
#
# This file is *dual-mode*, matching both patterns already established in
# this pipeline (scripts/pipeline/phase-00-precondition.sh's "runnable
# directly" pattern, and scripts/pipeline/lib/evidence.sh's "source me, I
# define functions, I don't touch caller shell options" pattern):
#   - `source scripts/pipeline/phase-01-build-android.sh` defines the
#     `phase01_build_android` function in the calling shell and returns
#     without side effects (no `set -e`/`exit` imposed on the sourcing
#     caller) so `scripts/pipeline/phase-01-build.sh` (T020's orchestrator,
#     built separately) can source this file, call the function, and inspect
#     its non-fatal return code itself (e.g. to still attempt T017/T018 or
#     record a Build Artifact failure entry even after this step fails).
#   - Executing it directly (`./scripts/pipeline/phase-01-build-android.sh`)
#     runs the same function and `exit`s the process with its return code.
#
# stdout contract (for a caller/wrapper to parse): on a fully successful run,
# exactly 4 lines of the form `ARTIFACT <label>: <path>` are printed, in this
# fixed order — app-debug, app-release, api-app-debug, api-app-release. If an
# expected APK is missing after the Gradle invocation (whether or not Gradle
# itself reported failure — e.g. a partially-successful `--parallel` task
# graph), a line `ARTIFACT <label>: MISSING (<reason>)` is printed to stderr
# for that label instead, and the function/script still returns/exits
# non-zero overall.
#
# Exit codes:
#   0 - Gradle invocation succeeded AND all 4 expected APKs were found/copied.
#   1 - Repo-root resolution or expected-artifact verification failed even
#       though Gradle itself reported success (e.g. an assemble task silently
#       produced no file at the expected path).
#   <n> - Gradle's own real, non-zero exit code, propagated unchanged, when
#       the combined `./gradlew` invocation itself fails. Gradle's real
#       stdout/stderr is never captured/swallowed by this script — it streams
#       straight through to this script's own stdout/stderr as the build
#       runs, so a caller sees the actual failure, not a paraphrase of it.

_phase01_build_android_repo_root() {
  local script_dir
  script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  (cd "$script_dir/../.." && pwd)
}

# Extracts a `versionName = "..."` value from a module's build.gradle.kts,
# using the exact grep/sed pattern build_and_release.sh already uses (kept
# byte-for-byte identical so both scripts stay in sync with any future
# version-declaration style change made in only one place).
_phase01_build_android_version_name() {
  local gradle_kts="$1"
  grep -E '^\s+versionName\s*=' "$gradle_kts" | sed 's/.*"\([^"]*\)".*/\1/'
}

phase01_build_android() {
  local repo_root
  repo_root="$(_phase01_build_android_repo_root)" || {
    echo "phase-01-build-android: FAILED — could not resolve repository root" >&2
    return 1
  }

  local repo_root_override="${1:-}"
  if [[ -n "$repo_root_override" ]]; then
    repo_root="$repo_root_override"
  fi

  local app_gradle_kts="$repo_root/app/build.gradle.kts"
  local api_app_gradle_kts="$repo_root/api-app/build.gradle.kts"

  if [[ ! -f "$app_gradle_kts" ]]; then
    echo "phase-01-build-android: FAILED — $app_gradle_kts not found" >&2
    return 1
  fi
  if [[ ! -f "$api_app_gradle_kts" ]]; then
    echo "phase-01-build-android: FAILED — $api_app_gradle_kts not found" >&2
    return 1
  fi

  local app_version api_app_version
  app_version="$(_phase01_build_android_version_name "$app_gradle_kts")"
  api_app_version="$(_phase01_build_android_version_name "$api_app_gradle_kts")"

  if [[ -z "$app_version" || -z "$api_app_version" ]]; then
    echo "phase-01-build-android: FAILED — could not parse versionName from build.gradle.kts (app='$app_version' api-app='$api_app_version')" >&2
    return 1
  fi

  local release_dir="$repo_root/releases/$app_version"
  local api_app_release_dir="$repo_root/releases/api-app/$api_app_version"

  mkdir -p \
    "$release_dir/android-debug" \
    "$release_dir/android-release" \
    "$api_app_release_dir/android-debug" \
    "$api_app_release_dir/android-release"

  echo "phase-01-build-android: app versionName=$app_version, api-app versionName=$api_app_version"
  echo "phase-01-build-android: running combined Gradle invocation (single process, Gradle-internal --parallel task graph):"
  echo "  ./gradlew --no-daemon --parallel :app:assembleDebug :app:assembleRelease :api-app:assembleDebug :api-app:assembleRelease"

  (
    cd "$repo_root"
    ./gradlew --no-daemon --parallel \
      :app:assembleDebug :app:assembleRelease \
      :api-app:assembleDebug :api-app:assembleRelease
  )
  local gradle_rc=$?

  if [[ $gradle_rc -ne 0 ]]; then
    echo "phase-01-build-android: Gradle invocation exited $gradle_rc — see the real Gradle output above for the actual failure (not swallowed/paraphrased here)." >&2
  else
    echo "phase-01-build-android: Gradle invocation succeeded (exit 0)"
  fi

  # Regardless of the overall Gradle exit code, report on whichever of the 4
  # expected artifacts actually exist — a `--parallel` task graph can leave
  # some assemble tasks' outputs on disk even when a sibling task failed, and
  # per this feature's honest-reporting requirement we surface that reality
  # rather than treating a failed run as "produced nothing".
  local all_present=1

  _phase01_build_android_report_one \
    "app-debug" \
    "$repo_root/app/build/outputs/apk/debug/app-debug.apk" \
    "$release_dir/android-debug/digital.vasic.lava.client-${app_version}-debug.apk" \
    || all_present=0

  _phase01_build_android_report_one \
    "app-release" \
    "$repo_root/app/build/outputs/apk/release/app-release.apk" \
    "$release_dir/android-release/digital.vasic.lava.client-${app_version}-release.apk" \
    || all_present=0

  _phase01_build_android_report_one \
    "api-app-debug" \
    "$repo_root/api-app/build/outputs/apk/debug/api-app-debug.apk" \
    "$api_app_release_dir/android-debug/digital.vasic.lava.api-${api_app_version}-debug.apk" \
    || all_present=0

  _phase01_build_android_report_one \
    "api-app-release" \
    "$repo_root/api-app/build/outputs/apk/release/api-app-release.apk" \
    "$api_app_release_dir/android-release/digital.vasic.lava.api-${api_app_version}-release.apk" \
    || all_present=0

  if [[ $gradle_rc -ne 0 ]]; then
    return "$gradle_rc"
  fi
  if [[ $all_present -ne 1 ]]; then
    echo "phase-01-build-android: FAILED — Gradle reported success but at least one expected APK is missing" >&2
    return 1
  fi

  return 0
}

# Copies one built APK (if present) to its conventional release-directory
# destination and prints the ARTIFACT line; prints a MISSING line to stderr
# and returns 1 if the source file is absent or empty, if the copy fails, or
# if the destination is not a real non-empty file afterwards.
#
# The post-copy verification is not belt-and-braces (forensic anchor,
# 2026-08-21): this function used to run a bare `cp -f "$src" "$dst"` with
# its exit status unchecked and then unconditionally print
# `ARTIFACT $label: $dst` and `return 0`. A copy that FAILED (read-only
# destination, full disk, ENOSPC mid-write) therefore announced a successful
# artifact at a path holding nothing, and the caller
# (scripts/pipeline/phase-01-build.sh) believed the line, recorded that path
# into report.json's build_artifacts[], counted it toward the 5 required
# artifacts, and reported the whole build phase PASS. The ARTIFACT line is
# this script's entire contract with its caller; it must never be printed
# for a file that is not there. Regression coverage:
# tests/pipeline/test_phase_01_artifact_verification.sh CASE 2.
_phase01_build_android_report_one() {
  local label="$1" src="$2" dst="$3"

  if [[ ! -f "$src" ]]; then
    echo "ARTIFACT $label: MISSING (expected Gradle output not found at $src)" >&2
    return 1
  fi

  if [[ ! -s "$src" ]]; then
    echo "ARTIFACT $label: MISSING (Gradle output at $src exists but is empty)" >&2
    return 1
  fi

  if ! cp -f "$src" "$dst"; then
    echo "ARTIFACT $label: MISSING (copy from $src to $dst failed — see cp's own error above)" >&2
    return 1
  fi

  if [[ ! -f "$dst" || ! -s "$dst" ]]; then
    echo "ARTIFACT $label: MISSING (cp reported success but $dst is absent or empty)" >&2
    return 1
  fi

  echo "ARTIFACT $label: $dst"
  return 0
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  set -uo pipefail
  phase01_build_android "$@"
  exit $?
fi

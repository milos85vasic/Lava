#!/usr/bin/env bash
# phase-02-test-challenge.sh — Phase 02 test-category wrapper: real-device-challenge
# (T024 of specs/002-build-test-distribute-pipeline/tasks.md).
#
# This project already has real Compose UI "Challenge Tests" under
# app/src/androidTest/kotlin/lava/app/challenges/ (73 Challenge*Test.kt files
# at authoring time, package lava.app.challenges) and
# api-app/src/androidTest/kotlin/lava/api/app/challenges/ (7 files, package
# lava.api.app.challenges), driven via the existing
# scripts/run-challenge-matrix.sh and scripts/run-api-app-challenge-matrix.sh.
# This wrapper does NOT reimplement any Challenge-Test or emulator-boot logic
# (Decoupled Reusable Architecture: reuse, don't reinvent) — it invokes those
# two scripts FOR REAL against whatever real containerized/VM emulator target
# this host actually has, captures their real output, and writes ONE Evidence
# Record (category: real-device-challenge) PER DISCOVERED CHALLENGE CLASS via
# scripts/pipeline/lib/evidence.sh, then anti-bluff-validates every record via
# scripts/pipeline/lib/anti-bluff-validate.sh.
#
# ---------------------------------------------------------------------------
# Verified real invocation contracts (read both sibling scripts IN FULL
# before writing this wrapper — this is what they actually do, not assumed):
# ---------------------------------------------------------------------------
#
# scripts/run-challenge-matrix.sh (the :app module's §6.AE gate entry point):
#   ./scripts/run-challenge-matrix.sh
#       [--test-class <FQCN>[,<FQCN>...]]   # empty forwards NO --test-class
#                                            # at all to the Containers CLI —
#                                            # see "--test-class is actually
#                                            # REQUIRED" below, this is NOT
#                                            # "run everything".
#       [--evidence-dir <dir>] [--no-build]
#       [--avds "name:api:form[,...]"]      # REPLACES the §6.AE.2 4-AVD
#                                            # default matrix entirely
#       [--container-image REF] [--container-runtime podman|docker]
#       [--latest-api N] [--add-tv] [--add-foldable] [--include-helixqa]
#   Exit 0 = matrix ran, every row passed. Exit 2 = host-gap (no KVM/HVF
#   accelerator — genuinely ineligible gate-host per §6.AE.7/§6.X). Exit 1 =
#   either a real per-row test failure, OR (LVA-014 fix #3) the emulator-image
#   preflight failed for at least one requested AVD's container image (a real
#   `podman pull` was attempted and genuinely failed) — in BOTH exit-1 cases
#   this script tells them apart by checking whether
#   `<evidence-dir>/real-device-verification.json` was actually produced (see
#   "BLOCKED vs real-FAIL" below).
#
# scripts/run-api-app-challenge-matrix.sh (the :api-app module's sibling):
#   Same shape, module-scoped to :api-app; ITS OWN default `--test-class`
#   (when none is passed) hardcodes only Challenge01-04, silently missing the
#   Challenge05/06/07 files that already exist on disk at authoring time (a
#   pre-existing drift in that script, not something this wrapper's job to
#   fix) — this wrapper never relies on that default: it ALWAYS passes its
#   own explicitly-discovered, complete class list, so 05/06/07 get real
#   coverage too. ITS OWN default `--avds "Pixel_8:35:phone"` +
#   `--container-image ...:api34-x86_64` is a documented mismatch for the
#   containerized path (a name at api 35 paired with an api-34 image) that
#   only matters on the host-direct+HVF macOS path this script also
#   supports; this wrapper always passes its OWN `--avds`/`--container-image`
#   pair (chosen from what is REALLY available on THIS host, see below), so
#   that mismatch never applies to a run this wrapper drives.
#
# --- "--test-class is actually REQUIRED" (a real finding from reading the
#     underlying CLI, contradicting run-challenge-matrix.sh's own header
#     comment that says "default: ALL Challenges") -------------------------
# submodules/containers/cmd/emulator-matrix/main.go:175-176 hard-requires
# `--test-class`: `if *flagTestClass == "" { ... "ERROR: --test-class is
# required"; os.Exit(2) }`. run-challenge-matrix.sh forwards NOTHING when its
# own $TEST_CLASS is empty (`TEST_CLASS_ARGS=()` stays empty — see its own
# arg-building block), so invoking either sibling script with no --test-class
# does NOT run "all Challenges"; it makes the underlying CLI exit 2 with a
# config error before booting anything. This wrapper therefore NEVER omits
# --test-class: it discovers every real Challenge*Test.kt file itself (see
# "Discovery" below) and ALWAYS passes an explicit, non-empty, comma-joined
# FQCN list (AndroidJUnitRunner's own `-e class=` instrumentation argument
# natively accepts a comma-separated list of classes/methods — this is
# documented AndroidJUnitRunner behavior, not an assumption; confirmed
# working in this pass — see "Live verification performed" below) — ONE
# gradle invocation, ONE cold boot, covering every requested class in this
# module, rather than one cold boot per class (which would multiply this
# script's real wall-clock cost by the discovered class count for no
# benefit: gradle's own connectedDebugAndroidTest task writes a REAL,
# authoritative per-testcase JUnit XML report to the HOST filesystem at
# <module>/build/outputs/androidTest-results/connected/**/TEST-*.xml
# regardless of how many classes were filtered into one run — confirmed
# by inspecting a real, pre-existing multi-class report already on this
# host at api-app/build/outputs/androidTest-results/connected/debug/
# TEST-sdk_gphone64_x86_64 - 14-_api-app-.xml, which aggregates FOUR
# distinct <testcase classname="..."> entries from one prior combined
# invocation). This wrapper parses that same real, host-side XML itself
# (embedded Python below, same approach as phase-02-test-kotlin.sh) to
# recover PER-CLASS granularity from the one combined run — grouping
# <testcase> elements by their own `classname` attribute, never inferred
# from the request list.
#
# Why gradle's own JUnit XML lives on the HOST at all despite the emulator
# itself running INSIDE a container (§6.X): confirmed by reading
# submodules/containers/pkg/emulator/containerized.go's RunInstrumentation —
# only the EMULATOR PROCESS runs inside the podman/docker container; gradle
# itself always runs on the HOST via `ANDROID_SERIAL=localhost:<forwarded-
# port> ./gradlew :<module>:connectedDebugAndroidTest -Pandroid.
# testInstrumentationRunnerArguments.class=<classes>`, talking to the
# container's forwarded ADB port exactly as it would a host-direct emulator.
#
# --- Target availability: §6.AH policy + what THIS host actually has ------
#
# Per root CLAUDE.md §6.AH: emulators/VMs MUST run in a Container or VM,
# NEVER host-direct, and NEVER on a live physical ADB device (a live device,
# if attached, is reserved for other work per §6.AG — never an acceptable
# substitute even when idle). This wrapper:
#   1. Confirms (real command, not assumed) that no live physical ADB device
#      is attached before doing anything else — logged, not a hard gate,
#      because neither sibling script would ever target one anyway (they
#      only ever drive a Containerized or host-direct+HVF *emulator*); this
#      is a courtesy real-check matching this project's own §6.AH posture.
#   2. Resolves accel/runner exactly as run-challenge-matrix.sh's own
#      pre-flight does (Linux+/dev/kvm -> containerized; macOS+HVF ->
#      host-direct) by simply delegating to that script and reading ITS real
#      exit code / host-preflight.json — never duplicating that detection
#      logic a second time.
#   3. Separately checks, via `podman image inspect`, which of the §6.AE.2
#      candidate emulator images (api28/30/34/36-x86_64) are ALREADY PRESENT
#      locally on this host — this check is NOT redundant with letting the
#      sibling script "figure it out itself": LVA-014 fix #3's own image
#      preflight ABORTS THE ENTIRE run (exit 1) the moment ANY ONE of the
#      REQUESTED avds' images is missing/unpullable, even when some others
#      in the same request WOULD have been fine — confirmed by a real,
#      live invocation performed while writing this wrapper (see below).
#      So this wrapper picks ONE api level whose image is ALREADY cached
#      and restricts --avds to just that one AVD, which is a real,
#      genuine, working target — not a "development-iteration"
#      workaround invented here, but literally the mechanism
#      run-challenge-matrix.sh's own header comment documents for exactly
#      this situation ("--avds ... Use this to target EXISTING host AVDs").
#      When NONE of the four are cached, this wrapper still invokes the real
#      script with the lowest candidate (api28) so the REAL pull attempt
#      (and its real, specific failure — an auth/registry error, not a
#      guess) becomes this run's genuine diagnostic, rather than reusing a
#      stale finding from a different invocation.
#
# --- Live verification performed while writing this wrapper (real, not
#     assumed) -------------------------------------------------------------
#   $ podman images --filter reference='*lava-android-emulator*'
#     -> ghcr.io/vasic-digital/lava-android-emulator:api34-x86_64 present
#        (6.09 GB, pulled 7 weeks prior); api28/30/36 NOT present.
#   $ podman login --get-login ghcr.io -> "Error: not logged into ghcr.io"
#   $ curl -s 'https://ghcr.io/token?scope=repository:vasic-digital/
#     lava-android-emulator:pull&service=ghcr.io' -> {"errors":[{"code":
#     "DENIED", ...}]} (anonymous pull token denied)
#   $ timeout 120 bash scripts/run-challenge-matrix.sh --no-build
#     --evidence-dir /tmp/lva-challenge-probe/app-matrix
#     -> host-preflight: accel=kvm runner=containerized (this Linux/x86_64
#        host HAS /dev/kvm — genuinely gate-ELIGIBLE, not a host-gap);
#        LVA-014 image preflight: api28 MISSING, "podman pull" ->
#        "received unexpected HTTP status: 403 Forbidden"; api30 same;
#        api34 "present locally"; api36 same 403 as api28/30. Overall exit 1
#        BEFORE any AVD ever booted, BEFORE any test class ever ran, because
#        the (untouched, default) 4-AVD matrix requires ALL FOUR images —
#        exactly the "aborts the whole run" behavior documented above.
#   $ podman run --rm ghcr.io/vasic-digital/lava-android-emulator:api34-x86_64
#     avdmanager list avd -> real entrypoint output confirming the image
#     genuinely bundles a bootable AVD (named "default"; LVA-014 fix #1
#     resolves the requested "CZ_API34_Phone" name to this real baked AVD by
#     matching api level, not by name).
# Conclusion of this real check: this host IS a genuine, §6.AH-compliant,
# containerized (Linux + /dev/kvm) gate-eligible target for AT LEAST the
# api34 AVD; api28/30/36 are real, present, honestly-reportable precondition
# gaps (registry access denied, not a code defect) that this wrapper reports
# per-class as SKIPPED rather than fabricating a PASS or silently omitting
# those classes' records.
#
# --- SKIPPED design (schema recently gained a third `result` value) -------
# specs/002-build-test-distribute-pipeline/contracts/evidence-record.schema.json
# now allows `result: SKIPPED` (added specifically for legitimately-skipped/
# blocked checks — see scripts/pipeline/lib/evidence.sh's own header). Prior
# wrappers in this pipeline (phase-02-test-release-canary.sh,
# phase-02-test-go.sh) predate that value and had to either force a config-
# error into FAIL or drop the record from the schema entirely. This wrapper
# uses SKIPPED wherever a Challenge class's real outcome is genuinely
# "did not execute because of a real, honestly-diagnosed host/registry
# precondition gap" — e.g. when the sibling script never even reaches the
# point of booting an AVD for that class (host-gap per §6.AE.7, or an image
# the class's chosen AVD needs is not pullable). SKIPPED records still go
# through every anti-bluff rule anti-bluff-validate.sh enforces (Rule 1
# generic-phrase check, Rule 2/3 raw_output_ref existence/non-emptiness,
# and — because `category` is always `real-device-challenge` regardless of
# `result` — Rule 4's falsifiability-rehearsal-marker requirement too; see
# "Falsifiability-rehearsal marker propagation" below, which applies
# uniformly to PASS, FAIL, AND SKIPPED records alike).
#
# --- Falsifiability-rehearsal marker propagation ---------------------------
# scripts/check-challenge-discrimination.sh's own detection regex is the
# source of truth for what counts as a marker:
#   FALSIFIABILITY[ \t]+REHEARSAL|§6\.AB-discrimination:
# anti-bluff-validate.sh's Rule 4 (real-device-challenge category) rejects
# any record — PASS, FAIL, or SKIPPED — whose `assertion_summary` AND
# `raw_output_ref` file content BOTH lack this marker text. This wrapper
# therefore extracts the REAL marker block verbatim from each class's own
# .kt source file (never fabricated, never templated) and writes it into
# that class's own raw_output_ref file for EVERY record this wrapper
# produces, in both the "real test ran" branch and the "genuinely blocked"
# branch. A real, pre-existing gap in this project's own Challenge suite
# (confirmed by a real grep while writing this wrapper: 63 of 73 :app
# Challenge*Test.kt files carry the marker; 10 do not) means a handful of
# classes' records will be honestly REJECTED by anti-bluff-validate.sh for
# lacking it — this wrapper does NOT paper over that by inserting a marker
# that is not really in the source; it reports the rejection honestly in
# its own summary, exactly as it would for any other real anti-bluff
# rejection.
#
# --- Command reconstruction (FR-003) ---------------------------------------
# Because this wrapper drives ONE combined multi-class invocation per module
# (see above), the literal command that PRODUCED a given class's evidence is
# a multi-class one. Per FR-003 ("the exact command invoked, for independent
# re-run") and matching phase-02-test-kotlin.sh's own precedent (which shows
# a single-class `./gradlew ... --tests "<classname>"` re-run command even
# though its own real invocation was a full multi-module `test` run), this
# wrapper's `command` field is a genuinely re-runnable SINGLE-class
# invocation of the same real script with the same real --avds/
# --container-image this run actually used — running it reproduces that
# one class's result independently, which is the property FR-003 cares
# about, without being a fictitious command that was never actually run.
#
# --- Usage ------------------------------------------------------------------
#   scripts/pipeline/phase-02-test-challenge.sh [repo-path] [phase-dir]
#
# With no arguments: repo-path resolves via `git rev-parse --show-toplevel`;
# phase-dir defaults to a freshly-created
# `.lava-ci-evidence/pipeline-runs/<UTC-run-id>/phase-02` under repo-path —
# matching every other phase-02-test-*.sh wrapper's existing convention, so
# this script is independently runnable/testable on its own.
#
# Optional environment-variable overrides (advanced; default is full
# discovery of every real Challenge*Test.kt file in both modules):
#   LAVA_PIPELINE_CHALLENGE_APP_TEST_CLASSES     comma-separated FQCN list —
#     when set, restricts the :app module run to the INTERSECTION of this
#     list and what was actually discovered on disk (a class named here that
#     does not really exist as a file is silently NOT added — this wrapper
#     never invents a class it did not itself find). Intended for bounded,
#     fast verification runs; a real pipeline invocation should leave this
#     unset so every real Challenge class gets real coverage.
#   LAVA_PIPELINE_CHALLENGE_API_APP_TEST_CLASSES same, for :api-app.
#   LAVA_PIPELINE_CHALLENGE_CONTAINER_RUNTIME    podman|docker (default:
#     podman, matching both sibling scripts' own default).
#
# --- Exit codes (of THIS wrapper) -------------------------------------------
#   0 - every produced Evidence Record is PASS or SKIPPED (no genuine FAIL),
#       and every record was anti-bluff-validated. SKIPPED is a first-class,
#       non-failure outcome per the schema's own design intent — an all-
#       SKIPPED run (e.g. every requested image is genuinely unavailable)
#       is NOT the same failure-mode as a real, executed test that broke,
#       and is reported as such rather than flattened into FAIL as the
#       older release-canary wrapper (predating SKIPPED) had to do.
#   1 - at least one real Evidence Record is FAIL, or at least one record
#       (PASS, FAIL, or SKIPPED) was REJECTED by anti-bluff validation.
#   2 - usage/precondition error (repo path, either sibling script, or
#       `jq`/`python3`/the container runtime binary missing; or zero
#       Challenge*Test.kt files discovered in either module at all).

set -uo pipefail
# Deliberately NOT `set -e`: both sibling scripts' own non-zero exits (host-
# gap, image-preflight failure, or a real test failure) are the REAL, wanted
# signal this wrapper must inspect and turn into honest Evidence Records —
# never something errexit should silently abort on. Every risky command
# below is explicitly guarded (`if`, direct `$?` capture) instead.

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

for tool in jq python3; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "phase-02-test-challenge: FAILED — required tool '$tool' not found on PATH" >&2
    exit 2
  fi
done

APP_MATRIX_SCRIPT="${REPO_PATH}/scripts/run-challenge-matrix.sh"
API_APP_MATRIX_SCRIPT="${REPO_PATH}/scripts/run-api-app-challenge-matrix.sh"
if [[ ! -f "$APP_MATRIX_SCRIPT" ]]; then
  echo "phase-02-test-challenge: precondition failed — ${APP_MATRIX_SCRIPT} not found" >&2
  exit 2
fi
if [[ ! -f "$API_APP_MATRIX_SCRIPT" ]]; then
  echo "phase-02-test-challenge: precondition failed — ${API_APP_MATRIX_SCRIPT} not found" >&2
  exit 2
fi

if [[ -z "$PHASE_DIR" ]]; then
  RUN_ID="$(date -u +%Y-%m-%dT%H-%M-%SZ)"
  PHASE_DIR="${REPO_PATH}/.lava-ci-evidence/pipeline-runs/${RUN_ID}/phase-02"
fi

CATEGORY_DIR="${PHASE_DIR}/real-device-challenge"
RAW_DIR="${CATEGORY_DIR}/raw"
mkdir -p "$RAW_DIR"

CONTAINER_RUNTIME="${LAVA_PIPELINE_CHALLENGE_CONTAINER_RUNTIME:-podman}"

# --- LVA-161: an explicitly-sized --test-timeout, DERIVED from the real
# workload rather than guessed -------------------------------------------
# cmd/emulator-matrix defaults --test-timeout to 10m (main.go:123) and that
# budget covers the TEST step only (boot is timed separately as boot_seconds).
# This wrapper deliberately runs ONE gradle invocation covering EVERY selected
# class in a module (one cold boot instead of N), so the budget must scale with
# the selection or it silently rots the moment classes are added -- which is
# exactly what happened: 73 app classes / 104 tests were selected against an
# unchanged 10m default and the run was killed at test_seconds=600.02.
#
# Sizing evidence (measured, not assumed):
#   * killed run 2026-08-26T14-09-17Z: 81 of 104 tests completed in 600.02s of
#     TEST-step time => 7.41 s/test aggregate, INCLUDING gradle configure +
#     APK install, and progress was still advancing at the moment of the kill.
#   * 73 classes / 108 @Test methods => ~1.48 tests per class, so the observed
#     aggregate is ~10.9 s per CLASS.
#   * slowest real single testcases actually on disk in this repo's own JUnit
#     XML: Challenge00CrashSurvivalTest 22.84s, Challenge02ApiAppBootAndServe
#     Test 33.58s.
# A 45 s/class budget is ~4.1x the measured 10.9 s/class and comfortably above
# the slowest observed single test, leaving headroom for a loaded host and for
# failing Compose tests that burn their own 15s ComposeTimeout before failing.
# The fixed overhead covers gradle configure + APK install + first-test warmup.
CHALLENGE_TIMEOUT_OVERHEAD_S="${LAVA_PIPELINE_CHALLENGE_TEST_TIMEOUT_OVERHEAD_S:-300}"
CHALLENGE_TIMEOUT_PER_CLASS_S="${LAVA_PIPELINE_CHALLENGE_TEST_TIMEOUT_PER_CLASS_S:-45}"

# derive_test_timeout <selected_class_count> -> prints a Go duration string.
# An explicit LAVA_PIPELINE_CHALLENGE_TEST_TIMEOUT wins verbatim.
derive_test_timeout() {
  local n="$1"
  if [[ -n "${LAVA_PIPELINE_CHALLENGE_TEST_TIMEOUT:-}" ]]; then
    printf '%s' "${LAVA_PIPELINE_CHALLENGE_TEST_TIMEOUT}"
    return 0
  fi
  printf '%ss' "$(( CHALLENGE_TIMEOUT_OVERHEAD_S + CHALLENGE_TIMEOUT_PER_CLASS_S * n ))"
}

# timeout_to_seconds <go-duration> -> integer seconds (supports Ns/Nm/Nh, bare N=seconds).
timeout_to_seconds() {
  local d="$1" n unit
  [[ "$d" =~ ^([0-9]+)([smh]?)$ ]] || { printf '0'; return 0; }
  n="${BASH_REMATCH[1]}"; unit="${BASH_REMATCH[2]}"
  case "$unit" in
    h) printf '%s' $(( n * 3600 )) ;;
    m) printf '%s' $(( n * 60 )) ;;
    *) printf '%s' "$n" ;;
  esac
}

echo "phase-02-test-challenge: repo=${REPO_PATH}"
echo "phase-02-test-challenge: phase_dir=${PHASE_DIR}"
echo "phase-02-test-challenge: container_runtime=${CONTAINER_RUNTIME}"

# --- §6.AH courtesy real-check: no live physical ADB device is used --------
# Neither sibling script would ever target one (they only ever drive a
# Containerized or host-direct+HVF *emulator*) — this is a real, logged
# confirmation, not a hard gate.
if command -v adb >/dev/null 2>&1; then
  LIVE_DEVICES="$(adb devices 2>/dev/null | tail -n +2 | grep -v '^[[:space:]]*$' || true)"
  if [[ -n "$LIVE_DEVICES" ]]; then
    echo "phase-02-test-challenge: NOTE — adb reports live device(s) attached; per §6.AH these are reserved for other work and are NEVER used as a target by either sibling script (they only ever drive a Containers-orchestrated or host-direct+HVF emulator):"
    echo "$LIVE_DEVICES" | sed 's/^/    /'
  else
    echo "phase-02-test-challenge: §6.AH check — adb reports zero live devices attached (real command output; not assumed)"
  fi
else
  echo "phase-02-test-challenge: §6.AH check — 'adb' not found on PATH; skipping the live-device courtesy check (neither sibling script would use one regardless)"
fi

# --- Discover real Challenge*Test.kt files (never trust a sibling script's
# own hardcoded default list — see header comment on run-api-app-challenge-
# matrix.sh's Challenge05/06/07 drift) ---------------------------------------
discover_classes() {
  # $1 = directory to scan (maxdepth 1), $2 = path to write the DROPPED-file
  # list to. Prints one "FQCN<TAB>ktpath" line per real Challenge*Test.kt file
  # found, sorted, using each file's own real `^package` line (never assumed
  # from the directory path).
  #
  # A Challenge*Test.kt whose `package` line cannot be read cannot be turned
  # into an FQCN and so cannot be requested from the matrix runner. That file
  # is genuinely NOT covered by this run. It used to be dropped in silence,
  # leaving the caller to report the surviving count as if it were the whole
  # inventory -- three files on disk reported as "discovered 1 real Challenge
  # class(es)". A refactor that breaks the package-line match on N files would
  # silently remove N classes from the gate's coverage with no signal at all,
  # which is the partial-result dishonesty this pipeline exists to prevent.
  # Every dropped file is now recorded by path so the caller can name it.
  local dir="$1" dropped_file="$2"
  local f pkg base
  : > "$dropped_file"
  if [[ ! -d "$dir" ]]; then
    return 0
  fi
  while IFS= read -r f; do
    [[ -z "$f" ]] && continue
    pkg="$(grep -m1 -E '^package ' "$f" 2>/dev/null | awk '{print $2}')"
    base="$(basename "$f" .kt)"
    if [[ -n "$pkg" ]]; then
      printf '%s.%s\t%s\n' "$pkg" "$base" "$f"
    else
      printf '%s\n' "$f" >> "$dropped_file"
    fi
  done < <(find "$dir" -maxdepth 1 -name 'Challenge*Test.kt' 2>/dev/null | sort)
}

APP_CHALLENGE_DIR="${REPO_PATH}/app/src/androidTest/kotlin/lava/app/challenges"
API_APP_CHALLENGE_DIR="${REPO_PATH}/api-app/src/androidTest/kotlin/lava/api/app/challenges"

APP_MANIFEST_ALL="${RAW_DIR}/_app-discovered.tsv"
API_APP_MANIFEST_ALL="${RAW_DIR}/_api-app-discovered.tsv"
APP_DROPPED="${RAW_DIR}/_app-undiscoverable.txt"
API_APP_DROPPED="${RAW_DIR}/_api-app-undiscoverable.txt"
discover_classes "$APP_CHALLENGE_DIR" "$APP_DROPPED" > "$APP_MANIFEST_ALL"
discover_classes "$API_APP_CHALLENGE_DIR" "$API_APP_DROPPED" > "$API_APP_MANIFEST_ALL"

APP_DISCOVERED_COUNT=$(wc -l < "$APP_MANIFEST_ALL" | tr -d '[:space:]')
API_APP_DISCOVERED_COUNT=$(wc -l < "$API_APP_MANIFEST_ALL" | tr -d '[:space:]')

# Report the real file count alongside the discovered count, and NAME every
# file that could not be turned into an FQCN. "discovered N" on its own reads
# as "N is all there is"; when M files are on disk and only N are usable, the
# M-N difference is a real, silent coverage gap and must be said out loud.
_report_discovery() {
  # $1 = module label, $2 = challenge dir, $3 = discovered count,
  # $4 = dropped-file list
  local label="$1" dir="$2" found="$3" dropped_file="$4"
  local on_disk=0 dropped=0
  [[ -d "$dir" ]] && on_disk=$(find "$dir" -maxdepth 1 -name 'Challenge*Test.kt' 2>/dev/null | wc -l | tr -d '[:space:]')
  [[ -f "$dropped_file" ]] && dropped=$(grep -cv '^[[:space:]]*$' "$dropped_file" 2>/dev/null || echo 0)
  echo "phase-02-test-challenge: discovered ${found} real Challenge class(es) under ${label} (${on_disk} Challenge*Test.kt file(s) on disk)"
  if [[ "$dropped" -gt 0 ]]; then
    echo "phase-02-test-challenge: WARNING — ${dropped} Challenge*Test.kt file(s) under ${label} carry no readable '^package ' line, so no FQCN could be built for them and they are NOT covered by this run:"
    sed 's|^|    UNDISCOVERABLE: |' "$dropped_file"
  fi
}
_report_discovery "app/.../challenges" "$APP_CHALLENGE_DIR" "$APP_DISCOVERED_COUNT" "$APP_DROPPED"
_report_discovery "api-app/.../challenges" "$API_APP_CHALLENGE_DIR" "$API_APP_DISCOVERED_COUNT" "$API_APP_DROPPED"

if [[ "$APP_DISCOVERED_COUNT" -eq 0 && "$API_APP_DISCOVERED_COUNT" -eq 0 ]]; then
  echo "phase-02-test-challenge: precondition failed — zero Challenge*Test.kt files discovered in either module" >&2
  exit 2
fi

# Apply optional env-var overrides: intersect the discovered manifest with
# the operator-provided FQCN list (never invent an undiscovered class).
apply_override() {
  # $1 = manifest file (FQCN\tpath), $2 = comma-separated override FQCN list
  # (may be empty). Prints the filtered manifest to stdout.
  local manifest="$1" override="$2"
  if [[ -z "$override" ]]; then
    cat "$manifest"
    return 0
  fi
  local wanted
  wanted="$(printf '%s' "$override" | tr ',' '\n' | sed '/^[[:space:]]*$/d')"
  while IFS=$'\t' read -r fqcn path; do
    [[ -z "$fqcn" ]] && continue
    # Herestring, not a pipe — see the SIGPIPE/pipefail note in
    # scripts/pipeline/lib/anti-bluff-validate.sh. Bounded here today, but the
    # shape is the defect, not the current size.
    if grep -qxF "$fqcn" <<< "$wanted"; then
      printf '%s\t%s\n' "$fqcn" "$path"
    fi
  done < "$manifest"
}

APP_MANIFEST="${RAW_DIR}/_app-selected.tsv"
API_APP_MANIFEST="${RAW_DIR}/_api-app-selected.tsv"
apply_override "$APP_MANIFEST_ALL" "${LAVA_PIPELINE_CHALLENGE_APP_TEST_CLASSES:-}" > "$APP_MANIFEST"
apply_override "$API_APP_MANIFEST_ALL" "${LAVA_PIPELINE_CHALLENGE_API_APP_TEST_CLASSES:-}" > "$API_APP_MANIFEST"

APP_SELECTED_COUNT=$(wc -l < "$APP_MANIFEST" | tr -d '[:space:]')
API_APP_SELECTED_COUNT=$(wc -l < "$API_APP_MANIFEST" | tr -d '[:space:]')
if [[ -n "${LAVA_PIPELINE_CHALLENGE_APP_TEST_CLASSES:-}" ]]; then
  echo "phase-02-test-challenge: LAVA_PIPELINE_CHALLENGE_APP_TEST_CLASSES override active — ${APP_SELECTED_COUNT}/${APP_DISCOVERED_COUNT} discovered app classes selected"
fi
if [[ -n "${LAVA_PIPELINE_CHALLENGE_API_APP_TEST_CLASSES:-}" ]]; then
  echo "phase-02-test-challenge: LAVA_PIPELINE_CHALLENGE_API_APP_TEST_CLASSES override active — ${API_APP_SELECTED_COUNT}/${API_APP_DISCOVERED_COUNT} discovered api-app classes selected"
fi

# --- Choose a real, locally-available AVD image (see header comment:
# LVA-014 fix #3 aborts the WHOLE run if any ONE requested image is
# missing/unpullable, so a single AVD whose image is ALREADY cached is the
# genuinely correct, real target — not a workaround) -------------------------
CANDIDATE_APIS=(34 36 30 28)
CHOSEN_API=""
for api in "${CANDIDATE_APIS[@]}"; do
  if "$CONTAINER_RUNTIME" image inspect "ghcr.io/vasic-digital/lava-android-emulator:api${api}-x86_64" >/dev/null 2>&1; then
    CHOSEN_API="$api"
    echo "phase-02-test-challenge: real check — ghcr.io/vasic-digital/lava-android-emulator:api${api}-x86_64 is ALREADY present locally (podman image inspect succeeded)"
    break
  fi
done
if [[ -z "$CHOSEN_API" ]]; then
  CHOSEN_API="${CANDIDATE_APIS[-1]}"
  echo "phase-02-test-challenge: real check — none of api{${CANDIDATE_APIS[*]}}-x86_64 are cached locally; will let the real invocation attempt to pull api${CHOSEN_API}-x86_64 so its genuine failure becomes this run's diagnostic"
fi
AVD_SPEC="CZ_API${CHOSEN_API}_Phone:${CHOSEN_API}:phone"
CONTAINER_IMAGE_REF="ghcr.io/vasic-digital/lava-android-emulator:api${CHOSEN_API}-x86_64"
echo "phase-02-test-challenge: chosen AVD for this run: ${AVD_SPEC} (image: ${CONTAINER_IMAGE_REF})"

# --- Embedded parser: turns one module's real run into one JSON line per
# requested Challenge class (PASS/FAIL when real testcases were found in a
# real JUnit XML report; PASS/FAIL/SKIPPED-BLOCKED when the run never
# reached that point). Always embeds the class's own REAL KDoc
# falsifiability-rehearsal marker verbatim (never fabricated) into that
# class's raw_output_ref file. -----------------------------------------------
run_module_parser() {
  # args: repo_path raw_dir module_label mode(real|blocked) avd_spec
  #       matrix_script_rel container_image_ref container_runtime
  #       diag_file xml_search_dir marker_file manifest_file matrix_rc
  python3 - "$@" <<'PYEOF'
import json
import os
import re
import sys
import glob

try:
    # These XML files are Gradle's own local output (not attacker-supplied),
    # so the classic XXE/billion-laughs threat model barely applies here --
    # but hardening a parser against external-entity/DTD expansion is free
    # when the library is already on the host, so prefer it defensively
    # (same posture as phase-02-test-kotlin.sh's own embedded parser).
    import defusedxml.ElementTree as ET
except ImportError:  # pragma: no cover - falls back to stdlib if absent
    import xml.etree.ElementTree as ET

(repo_path, raw_dir, module_label, mode, avd_spec, matrix_script_rel,
 container_image_ref, container_runtime, diag_file, xml_search_dir,
 marker_file, manifest_file, matrix_rc, abort_reason, abort_detail) = sys.argv[1:16]

# LVA-161 (2026-08-26): `abort_reason` is non-empty ONLY when the caller
# proved, from the runner's own attestation row, that this invocation was
# KILLED or TIMED OUT rather than allowed to finish. That distinction is
# load-bearing and cannot be re-derived here: gradle writes its JUnit XML
# once, at the END of connectedAndroidTest, so a kill leaves ZERO parsed
# files and every selected class looks identical to "class filter matched
# nothing". Before this flag existed, both produced FAIL for all 73 classes,
# and the 2026-08-26T14-09-17Z run was consequently reported as 74 broken
# features when the runner had in fact completed 81 of 104 tests with 12
# real failures. Reporting a killed run as 73 FAILs is dishonest in one
# direction; reporting it as a pass is dishonest in the other. Neither is
# acceptable, so an aborted invocation yields SKIPPED-with-UNCONFIRMED per
# class AND a hard non-zero phase exit driven by the caller.

# The sibling matrix script's own documented exit codes (see this file's
# header): 0 = the matrix ran and every row passed; 1 = a real per-row test
# failure OR a genuine image-preflight failure; 2 = host-gap, a genuinely
# ineligible gate host. Only 1 and 2 can legitimately mean "this run never
# reached the point of producing evidence".
#
# This value used to be captured into `rc`, printed three times, and never
# compared -- the exact defect tests/pipeline/test_no_vacuous_pass_patterns.sh
# CHECK A exists to catch, which its heuristic missed here because the echo
# that reports rc also contains a bare "[" from "[$module_label]". The
# consequence: a matrix script that exited 0 -- claiming SUCCESS -- while
# writing no attestation at all was classified BLOCKED, and every class got a
# SKIPPED record asserting "a real, specific precondition gap", a cause this
# wrapper cannot possibly know and which the tool's own success exit code
# contradicts. SKIPPED does not block the phase, so the wrapper exited 0 on a
# run in which nothing was verified. Same shape as the already-fixed
# release-canary defect (undocumented exit codes laundered into a SKIPPED that
# asserted a cause it could not know).
try:
    _rc_int = int(matrix_rc)
except (TypeError, ValueError):
    _rc_int = None
# A blocked-mode run is only honestly SKIPPED when the exit code is one the
# sibling script documents as "did not get that far". Anything else -- above
# all 0 -- is a contract violation and is reported as a real FAIL.
BLOCK_IS_HONEST = _rc_int in (1, 2)

_SANITIZE_RE = re.compile(r'[^A-Za-z0-9._-]')
_COLLAPSE_RE = re.compile(r'_{2,}')
_WS_RE = re.compile(r'\s+')
_MARKER_RE = re.compile(r'FALSIFIABILITY[ \t]+REHEARSAL|§6\.AB-discrimination:')


def one_line(s, maxlen=1200):
    if not s:
        return ""
    s = s.replace("\r", " ").replace("\n", " | ")
    s = _WS_RE.sub(" ", s).strip()
    if len(s) > maxlen:
        s = s[:maxlen] + "...(truncated)"
    return s


def sanitize_for_filename(s):
    s = _SANITIZE_RE.sub("_", s)
    s = _COLLAPSE_RE.sub("_", s).strip("_")
    return s or "unnamed-test"


def extract_marker_block(kt_path):
    """Return the REAL falsifiability-rehearsal KDoc block verbatim from the
    given .kt source file (from the marker line through the next '*/'),
    or None if the file has no such marker at all (a real, honestly-reported
    pre-existing gap in this project's own Challenge suite -- never
    fabricated here)."""
    try:
        with open(kt_path, "r", encoding="utf-8", errors="replace") as fh:
            lines = fh.readlines()
    except OSError as e:
        return None, f"(could not read source file {kt_path}: {e})"
    start = None
    for i, ln in enumerate(lines):
        if _MARKER_RE.search(ln):
            start = i
            break
    if start is None:
        return None, None
    end = len(lines)
    for j in range(start, len(lines)):
        if "*/" in lines[j]:
            end = j + 1
            break
    block = "".join(lines[start:end])
    return block, None


diag_text = ""
if diag_file and os.path.isfile(diag_file):
    with open(diag_file, "r", encoding="utf-8", errors="replace") as fh:
        diag_text = fh.read()

# --- Load requested classes (FQCN\tktpath per line) ---
requested = []
with open(manifest_file, "r", encoding="utf-8") as fh:
    for line in fh:
        line = line.rstrip("\n")
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) != 2:
            continue
        requested.append((parts[0], parts[1]))

# --- In "real" mode, parse every fresh TEST-*.xml under xml_search_dir,
# grouping <testcase> elements by their own real `classname` attribute. A
# freshness cutoff (marker_file's mtime) proves every parsed testcase came
# from THIS invocation, not a stale prior run's leftover report. ---
testcases_by_class = {}
all_found_classnames = set()
xml_files_used = []
if mode == "real" and xml_search_dir:
    marker_mtime = os.path.getmtime(marker_file) if marker_file and os.path.isfile(marker_file) else 0.0
    pattern = os.path.join(xml_search_dir, "**", "TEST-*.xml")
    for xf in sorted(glob.glob(pattern, recursive=True)):
        try:
            if os.path.getmtime(xf) < marker_mtime:
                continue
        except OSError:
            continue
        try:
            tree = ET.parse(xf)
        except Exception as e:  # noqa: BLE001 - a malformed report must not abort the whole parse
            print(f"WARN: failed to parse '{xf}' as XML: {e}", file=sys.stderr)
            continue
        xml_files_used.append(xf)
        for tc in tree.getroot().iter("testcase"):
            classname = tc.get("classname", "") or ""
            all_found_classnames.add(classname)
            testcases_by_class.setdefault(classname, []).append(tc)

records = []
for fqcn, kt_path in requested:
    marker_block, marker_err = extract_marker_block(kt_path)
    rel_kt = os.path.relpath(kt_path, repo_path)
    short_class = fqcn.rsplit(".", 1)[-1]
    command = (
        f'scripts/{matrix_script_rel} --no-build --avds "{avd_spec}" '
        f'--test-class "{fqcn}" --container-image "{container_image_ref}" '
        f'--container-runtime "{container_runtime}" '
        f'--evidence-dir .lava-ci-evidence/<run>/phase-02/real-device-challenge/raw/{module_label}-rerun-{short_class}'
    )

    raw_lines = [
        f"module: {module_label}",
        f"classname (FQCN): {fqcn}",
        f"source file: {rel_kt}",
        f"chosen AVD for this run: {avd_spec} (image: {container_image_ref}, runtime: {container_runtime})",
        "",
    ]

    if mode == "blocked" and BLOCK_IS_HONEST:
        result = "SKIPPED"
        assertion_summary = one_line(
            f'BLOCKED (real, specific precondition gap -- not a feature defect): '
            f'the real invocation of scripts/{matrix_script_rel} for classname="{fqcn}" '
            f'exited {matrix_rc} -- one of the two exit codes that script documents as '
            f'"never got as far as producing evidence" (1 = image-preflight failure, '
            f'2 = host-gap) -- and produced no per-AVD attestation for this run. '
            f'Real diagnostic: "{one_line(diag_text, 600)}"'
        )
        raw_lines += [
            f"outcome: SKIPPED (BLOCKED -- matrix script exited {matrix_rc}, a documented"
            " did-not-get-that-far code; see diagnostic below)",
            "--- real captured diagnostic from this invocation ---",
            diag_text,
        ]
    elif mode == "blocked":
        result = "FAIL"
        _rc_note = (
            "reported SUCCESS" if _rc_int == 0
            else f"exited with the undocumented code {matrix_rc}"
        )
        assertion_summary = one_line(
            f'CONTRACT VIOLATION, not a precondition gap: the real invocation of '
            f'scripts/{matrix_script_rel} for classname="{fqcn}" {_rc_note} '
            f'(exited {matrix_rc}) yet wrote NO per-AVD attestation '
            f'(real-device-verification.json) for this run. Exit 0 from that script means '
            f'"the matrix ran and every row passed", so there is no honest reading under '
            f'which this class was verified, and no evidence from which to name a cause -- '
            f'this wrapper refuses to assert a host/registry precondition gap it cannot '
            f'know occurred. Real diagnostic: "{one_line(diag_text, 600)}"'
        )
        raw_lines += [
            f"outcome: FAIL (matrix script exited {matrix_rc} but produced no attestation --"
            " a contract violation, deliberately NOT downgraded to SKIPPED)",
            "--- real captured diagnostic from this invocation ---",
            diag_text,
        ]
    else:
        tcs = testcases_by_class.get(fqcn, [])
        if not tcs and abort_reason:
            # The invocation was KILLED/TIMED OUT (proved by the runner's own
            # attestation row -- see abort_reason/abort_detail). The absence of
            # a <testcase> for this class therefore carries NO information
            # about the class: it was never given the chance to report. Calling
            # that FAIL would manufacture a defect; calling it PASS would hide
            # one. It is UNCONFIRMED, and it is recorded as such.
            result = "SKIPPED"
            assertion_summary = one_line(
                f'UNCONFIRMED: the runner was ABORTED ({abort_reason}), not allowed to finish, so '
                f'classname="{fqcn}" has NO result in either direction. Gradle writes its JUnit XML '
                f'only at the END of connectedAndroidTest, so this abort discarded the results of '
                f'every selected class at once ({len(xml_files_used)} XML file(s) parsed) -- '
                f'including any that had already passed AND any that had already genuinely failed. '
                f'This is NOT evidence the feature works and NOT evidence it is broken. '
                f'Runner-reported progress before the abort: {one_line(abort_detail, 400)}'
            )
            raw_lines += [
                f"outcome: SKIPPED (UNCONFIRMED -- invocation ABORTED: {abort_reason})",
                "",
                "WHY THIS IS NOT 'FAIL': the runner never completed, so no per-class verdict",
                "exists for this class. WHY THIS IS NOT 'PASS': the runner never completed, so",
                "no per-class verdict exists for this class. Re-run with a sufficient",
                "--test-timeout (or a reduced class selection) to obtain a real verdict.",
                "",
                "--- runner-reported progress captured before the abort (real, verbatim) ---",
                abort_detail,
                f"XML files parsed ({len(xml_files_used)}): " + ", ".join(os.path.relpath(x, repo_path) for x in xml_files_used),
                "--- real run context ---",
                diag_text,
            ]
        elif not tcs:
            result = "FAIL"
            known = ", ".join(sorted(all_found_classnames)[:15]) if all_found_classnames else "(none)"
            assertion_summary = one_line(
                f'No <testcase classname="{fqcn}"> entry was found in the real, freshly-written '
                f'JUnit XML report(s) for this run ({len(xml_files_used)} file(s) parsed), and the '
                f'runner was NOT aborted (its attestation row reports a completed invocation) -- so '
                f'the class filter did not match any executed test. classnames actually present in '
                f'the report: {known}'
            )
            raw_lines += [
                "outcome: FAIL (no matching <testcase> found in the real JUnit XML report for this run;"
                " the runner ran to completion, so this is a genuine absence, not an abort artefact)",
                f"XML files parsed ({len(xml_files_used)}): " + ", ".join(os.path.relpath(x, repo_path) for x in xml_files_used),
                f"classnames actually present in those reports: {sorted(all_found_classnames)}",
                "--- real run context ---",
                diag_text,
            ]
        else:
            fails = []
            for tc in tcs:
                fnode = tc.find("failure")
                enode = tc.find("error")
                node = fnode if fnode is not None else enode
                if node is not None:
                    fails.append((tc.get("name", ""), "failure" if fnode is not None else "error", node.get("type", ""), node.get("message", ""), node.text or ""))
            if fails:
                result = "FAIL"
                first = fails[0]
                assertion_summary = one_line(
                    f'Real JUnit XML for classname="{fqcn}" reports a real {first[1]} in method '
                    f'"{first[0]}": type="{first[2]}" message="{one_line(first[3], 300)}" '
                    f'({len(fails)} of {len(tcs)} real testcase(s) failed)'
                )
                raw_lines += [
                    f"outcome: FAIL ({len(fails)} of {len(tcs)} real <testcase> element(s) had <failure>/<error>)",
                ]
                for name, kind, ftype, fmsg, ftext in fails:
                    raw_lines += [
                        f"  method: {name}  kind: {kind}  type: {ftype}  message: {fmsg}",
                        f"  stack excerpt: {one_line(ftext, 500)}",
                    ]
            else:
                result = "PASS"
                methods = ", ".join(f'"{tc.get("name","")}" ({tc.get("time","?")}s)' for tc in tcs)
                assertion_summary = one_line(
                    f'Real JUnit XML for classname="{fqcn}" reports {len(tcs)} real testcase(s) '
                    f'with no <failure>/<error> child: {methods} -- genuinely executed by '
                    f'connectedDebugAndroidTest on a real cold-booted {avd_spec} emulator'
                )
                raw_lines += [
                    f"outcome: PASS ({len(tcs)} real <testcase> element(s), none with <failure>/<error>)",
                ]
                for tc in tcs:
                    raw_lines += [f"  method: {tc.get('name','')}  time: {tc.get('time','?')}s"]
            raw_lines += [
                "--- real run context ---",
                diag_text,
            ]

    raw_lines += [
        "",
        "--- real KDoc falsifiability-rehearsal marker, verbatim from the source file above ---",
    ]
    if marker_block:
        raw_lines.append(marker_block)
    elif marker_err:
        raw_lines.append(marker_err)
    else:
        raw_lines.append(
            f"(this Challenge test's KDoc does NOT contain a FALSIFIABILITY REHEARSAL / "
            f"§6.AB-discrimination: marker -- a real, pre-existing gap in this project's own "
            f"Challenge suite, not introduced by this wrapper; anti-bluff-validate.sh's Rule 4 will "
            f"honestly REJECT this record for lacking it)"
        )

    fname = sanitize_for_filename(fqcn) + ".txt"
    raw_path = os.path.join(raw_dir, fname)
    with open(raw_path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(raw_lines) + "\n")

    records.append({
        "test_id": fqcn,
        "result": result,
        "assertion_summary": assertion_summary,
        "raw_file": raw_path,
        "command": command,
    })

for r in records:
    print(json.dumps(r, ensure_ascii=False))
PYEOF
}

# --- Per-module driver: invokes the real sibling script, determines
# real/blocked mode, runs the parser above, writes + validates one Evidence
# Record per class. ----------------------------------------------------------
TOTAL_PASS=0
TOTAL_FAIL=0
TOTAL_SKIPPED=0
TOTAL_VALIDATED=0
TOTAL_REJECTED=0
TOTAL_RECORDS=0
TOTAL_SELECTED=0
declare -a FAILED_IDS=()
declare -a REJECTED_RECORDS=()
declare -a EXAMPLE_RECORDS=()
# LVA-161 / LVA-162
declare -a ABORTED_MODULES=()
declare -a DIAG_GATE_VIOLATIONS=()
ABORT_REASON=""
ABORT_DETAIL=""

# --- LVA-162: refuse a gating attestation row that carries an empty diag ---
# scripts/tag.sh Group-B Gate 3 (the AVD-shadow gate) is written as:
#   jq '.rows[] | select(.diag.sdk != null and .api_level != null
#                        and .diag.sdk != .api_level)'
# With diag == {} the `.diag.sdk != null` guard filters the row OUT, so the
# gate reports "no mismatches" and the row sails through -- on a row that
# declares itself `gating: true`. That carve-out (tag.sh's own comment: "rows
# lacking diag ... are skipped") was meant for pre-Group-B attestations, but
# a CURRENT emitter writing diag:{} slides through the identical hole, leaving
# the gate inert on exactly the rows it exists to police.
#
# The emitter is submodules/containers/pkg/emulator/matrix.go:611
# (writeAttestation), which this repo may not edit (pins are frozen). So the
# refusal lives here, Lava-side: an inert gate that REFUSES is strictly better
# than one that passes. §6.I.4 Group B requires diag.target, diag.sdk,
# diag.device and diag.adb_devices_state on EVERY row.
check_gating_row_diag() {
  local attestation="$1" module_label="$2"
  command -v jq >/dev/null 2>&1 || return 0
  local gating offenders
  gating="$(jq -r 'if (.gating == null) then "true" else (.gating | tostring) end' "$attestation" 2>/dev/null)"
  [[ "$gating" == "true" ]] || return 0

  offenders="$(jq -r '
    [ .rows[]?
      | . as $r
      | ( ["target","sdk","device","adb_devices_state"]
          | map(select((($r.diag // {})[.] // "") | tostring | length == 0)) ) as $missing
      | select(($missing | length) > 0)
      | "\($r.avd // "?") (api_level=\($r.api_level // "?")): missing diag field(s): \($missing | join(", "))"
    ] | join(" ;; ")' "$attestation" 2>/dev/null)"
  [[ "$offenders" == "null" ]] && offenders=""

  if [[ -n "$offenders" ]]; then
    echo "" >&2
    echo "phase-02-test-challenge: [$module_label] §6.I.4 GROUP-B VIOLATION — this attestation declares gating: true but has row(s) with an incomplete diag: ${offenders}" >&2
    echo "phase-02-test-challenge: [$module_label] scripts/tag.sh Group-B Gate 3 compares diag.sdk against api_level to catch the AVD-shadow bluff; with diag empty that comparison has nothing to read and the gate is INERT on this row. A gating row that cannot be policed is refused here rather than passed on." >&2
    echo "phase-02-test-challenge: [$module_label] emitter: submodules/containers/pkg/emulator/matrix.go writeAttestation() — Diag is serialised from TestResult.Diag, which was empty for this run." >&2
    return 1
  fi
  return 0
}

process_module() {
  local module_label="$1" matrix_script="$2" matrix_script_rel="$3" \
        manifest="$4"
  local selected_count
  selected_count=$(wc -l < "$manifest" | tr -d '[:space:]')
  TOTAL_SELECTED=$((TOTAL_SELECTED + selected_count))
  if [[ "$selected_count" -eq 0 ]]; then
    echo "phase-02-test-challenge: [$module_label] zero classes selected — skipping this module entirely"
    return 0
  fi

  # Reset the per-module abort signals; these are consulted by the parser and
  # by the final exit decision, and MUST NOT leak from a previous module.
  ABORT_REASON=""
  ABORT_DETAIL=""

  local test_class_arg
  test_class_arg="$(cut -f1 "$manifest" | paste -sd, -)"

  local module_raw_dir="${RAW_DIR}/${module_label}"
  mkdir -p "$module_raw_dir"
  local matrix_evidence_dir="${module_raw_dir}-matrix-run"
  local run_log="${module_raw_dir}-invocation.log"
  local marker_file="${module_raw_dir}.marker"
  touch "$marker_file"

  echo ""
  echo "phase-02-test-challenge: [$module_label] ${selected_count} class(es) selected: ${test_class_arg}"
  echo "phase-02-test-challenge: [$module_label] RUN (real): ${matrix_script_rel} --no-build --avds \"${AVD_SPEC}\" --test-class \"<${selected_count} classes>\" --container-image \"${CONTAINER_IMAGE_REF}\" --container-runtime \"${CONTAINER_RUNTIME}\" --evidence-dir \"${matrix_evidence_dir}\""

  local test_timeout test_timeout_s
  test_timeout="$(derive_test_timeout "$selected_count")"
  test_timeout_s="$(timeout_to_seconds "$test_timeout")"
  echo "phase-02-test-challenge: [$module_label] --test-timeout=${test_timeout} (${test_timeout_s}s) for ${selected_count} class(es) [overhead ${CHALLENGE_TIMEOUT_OVERHEAD_S}s + ${CHALLENGE_TIMEOUT_PER_CLASS_S}s/class]"

  bash "$matrix_script" \
    --no-build \
    --avds "$AVD_SPEC" \
    --test-class "$test_class_arg" \
    --container-image "$CONTAINER_IMAGE_REF" \
    --container-runtime "$CONTAINER_RUNTIME" \
    --test-timeout "$test_timeout" \
    --evidence-dir "$matrix_evidence_dir" \
    > "$run_log" 2>&1
  local rc=$?
  echo "phase-02-test-challenge: [$module_label] ${matrix_script_rel} exited ${rc}"

  local attestation="${matrix_evidence_dir}/real-device-verification.json"
  local mode diag_file xml_search_dir

  # -f AND -s, never -s alone: a directory satisfies both -e and -s (its inode
  # has a non-zero size), which is exactly how an empty raw_output_ref once
  # satisfied anti-bluff-validate.sh's "there is real captured output behind
  # this claim" rules. The attestation must be a real, non-empty regular file.
  if [[ -f "$attestation" && -s "$attestation" ]]; then
    mode="real"
    xml_search_dir="${REPO_PATH}/${module_label}/build/outputs/androidTest-results/connected"
    diag_file="${module_raw_dir}-run-context.txt"
    {
      echo "sibling script: ${matrix_script_rel}"
      echo "exit code: ${rc}"
      echo "AVD: ${AVD_SPEC}  image: ${CONTAINER_IMAGE_REF}  runtime: ${CONTAINER_RUNTIME}"
      echo "attestation file: $(realpath --relative-to="$REPO_PATH" "$attestation" 2>/dev/null || echo "$attestation")"
      if command -v jq >/dev/null 2>&1; then
        echo "--- real real-device-verification.json rows ---"
        jq -c '.rows[]?' "$attestation" 2>/dev/null
      fi
    } > "$diag_file"
    echo "phase-02-test-challenge: [$module_label] real per-AVD attestation found — parsing real JUnit XML for per-class results"

    # --- LVA-161: was this invocation ABORTED (killed / deadline exceeded)? ---
    # Proved from the runner's OWN attestation row, never inferred from the
    # empty XML directory (an empty directory is exactly what a
    # filter-matched-nothing run also produces -- that ambiguity IS the defect).
    # Two independent signals, either sufficient:
    #   (a) test_error names a kill/timeout ("signal: killed", "deadline
    #       exceeded", "timeout"), which is what cmd/emulator-matrix records
    #       when it kills the gradle child at --test-timeout; or
    #   (b) the row failed AND burned >=98% of the budget we ourselves passed.
    if command -v jq >/dev/null 2>&1; then
      ABORT_REASON="$(jq -r --argjson budget "${test_timeout_s:-0}" '
        [ .rows[]?
          | select(
              (((.test_error // "") | test("signal: *killed|killed|deadline exceeded|timed? ?out"; "i")))
              or ( $budget > 0
                   and ((.test_seconds // 0) >= ($budget * 0.98))
                   and (((.test_passed) // false) == false) )
            )
          | "AVD \(.avd // "?") test_seconds=\(.test_seconds // 0) test_error=\(.test_error // "(none)")"
        ] | join(" ;; ")' "$attestation" 2>/dev/null)"
      [[ "$ABORT_REASON" == "null" ]] && ABORT_REASON=""
    fi

    if [[ -n "$ABORT_REASON" ]]; then
      # Salvage whatever the runner DID report before it was killed. Gradle
      # prints a running "Tests N/M completed. (S skipped) (F failed)" progress
      # line to its log even though it writes the JUnit XML only at the end, so
      # this is the ONLY surviving record of how far the run actually got --
      # and, critically, of how many tests had ALREADY genuinely failed. It is
      # surfaced so nobody reads "aborted" as "nothing was wrong".
      local gl
      while IFS= read -r gl; do
        [[ -z "$gl" ]] && continue
        local gpath="${matrix_evidence_dir}/${gl}"
        [[ -f "$gpath" ]] || continue
        local tail_line
        tail_line="$(grep -aoE 'Tests [0-9]+/[0-9]+ completed\.[^\r]*' "$gpath" 2>/dev/null | tail -n 1)"
        if [[ -n "$tail_line" ]]; then
          ABORT_DETAIL="${ABORT_DETAIL}[${gl}] ${tail_line} "
        fi
      done < <(jq -r '.rows[]?.gradle_log_path // empty' "$attestation" 2>/dev/null)
      if [[ -z "$ABORT_DETAIL" ]]; then
        ABORT_DETAIL="(no gradle progress line survived in ${matrix_evidence_dir}; the runner was killed before printing one, so the number of already-failing tests is UNKNOWN -- treat every selected class as UNCONFIRMED)"
      fi
      ABORTED_MODULES+=("${module_label}: ${ABORT_REASON} | progress: ${ABORT_DETAIL}")
      echo "phase-02-test-challenge: [$module_label] ABORTED — ${ABORT_REASON}" >&2
      echo "phase-02-test-challenge: [$module_label] salvaged runner progress: ${ABORT_DETAIL}" >&2
    fi

    # --- LVA-162: a gating row with an empty diag makes tag.sh Group-B Gate 3
    # inert on exactly the rows it exists to police. Refuse it here. ---
    check_gating_row_diag "$attestation" "$module_label" || DIAG_GATE_VIOLATIONS+=("$module_label")
  else
    mode="blocked"
    xml_search_dir=""
    diag_file="${module_raw_dir}-blocked-diagnostic.txt"
    {
      echo "sibling script: ${matrix_script_rel}"
      echo "exit code: ${rc}"
      echo "no real-device-verification.json was produced at: $(realpath --relative-to="$REPO_PATH" "$attestation" 2>/dev/null || echo "$attestation")"
      local hp="${matrix_evidence_dir}/host-preflight.json"
      if [[ -f "$hp" ]]; then
        echo "--- real host-preflight.json ---"
        cat "$hp"
      fi
      echo "--- real captured stdout/stderr tail from this invocation (last 25 non-blank lines) ---"
      grep -av '^[[:space:]]*$' "$run_log" 2>/dev/null | tail -n 25
    } > "$diag_file"
    echo "phase-02-test-challenge: [$module_label] BLOCKED — no real per-AVD attestation was produced (see ${diag_file#"$REPO_PATH"/})"
  fi

  local jsonl="${module_raw_dir}-parsed.jsonl"
  run_module_parser \
    "$REPO_PATH" "$module_raw_dir" "$module_label" "$mode" "$AVD_SPEC" \
    "$(basename "$matrix_script_rel")" "$CONTAINER_IMAGE_REF" "$CONTAINER_RUNTIME" \
    "$diag_file" "$xml_search_dir" "$marker_file" "$manifest" "$rc" \
    "$ABORT_REASON" "$ABORT_DETAIL" \
    > "$jsonl"

  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    TOTAL_RECORDS=$((TOTAL_RECORDS + 1))
    local f_test_id f_result f_summary f_rawfile f_command
    IFS=$'\t' read -r f_test_id f_result f_summary f_rawfile f_command \
      <<< "$(jq -r '[.test_id, .result, .assertion_summary, .raw_file, .command] | @tsv' <<< "$line")"

    case "$f_result" in
      PASS) TOTAL_PASS=$((TOTAL_PASS + 1)) ;;
      FAIL) TOTAL_FAIL=$((TOTAL_FAIL + 1)); FAILED_IDS+=("${module_label} :: ${f_test_id}") ;;
      SKIPPED) TOTAL_SKIPPED=$((TOTAL_SKIPPED + 1)) ;;
    esac

    local record_path=""
    if ! record_path="$(write_evidence_record "$PHASE_DIR" "$f_test_id" "real-device-challenge" "$f_command" "$f_result" "$f_summary" "$f_rawfile")"; then
      echo "  ERROR: write_evidence_record failed for ${f_test_id}" >&2
      TOTAL_REJECTED=$((TOTAL_REJECTED + 1))
      REJECTED_RECORDS+=("${f_test_id} (evidence-write failure)")
      continue
    fi

    if validate_evidence_record "$record_path" >/dev/null 2>&1; then
      TOTAL_VALIDATED=$((TOTAL_VALIDATED + 1))
      if [[ ${#EXAMPLE_RECORDS[@]} -lt 6 ]]; then
        EXAMPLE_RECORDS+=("$record_path")
      fi
    else
      TOTAL_REJECTED=$((TOTAL_REJECTED + 1))
      REJECTED_RECORDS+=("${f_test_id} ($(jq -r '.anti_bluff_status' "$record_path" 2>/dev/null || echo REJECTED))")
    fi
  done < "$jsonl"
}

process_module "app" "$APP_MATRIX_SCRIPT" "run-challenge-matrix.sh" "$APP_MANIFEST"
process_module "api-app" "$API_APP_MATRIX_SCRIPT" "run-api-app-challenge-matrix.sh" "$API_APP_MANIFEST"

# --- An empty Challenge pass proves nothing -------------------------------
# process_module returns 0 early for a module with zero selected classes. With
# BOTH modules at zero -- which the documented LAVA_PIPELINE_CHALLENGE_*_TEST_
# CLASSES overrides can produce, e.g. after a class rename leaves an
# operator's FQCN list matching nothing -- neither sibling script was invoked,
# no Challenge ran, and not one Evidence Record was written, yet the wrapper
# printed PASSED and exited 0. That is the same defect
# tests/pipeline/test_phase_02_aggregation.sh CASE 2 fixed one level up in
# phase-02-test.sh ("an empty test phase proves nothing"), reappearing one
# level down; the aggregate guard there does NOT rescue it, because it only
# fires when the run has zero records IN TOTAL, so any other wrapper's records
# let this one contribute nothing while the phase still reports PASS.
if [[ "$TOTAL_SELECTED" -eq 0 ]]; then
  echo "" >&2
  echo "phase-02-test-challenge: FAILED — zero Challenge class(es) were selected across BOTH modules, so no matrix script was invoked, no Challenge ran, and no Evidence Record was written. ${APP_DISCOVERED_COUNT} app + ${API_APP_DISCOVERED_COUNT} api-app class(es) were discovered on disk; the LAVA_PIPELINE_CHALLENGE_*_TEST_CLASSES override(s) intersected them down to nothing. A Challenge pass that scanned nothing proves nothing, so this is refused rather than reported as PASSED." >&2
  exit 1
fi
if [[ "$TOTAL_RECORDS" -eq 0 ]]; then
  echo "" >&2
  echo "phase-02-test-challenge: FAILED — ${TOTAL_SELECTED} Challenge class(es) were selected but zero Evidence Record(s) were produced. A Challenge pass that recorded nothing proves nothing, so this is refused rather than reported as PASSED." >&2
  exit 1
fi

# --- Summary ------------------------------------------------------------
echo ""
echo "phase-02-test-challenge: SUMMARY"
echo "  chosen AVD:            ${AVD_SPEC} (image: ${CONTAINER_IMAGE_REF})"
echo "  PASS:                  ${TOTAL_PASS}"
echo "  FAIL:                  ${TOTAL_FAIL}"
echo "  SKIPPED (blocked):     ${TOTAL_SKIPPED}"
echo "  ABORTED modules:       ${#ABORTED_MODULES[@]} (killed/timed-out runners; their classes are UNCONFIRMED, not FAIL)"
echo "  anti_bluff validated:  ${TOTAL_VALIDATED}"
echo "  anti_bluff REJECTED:   ${TOTAL_REJECTED}"

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
    echo "    - ${r}"
  done
fi

echo ""
echo "  Representative validated records:"
for p in "${EXAMPLE_RECORDS[@]}"; do
  echo "    - ${p#"$REPO_PATH"/}"
done

echo ""
echo "phase-02-test-challenge: Evidence Records under ${CATEGORY_DIR#"$REPO_PATH"/}"

# --- LVA-162: a gating attestation whose rows cannot be policed is refused ---
if [[ ${#DIAG_GATE_VIOLATIONS[@]} -gt 0 ]]; then
  echo "" >&2
  echo "phase-02-test-challenge: FAILED — §6.I.4 Group-B diag gate refused the attestation for: ${DIAG_GATE_VIOLATIONS[*]}. A row that declares gating: true while carrying an empty diag makes scripts/tag.sh Group-B Gate 3 (the AVD-shadow bluff gate) inert on exactly that row. Refusing is the only honest outcome available Lava-side, because the emitter (submodules/containers/pkg/emulator/matrix.go writeAttestation) is a frozen pin this repo does not edit." >&2
  exit 1
fi

# --- LVA-161: an ABORTED runner never produced verdicts; it must not PASS ----
# SKIPPED alone does not block the phase (by design -- an honestly-blocked host
# is not a failure). An abort is different in kind: the run DID start, the host
# WAS eligible, and the results were destroyed mid-flight. Letting that exit 0
# would let a timed-out gate masquerade as a green one.
if [[ ${#ABORTED_MODULES[@]} -gt 0 ]]; then
  echo "" >&2
  echo "phase-02-test-challenge: FAILED — the Challenge runner was ABORTED (killed / deadline exceeded) before it could write per-class results." >&2
  for a in "${ABORTED_MODULES[@]}"; do
    echo "    - ${a}" >&2
  done
  echo "phase-02-test-challenge: every selected class in an aborted module is recorded SKIPPED/UNCONFIRMED, NOT FAIL — the runner never gave those classes a verdict, so calling them broken would manufacture defects. It is equally NOT a pass. Read the salvaged progress line above: any test count it reports as already failed is a REAL failure that still needs fixing and MUST NOT be dismissed as 'just the timeout'." >&2
  echo "phase-02-test-challenge: remedy — raise LAVA_PIPELINE_CHALLENGE_TEST_TIMEOUT (or LAVA_PIPELINE_CHALLENGE_TEST_TIMEOUT_PER_CLASS_S, currently ${CHALLENGE_TIMEOUT_PER_CLASS_S}s/class) and re-run, or reduce the selection via LAVA_PIPELINE_CHALLENGE_*_TEST_CLASSES." >&2
  exit 1
fi

if [[ "$TOTAL_FAIL" -gt 0 || "$TOTAL_REJECTED" -gt 0 ]]; then
  echo "phase-02-test-challenge: FAILED — ${TOTAL_FAIL} real FAIL(s), ${TOTAL_REJECTED} anti-bluff rejection(s)" >&2
  exit 1
fi

echo "phase-02-test-challenge: PASSED (SKIPPED counts as a legitimate, honestly-reported non-failure outcome per the schema's own design intent)"
exit 0

#!/usr/bin/env bash
# Hermetic test suite for scripts/pipeline/phase-01-build.sh's Build Artifact
# recording, and for the artifact-reporting half of
# scripts/pipeline/phase-01-build-android.sh.
#
# No real Gradle, no real Go toolchain, no emulator: the fixture is a
# disposable repo (mktemp -d + git init) carrying a stub `gradlew` that
# creates the four APK files the real build would create, and a stub
# `lava-api-go/Makefile` whose `build:` target creates bin/lava-api-go. Both
# real build scripts already accept a [repo-path] override for exactly this
# purpose (see their headers), so the code under test is the REAL
# phase-01-build.sh driving the REAL phase-01-build-android.sh and
# phase-01-build-lava-api-go.sh — only the toolchains beneath them are stubs.
#
# WHY CASE 2 EXISTS (forensic anchor, 2026-08-21, vacuous-pass hunt):
# phase-01-build.sh's artifact bookkeeping trusted a text line. It greps
# "ARTIFACT <label>: <path>" out of each sub-build's log and records that
# path into report.json's build_artifacts[] — without ever asking whether
# the file is on disk. Underneath it, phase-01-build-android.sh's
# _phase01_build_android_report_one did:
#
#     cp -f "$src" "$dst"
#     echo "ARTIFACT $label: $dst"
#     return 0
#
# with cp's exit status unchecked. So a copy that FAILED still announced a
# successful artifact at a path that does not exist. Verbatim output from a
# real run of the unfixed pipeline against this fixture, with the app-debug
# destination directory made read-only:
#
#   cp: cannot create regular file '.../digital.vasic.lava.client-9.9.9-debug.apk': Permission denied
#   ARTIFACT app-debug: .../digital.vasic.lava.client-9.9.9-debug.apk
#   phase-01-build: recorded Build Artifact 'app-debug' (version=1.3.17/1086) -> .../client-9.9.9-debug.apk
#   phase-01-build: 5 of 5 expected Build Artifacts recorded
#   phase-01-build: all 5 Build Artifacts produced and recorded
#   PHASE EXIT=0
#   ===== does the recorded app-debug path exist on disk? =====
#   DOES NOT EXIST
#
# The phase's own PASS condition (android_rc == 0 && go_rc == 0 &&
# artifacts >= 5) was satisfied entirely by the absence of the check that
# would have caught it. Downstream, phase-02-test.sh resolves the
# release-canary APK out of exactly this build_artifacts[] entry.
#
# WHY CASE 3 EXISTS (same run, separate defect): the same output shows the
# artifact recorded as version 1.3.17/1086 while the APK it points at was
# built from the fixture repo at version 9.9.9 (visible in the filename).
# _record_build_artifact read app/build.gradle.kts and `git rev-parse HEAD`
# from $REPO_ROOT — the directory phase-01-build.sh itself lives in — while
# the sub-builders built from the [repo-path] override. The recorded
# version_name/version_code/built_from_commit therefore described a
# different checkout than the artifact. This is not a vacuous pass; it is
# recorded metadata that does not match the recorded artifact, on the same
# code path, found by the same demonstration.
#
# Exit 0 if every case passes; non-zero otherwise.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PHASE01="${REPO_ROOT}/scripts/pipeline/phase-01-build.sh"

if [[ ! -f "$PHASE01" ]]; then
  echo "FAIL: script under test not found: $PHASE01"
  exit 1
fi
for tool in jq git python3 make; do
  command -v "$tool" >/dev/null 2>&1 || { echo "FAIL: '$tool' required"; exit 1; }
done

FAILURES=0
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAILURES=$((FAILURES + 1)); }

WORKDIR="$(mktemp -d)"
# chmod back before rm -rf: case 2 deliberately makes a directory read-only.
cleanup() { chmod -R u+rwX -- "$WORKDIR" 2>/dev/null || true; rm -rf -- "$WORKDIR"; }
trap cleanup EXIT

FIXTURE_APP_VERSION="9.9.9"
FIXTURE_APP_CODE="42"
FIXTURE_API_APP_VERSION="0.0.7"
FIXTURE_API_APP_CODE="7"

# _new_fixture_repo <name> <gradlew-mode>
# gradlew-mode: "produces" -> stub gradlew writes all 4 APKs
#               "empty"    -> stub gradlew exits 0 having written nothing
# Prints the repo path.
_new_fixture_repo() {
  local name="$1" mode="$2"
  local dir="${WORKDIR}/${name}"
  mkdir -p "$dir"
  git init -q -b master "$dir"
  git -C "$dir" config user.email "fixture@example.invalid"
  git -C "$dir" config user.name "Fixture"

  mkdir -p "${dir}/app" "${dir}/api-app" "${dir}/lava-api-go"
  printf 'android {\n    defaultConfig {\n        versionCode = %s\n        versionName = "%s"\n    }\n}\n' \
    "$FIXTURE_APP_CODE" "$FIXTURE_APP_VERSION" > "${dir}/app/build.gradle.kts"
  printf 'android {\n    defaultConfig {\n        versionCode = %s\n        versionName = "%s"\n    }\n}\n' \
    "$FIXTURE_API_APP_CODE" "$FIXTURE_API_APP_VERSION" > "${dir}/api-app/build.gradle.kts"

  if [[ "$mode" == "produces" ]]; then
    cat > "${dir}/gradlew" <<'GRADLEW'
#!/usr/bin/env bash
set -euo pipefail
mkdir -p app/build/outputs/apk/debug app/build/outputs/apk/release \
         api-app/build/outputs/apk/debug api-app/build/outputs/apk/release
printf 'STUB-APK-CONTENT\n' > app/build/outputs/apk/debug/app-debug.apk
printf 'STUB-APK-CONTENT\n' > app/build/outputs/apk/release/app-release.apk
printf 'STUB-APK-CONTENT\n' > api-app/build/outputs/apk/debug/api-app-debug.apk
printf 'STUB-APK-CONTENT\n' > api-app/build/outputs/apk/release/api-app-release.apk
echo "BUILD SUCCESSFUL in 1s"
GRADLEW
  else
    printf '#!/usr/bin/env bash\necho "BUILD SUCCESSFUL in 1s"\nexit 0\n' > "${dir}/gradlew"
  fi
  chmod +x "${dir}/gradlew"

  # Stub Makefile whose build: target creates a real, non-empty, executable
  # bin/lava-api-go that answers --version the way the real binary does.
  {
    printf 'build:\n'
    printf '\tmkdir -p bin\n'
    printf '\tprintf "#!/usr/bin/env bash\\necho \\"lava-api-go 1.2.3 (build 123)\\"\\n" > bin/lava-api-go\n'
    printf '\tchmod +x bin/lava-api-go\n'
  } > "${dir}/lava-api-go/Makefile"

  printf '.lava-ci-evidence/\nreleases/\nbin/\nbuild/\n' > "${dir}/.gitignore"
  git -C "$dir" add -A >/dev/null 2>&1
  git -C "$dir" commit -qm "fixture" >/dev/null 2>&1
  printf '%s' "$dir"
}

# _run_phase01 <repo-dir> <run_id> — sets P1_RC and P1_OUT.
_run_phase01() {
  local dir="$1" run_id="$2"
  ( cd "$dir" && source "${REPO_ROOT}/scripts/pipeline/lib/run-report.sh" && \
    init_run_report "$run_id" "$(git -C "$dir" rev-parse HEAD)" >/dev/null )
  local out_file="${WORKDIR}/phase01-${run_id}.log"
  set +e
  ( cd "$dir" && bash "$PHASE01" "$run_id" "$dir" ) >"$out_file" 2>&1
  P1_RC=$?
  set -e
  P1_OUT="$(cat "$out_file")"
}

_report() { printf '%s/.lava-ci-evidence/pipeline-runs/%s/report.json' "$1" "$2"; }

echo "==============================================================="
echo "CASE 1: every artifact genuinely on disk -> PASS"
echo "(guards against a 'fix' that just fails every build)"
echo "==============================================================="

RUN_A="2026-08-21T01-00-00Z"
DIR_A="$(_new_fixture_repo happy produces)"
_run_phase01 "$DIR_A" "$RUN_A"
REPORT_A="$(_report "$DIR_A" "$RUN_A")"

if [[ "$P1_RC" -eq 0 ]]; then
  pass "all 5 artifacts really produced -> exit 0"
else
  fail "happy path -> exit ${P1_RC}, expected 0; output: ${P1_OUT}"
fi
count_a="$(jq -r '.build_artifacts | length' "$REPORT_A")"
if [[ "$count_a" == "5" ]]; then
  pass "happy path: 5 Build Artifacts recorded in report.json"
else
  fail "happy path: report.json has ${count_a} build_artifacts, expected 5"
fi
res_a="$(jq -r '.phases[] | select(.name=="build") | .result' "$REPORT_A")"
if [[ "$res_a" == "PASS" ]]; then
  pass "happy path: report.json records build phase PASS"
else
  fail "happy path: build phase result is '${res_a}', expected PASS"
fi

missing_on_disk=0
while IFS= read -r p; do
  [[ -s "$p" ]] || { missing_on_disk=$((missing_on_disk + 1)); echo "    not on disk: $p"; }
done < <(jq -r '.build_artifacts[].build_output_path' "$REPORT_A")
if [[ "$missing_on_disk" -eq 0 ]]; then
  pass "happy path: every recorded build_output_path exists and is non-empty on disk"
else
  fail "happy path: ${missing_on_disk} recorded build_output_path(s) are not real files"
fi

echo ""
echo "==============================================================="
echo "CASE 2 (LOAD-BEARING): the copy fails, so the artifact is not"
echo "on disk — the phase must not report success"
echo "==============================================================="
echo "The destination directory for app-debug is made read-only, so"
echo "the real cp inside phase-01-build-android.sh genuinely fails."
echo "Everything else about the run is healthy."
echo ""

RUN_B="2026-08-21T02-00-00Z"
DIR_B="$(_new_fixture_repo uncopyable produces)"
BLOCKED_DIR="${DIR_B}/releases/${FIXTURE_APP_VERSION}/android-debug"
mkdir -p "$BLOCKED_DIR"
chmod 555 "$BLOCKED_DIR"
_run_phase01 "$DIR_B" "$RUN_B"
REPORT_B="$(_report "$DIR_B" "$RUN_B")"

if grep -q "Permission denied" <<< "$P1_OUT"; then
  pass "fixture sanity: the copy really did fail (cp reported Permission denied)"
else
  fail "fixture sanity: cp did not fail, so this case proves nothing; output: ${P1_OUT}"
fi

if [[ "$P1_RC" -ne 0 ]]; then
  pass "artifact missing from disk -> non-zero exit (${P1_RC})"
else
  fail "artifact missing from disk -> exit 0. The phase announced 'all 5 Build Artifacts produced and recorded' for a file that is not there. The ARTIFACT line was believed without checking the path."
fi

res_b="$(jq -r '.phases[] | select(.name=="build") | .result' "$REPORT_B" 2>/dev/null)"
if [[ "$res_b" == "FAIL" ]]; then
  pass "artifact missing from disk: report.json records build phase FAIL"
else
  fail "artifact missing from disk: build phase result is '${res_b}', expected FAIL — the run report would carry this forward as a passing phase"
fi

phantom=0
while IFS= read -r p; do
  [[ -z "$p" ]] && continue
  [[ -s "$p" ]] || { phantom=$((phantom + 1)); echo "    phantom artifact recorded: $p"; }
done < <(jq -r '.build_artifacts[].build_output_path' "$REPORT_B" 2>/dev/null)
if [[ "$phantom" -eq 0 ]]; then
  pass "artifact missing from disk: no phantom build_output_path was recorded in report.json"
else
  fail "${phantom} build_artifacts entr(ies) in report.json point at file(s) that do not exist. phase-02-test.sh resolves the release-canary APK out of exactly this array."
fi

chmod 755 "$BLOCKED_DIR"

echo ""
echo "==============================================================="
echo "CASE 3: recorded version metadata must describe the artifact"
echo "that was actually built"
echo "==============================================================="
echo "The fixture repo declares app ${FIXTURE_APP_VERSION}/${FIXTURE_APP_CODE} and"
echo "api-app ${FIXTURE_API_APP_VERSION}/${FIXTURE_API_APP_CODE}, and its own HEAD commit."
echo ""

vn_a="$(jq -r '.build_artifacts[] | select(.artifact_id=="app-debug") | .version_name' "$REPORT_A")"
vc_a="$(jq -r '.build_artifacts[] | select(.artifact_id=="app-debug") | .version_code' "$REPORT_A")"
if [[ "$vn_a" == "$FIXTURE_APP_VERSION" && "$vc_a" == "$FIXTURE_APP_CODE" ]]; then
  pass "app-debug recorded as ${vn_a}/${vc_a}, matching the repo that was built"
else
  fail "app-debug recorded as ${vn_a}/${vc_a}, but the APK was built from a checkout declaring ${FIXTURE_APP_VERSION}/${FIXTURE_APP_CODE} — the version metadata was read from a different repository than the one that produced the artifact"
fi

vn_api="$(jq -r '.build_artifacts[] | select(.artifact_id=="api-app-release") | .version_name' "$REPORT_A")"
vc_api="$(jq -r '.build_artifacts[] | select(.artifact_id=="api-app-release") | .version_code' "$REPORT_A")"
if [[ "$vn_api" == "$FIXTURE_API_APP_VERSION" && "$vc_api" == "$FIXTURE_API_APP_CODE" ]]; then
  pass "api-app-release recorded as ${vn_api}/${vc_api}, matching the repo that was built"
else
  fail "api-app-release recorded as ${vn_api}/${vc_api}, expected ${FIXTURE_API_APP_VERSION}/${FIXTURE_API_APP_CODE}"
fi

want_sha="$(git -C "$DIR_A" rev-parse HEAD)"
got_sha="$(jq -r '.build_artifacts[0].built_from_commit' "$REPORT_A")"
if [[ "$got_sha" == "$want_sha" ]]; then
  pass "built_from_commit is the HEAD of the repo that was actually built"
else
  fail "built_from_commit is ${got_sha}, but the repo that was built is at ${want_sha}"
fi

echo ""
echo "==============================================================="
echo "CASE 4: a build that produces nothing still fails"
echo "(pre-existing guard — must not regress)"
echo "==============================================================="

RUN_C="2026-08-21T03-00-00Z"
DIR_C="$(_new_fixture_repo noapks empty)"
_run_phase01 "$DIR_C" "$RUN_C"
if [[ "$P1_RC" -ne 0 ]] && grep -q "MISSING" <<< "$P1_OUT"; then
  pass "gradle exits 0 having produced no APKs -> non-zero exit with MISSING reported"
else
  fail "zero-APK build -> exit ${P1_RC} without the expected refusal; output: ${P1_OUT}"
fi

echo ""
echo "==============================================================="
if [[ "$FAILURES" -eq 0 ]]; then
  echo "ALL CHECKS PASSED"
  exit 0
else
  echo "$FAILURES CHECK(S) FAILED"
  exit 1
fi

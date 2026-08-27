#!/usr/bin/env bash
# Hermetic test for phase-05a's _assert_no_drift check.
#
# scripts/pipeline/phase-05a-changelog-entry.sh COPIES three literals per app
# out of scripts/firebase-distribute.sh's `case "$SELECTED_APP"` block: the
# gradle version file, the changelog channel, and the Gate-2 pattern template.
# Its header promises that a silent divergence there is "impossible to miss"
# because the script re-greps that file at runtime for each copied literal and
# "FAILS LOUDLY if the literal is no longer present".
#
# WHY THAT MATTERS (forensic anchor, 2026-08-25): the two apps' literals are
# SUBSTRINGS of one another --
#     "firebase-app-distribution"  is a prefix of "firebase-app-distribution-api-app"
#     "app/build.gradle.kts"       is a substring of "api-app/build.gradle.kts"
# -- so a bare `grep -qF -- "$APP_CHANNEL"` for the CLIENT channel is satisfied
# by the API-APP channel's assignment line, and a bare `grep -qF --
# "$APP_GRADLE_FILE"` for the client gradle file is satisfied by the api-app
# gradle line AND by ordinary comment/error-message prose. The drift check then
# passes having matched a DIFFERENT app's literal (or a comment), phase-05a
# resolves the OLD channel, writes the snapshot into the OLD directory, reports
# PASS -- and firebase-distribute.sh Gate 3 then refuses the distribute for a
# snapshot it cannot find. That is exactly the "green-looking phase followed by
# a hard distribute refusal" the header says it prevents.
#
# The check must therefore match each literal IN ITS ASSIGNMENT CONTEXT
# (VARNAME="literal"), which no other app's line and no comment can satisfy.
#
# scripts/firebase-distribute.sh is never executed: the fixture carries a
# stand-in holding the six assignments phase-05a greps for. The seam is
# phase-05a's documented `[repo-path]` positional. Nothing here touches this
# repository.
#
# Exit 0 if every case passes; non-zero otherwise.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PHASE05A="${REPO_ROOT}/scripts/pipeline/phase-05a-changelog-entry.sh"

[[ -f "$PHASE05A" ]] || { echo "FAIL: script under test not found: $PHASE05A"; exit 1; }
for tool in jq python3 git; do
  command -v "$tool" >/dev/null 2>&1 || { echo "FAIL: '$tool' required"; exit 1; }
done

FAILURES=0
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAILURES=$((FAILURES + 1)); }

WORKDIR="$(mktemp -d)"
trap 'rm -rf -- "$WORKDIR"' EXIT

CLIENT_VERSION="3.1.4"
CLIENT_CODE="3140"

# _mkfix <dir> — a fixture repo whose stand-in firebase-distribute.sh carries
# BOTH apps' literals in their real assignment form, exactly as the real file
# does. Callers then mutate that stand-in to simulate an upstream edit.
_mkfix() {
  local fix="$1"
  mkdir -p "${fix}/scripts" "${fix}/app" "${fix}/api-app"
  git init -q -b master "$fix"
  git -C "$fix" config user.email "fixture@example.invalid"
  git -C "$fix" config user.name "Fixture"

  cat > "${fix}/scripts/firebase-distribute.sh" <<'FD'
#!/usr/bin/env bash
# Stand-in mirroring the real per-app config block, including the ordinary
# prose that mentions the same paths (the real file has three such lines).
# ----------------------------------------------------------------
#    per-app gradle file (client: app/build.gradle.kts;
#    api-app: api-app/build.gradle.kts).
# ----------------------------------------------------------------
case "$SELECTED_APP" in
    client)
        GRADLE_VERSION_FILE="app/build.gradle.kts"
        CHANGELOG_CHANNEL="firebase-app-distribution"
        CHANGELOG_PATTERN_TMPL='Lava-Android-?APP_VERSION-?APP_VERSION_CODE|Lava-Android APP_VERSION \(APP_VERSION_CODE\)'
        ;;
    api-app)
        GRADLE_VERSION_FILE="api-app/build.gradle.kts"
        CHANGELOG_CHANNEL="firebase-app-distribution-api-app"
        CHANGELOG_PATTERN_TMPL='Lava-API-App-?APP_VERSION-?APP_VERSION_CODE|Lava-API-App APP_VERSION \(APP_VERSION_CODE\)'
        ;;
esac
echo "       Bump versionCode in app/build.gradle.kts before re-running this script." >&2
FD

  cat > "${fix}/app/build.gradle.kts" <<G
android {
    defaultConfig {
        versionCode = ${CLIENT_CODE}
        versionName = "${CLIENT_VERSION}"
    }
}
G
  cp "${fix}/app/build.gradle.kts" "${fix}/api-app/build.gradle.kts"
  printf '# Changelog\n\n## Lava-Android-1.0.0-1000 — an older release\n\nolder notes\n' \
    > "${fix}/CHANGELOG.md"
  git -C "$fix" add -A >/dev/null 2>&1
  git -C "$fix" commit -qm "fixture init" >/dev/null 2>&1
}

# _run <fix> <run_id> -> echoes "<rc>|<combined output>"
_run() {
  local fix="$1" rid="$2" out rc
  ( cd "$fix" && source "${REPO_ROOT}/scripts/pipeline/lib/run-report.sh" \
      && init_run_report "$rid" "$(git -C "$fix" rev-parse HEAD)" >/dev/null )
  set +e
  out="$( cd "$fix" && bash "$PHASE05A" "$rid" "$fix" --app client 2>&1 )"
  rc=$?
  set -e
  printf '%s|%s' "$rc" "$out"
}

# _case <name> <mutation-sed-expr-or-empty> <expect: pass|drift>
_case() {
  local name="$1" mutate="$2" expect="$3"
  local fix="${WORKDIR}/$(echo "$name" | tr -c 'a-zA-Z0-9' '_')"
  _mkfix "$fix"
  [[ -n "$mutate" ]] && sed -i "$mutate" "${fix}/scripts/firebase-distribute.sh"
  local res rc out
  res="$(_run "$fix" "2026-08-25T50-00-00Z")"
  rc="${res%%|*}"; out="${res#*|}"

  if [[ "$expect" == "pass" ]]; then
    if [[ "$rc" -eq 0 ]]; then
      pass "${name}: unmutated stand-in -> phase exits 0 (over-correction guard)"
    else
      fail "${name}: expected exit 0, got ${rc}; output: ${out}"
    fi
    return
  fi

  if [[ "$rc" -eq 0 ]]; then
    fail "${name}: DRIFT WENT UNDETECTED — phase exited 0. The literal it copied is gone from the stand-in, yet the check matched something else (another app's line, or prose). Output: ${out}"
    return
  fi
  if printf '%s' "$out" | grep -qF -- "DRIFT against"; then
    pass "${name}: drift detected, phase exits ${rc} with a DRIFT failure"
  else
    fail "${name}: phase exited ${rc} but not with a DRIFT failure; output: ${out}"
  fi
}

echo "==============================================================="
echo "CASE 0: an unmutated stand-in must still PASS (over-correction guard)"
echo "==============================================================="
_case "case0-unmutated" "" pass

echo
echo "==============================================================="
echo "CASE 1: client CHANNEL renamed upstream; api-app channel still"
echo "        contains the old client channel as a prefix substring"
echo "==============================================================="
_case "case1-channel-renamed" \
  's|CHANGELOG_CHANNEL="firebase-app-distribution"|CHANGELOG_CHANNEL="firebase-app-distribution-client"|' \
  drift

echo
echo "==============================================================="
echo "CASE 2: client GRADLE FILE changed upstream; the api-app gradle"
echo "        line and two comment lines still contain the old path"
echo "==============================================================="
_case "case2-gradle-renamed" \
  's|GRADLE_VERSION_FILE="app/build.gradle.kts"|GRADLE_VERSION_FILE="client/build.gradle.kts"|' \
  drift

echo
echo "==============================================================="
echo "CASE 3: client Gate-2 PATTERN template changed upstream"
echo "        (regression guard — this one already worked)"
echo "==============================================================="
_case "case3-pattern-changed" \
  "s|Lava-Android-?APP_VERSION-?APP_VERSION_CODE|Lava-Android_vAPP_VERSION_bAPP_VERSION_CODE|" \
  drift

echo
echo "==============================================================="
echo "CASE 4: the client literals survive ONLY as comment prose, with"
echo "        every real assignment removed — a bare substring grep"
echo "        would pass; matching the assignment context must not"
echo "==============================================================="
_case "case4-comments-only" \
  's|^        GRADLE_VERSION_FILE="app/build.gradle.kts"|        # was: app/build.gradle.kts and firebase-app-distribution|; s|^        CHANGELOG_CHANNEL="firebase-app-distribution"$|        # channel literal now lives only in this comment|' \
  drift

echo
echo "==============================================================="
if [[ "$FAILURES" -eq 0 ]]; then
  echo "ALL CASES PASSED"
  exit 0
fi
echo "${FAILURES} CASE(S) FAILED"
exit 1

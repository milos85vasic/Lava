#!/usr/bin/env bash
# Hermetic test: phase-05a must refuse to author release artefacts from a
# version parse that did not actually parse.
#
# scripts/pipeline/phase-05a-changelog-entry.sh reads the version identity
# with expressions copied byte-for-byte from scripts/firebase-distribute.sh
# (that file's lines 113-116):
#
#   APP_VERSION="$(grep -E '^\s+versionName\s*=' "$G" | head -1 | sed 's/.*"\([^"]*\)".*/\1/')"
#   APP_VERSION_CODE="$(grep -E '^\s+versionCode\s*=' "$G" | head -1 | sed 's/.*= \([0-9]*\).*/\1/')"
#
# and guards them with `[[ -z "$APP_VERSION" || -z "$APP_VERSION_CODE" ]]`.
#
# WHY THAT GUARD IS NOT ENOUGH (forensic anchor, 2026-08-25): `sed` prints its
# input UNCHANGED when the pattern does not match. The grep is permissive
# (`\s*=`, no space required) while the sed demands a literal "= ". So
# `versionCode=1086` -- no spaces, perfectly valid Kotlin DSL -- passes the
# grep, fails the sed, and yields the WHOLE LINE. That is non-empty, so the
# -z guard sees a healthy value, and the phase went on to report PASS while
# writing a real file to disk named
#
#   .lava-ci-evidence/distribute-changelog/firebase-app-distribution/9.9.1-        versionCode=9001.md
#
# and a CHANGELOG heading reading `Lava-Android-9.9.1-        versionCode=9001`.
# A no-match parse was read as success. Downstream, firebase-distribute.sh
# compares that value ARITHMETICALLY (`[[ "$APP_VERSION_CODE" -le
# "$LAST_DISTRIBUTED" ]]`, its line 190), so the garbage does not stay
# cosmetic.
#
# The parse expression itself must stay byte-identical to the gate's -- the
# two have to agree or Gate 2/Gate 3 refuse the distribute -- so the fix is
# not to diverge from it but to VALIDATE ITS OUTPUT: a versionCode that is not
# a plain integer, or a versionName carrying whitespace/quotes, means the
# parse did not parse, and this phase must fail loudly instead of authoring
# release artefacts around it.
#
# scripts/firebase-distribute.sh is never executed; the fixture carries a
# stand-in holding the assignments phase-05a's drift check greps for. The seam
# is the documented `[repo-path]` positional. Nothing here touches this
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

# _mkfix <dir> <gradle-defaultConfig-body>
_mkfix() {
  local fix="$1" body="$2"
  mkdir -p "${fix}/scripts" "${fix}/app"
  git init -q -b master "$fix"
  git -C "$fix" config user.email "fixture@example.invalid"
  git -C "$fix" config user.name "Fixture"
  cat > "${fix}/scripts/firebase-distribute.sh" <<'FD'
#!/usr/bin/env bash
case "$SELECTED_APP" in
    client)
        GRADLE_VERSION_FILE="app/build.gradle.kts"
        CHANGELOG_CHANNEL="firebase-app-distribution"
        CHANGELOG_PATTERN_TMPL='Lava-Android-?APP_VERSION-?APP_VERSION_CODE|Lava-Android APP_VERSION \(APP_VERSION_CODE\)'
        ;;
esac
FD
  printf 'android {\n    defaultConfig {\n%s\n    }\n}\n' "$body" > "${fix}/app/build.gradle.kts"
  printf '# Changelog\n\n## Lava-Android-1.0.0-1000 — older\n\nnotes\n' > "${fix}/CHANGELOG.md"
  git -C "$fix" add -A >/dev/null 2>&1
  git -C "$fix" commit -qm "fixture init" >/dev/null 2>&1
}

# _run <fix> -> sets RC / OUT / SNAPSHOTS
_run() {
  local fix="$1" rid="2026-08-25T90-00-00Z"
  ( cd "$fix" && source "${REPO_ROOT}/scripts/pipeline/lib/run-report.sh" \
      && init_run_report "$rid" "$(git -C "$fix" rev-parse HEAD)" >/dev/null )
  set +e
  OUT="$( cd "$fix" && bash "$PHASE05A" "$rid" "$fix" --app client 2>&1 )"
  RC=$?
  set -e
  # Braces + `|| true`: with `set -o pipefail`, a `find` over a directory that
  # does not exist yet (the correct outcome for the rejection cases) would
  # otherwise fail the pipeline and abort this test under `set -e`.
  SNAPSHOTS="$( { find "${fix}/.lava-ci-evidence/distribute-changelog" -type f 2>/dev/null || true; } | wc -l | tr -d '[:space:]')"
}

echo "==============================================================="
echo "CASE 0: a normal gradle file still works (over-correction guard)"
echo "==============================================================="
F="${WORKDIR}/case0"
_mkfix "$F" '        versionCode = 9001
        versionName = "9.9.1"'
_run "$F"
if [[ "$RC" -eq 0 ]]; then pass "case0: exits 0"; else fail "case0: expected 0, got ${RC}; ${OUT}"; fi
if [[ -f "${F}/.lava-ci-evidence/distribute-changelog/firebase-app-distribution/9.9.1-9001.md" ]]; then
  pass "case0: snapshot written at the expected 9.9.1-9001.md"
else
  fail "case0: expected snapshot 9.9.1-9001.md; found: $(find "${F}/.lava-ci-evidence/distribute-changelog" -type f 2>/dev/null)"
fi

echo
echo "==============================================================="
echo "CASE 1: a versionName with a real suffix must STILL pass"
echo "        (the sanity rule must not be over-tight)"
echo "==============================================================="
F="${WORKDIR}/case1"
_mkfix "$F" '        versionCode = 9002
        versionName = "9.9.2-rc1"'
_run "$F"
if [[ "$RC" -eq 0 ]]; then
  pass "case1: a '-rc1' suffixed versionName is accepted"
else
  fail "case1: over-correction — a legitimate suffixed version was rejected (exit ${RC}); ${OUT}"
fi

echo
echo "==============================================================="
echo "CASE 2 (LOAD-BEARING): 'versionCode=9001' with no spaces --"
echo "        the sed does not match and returns the whole line"
echo "==============================================================="
F="${WORKDIR}/case2"
_mkfix "$F" '        versionCode=9001
        versionName = "9.9.1"'
_run "$F"
if [[ "$RC" -eq 0 ]]; then
  fail "case2: NO-MATCH PARSE READ AS SUCCESS — phase exited 0 on an unparsed versionCode. Output: ${OUT}"
else
  pass "case2: the unparsed versionCode is rejected (exit ${RC})"
fi
if [[ "$SNAPSHOTS" -eq 0 ]]; then
  pass "case2: no garbage-named snapshot was written to disk"
else
  fail "case2: ${SNAPSHOTS} snapshot(s) written from an unparsed version: $(find "${F}/.lava-ci-evidence/distribute-changelog" -type f 2>/dev/null)"
fi
if printf '%s' "$OUT" | grep -qF -- "Lava-Android-9.9.1-        versionCode=9001"; then
  fail "case2: the phase built a release LABEL out of the raw gradle line"
else
  pass "case2: no release label was built from the raw gradle line"
fi

echo
echo "==============================================================="
echo "CASE 3: an unquoted versionName -- the sed again returns the"
echo "        whole line, and it is again non-empty"
echo "==============================================================="
F="${WORKDIR}/case3"
_mkfix "$F" '        versionCode = 9003
        versionName = releaseNameConstant'
_run "$F"
if [[ "$RC" -eq 0 ]]; then
  fail "case3: NO-MATCH PARSE READ AS SUCCESS — phase exited 0 on an unparsed versionName. Output: ${OUT}"
else
  pass "case3: the unparsed versionName is rejected (exit ${RC})"
fi
if [[ "$SNAPSHOTS" -eq 0 ]]; then
  pass "case3: no garbage-named snapshot was written to disk"
else
  fail "case3: ${SNAPSHOTS} snapshot(s) written from an unparsed version"
fi

echo
echo "==============================================================="
echo "CASE 4: a genuinely absent versionCode still reports the"
echo "        original 'could not parse' failure (regression guard)"
echo "==============================================================="
F="${WORKDIR}/case4"
_mkfix "$F" '        versionName = "9.9.4"'
_run "$F"
if [[ "$RC" -ne 0 ]]; then
  pass "case4: a missing versionCode is rejected (exit ${RC})"
else
  fail "case4: expected non-zero, got 0; ${OUT}"
fi

echo
echo "==============================================================="
if [[ "$FAILURES" -eq 0 ]]; then
  echo "ALL CASES PASSED"
  exit 0
fi
echo "${FAILURES} CASE(S) FAILED"
exit 1

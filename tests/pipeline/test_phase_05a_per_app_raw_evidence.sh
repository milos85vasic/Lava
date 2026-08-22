#!/usr/bin/env bash
# Hermetic test for the per-app independence of phase-05a's Evidence Records.
#
# scripts/pipeline/phase-05a-changelog-entry.sh processes one or both apps
# (client, api-app) in a single run and writes ONE Evidence Record per app.
# Every one of those records used to carry the SAME raw_output_ref -- a single
# `changelog-entry-combined.log` holding both apps' output interleaved.
#
# WHY THAT MATTERS (forensic anchor, 2026-08-22): an Evidence Record's
# raw_output_ref is the artifact a reader opens to check the record's claim.
# When two records point at one file, neither claim is independently
# falsifiable: if the api-app entry were wrong, opening the api-app record's
# raw file shows the client app's successful output right next to it, and
# nothing in that file is attributable to one record rather than the other.
# The record for a FAILED app and the record for a PASSED app would cite
# byte-identical evidence. Per-record evidence has to be per-record.
#
# scripts/firebase-distribute.sh is never executed: the fixture carries a
# stand-in holding exactly the six literals phase-05a's own _assert_no_drift
# greps for (three per app), so the drift check passes and what is under test
# is the phase's own evidence layout. The seam is its documented `[repo-path]`
# positional. Nothing here touches this repository.
#
# Exit 0 if every case passes; non-zero otherwise.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PHASE05A="${REPO_ROOT}/scripts/pipeline/phase-05a-changelog-entry.sh"

if [[ ! -f "$PHASE05A" ]]; then
  echo "FAIL: script under test not found: $PHASE05A"
  exit 1
fi
for tool in jq python3 git; do
  command -v "$tool" >/dev/null 2>&1 || { echo "FAIL: '$tool' required"; exit 1; }
done

FAILURES=0
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAILURES=$((FAILURES + 1)); }

WORKDIR="$(mktemp -d)"
trap 'rm -rf -- "$WORKDIR"' EXIT

CLIENT_VERSION="2.4.1"
CLIENT_CODE="2411"
API_VERSION="0.9.7"
API_CODE="97"
RUN_ID="2026-08-22T44-00-00Z"

FIX="${WORKDIR}/repo"
mkdir -p "${FIX}/scripts" "${FIX}/app" "${FIX}/api-app"
git init -q -b master "$FIX"
git -C "$FIX" config user.email "fixture@example.invalid"
git -C "$FIX" config user.name "Fixture"

# Stand-in carrying BOTH apps' drift-checked literals verbatim.
cat > "${FIX}/scripts/firebase-distribute.sh" <<'FD'
#!/usr/bin/env bash
# Stand-in carrying the literals phase-05a's _assert_no_drift greps for.
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
FD

cat > "${FIX}/app/build.gradle.kts" <<G
android {
    defaultConfig {
        versionCode = ${CLIENT_CODE}
        versionName = "${CLIENT_VERSION}"
    }
}
G
cat > "${FIX}/api-app/build.gradle.kts" <<G
android {
    defaultConfig {
        versionCode = ${API_CODE}
        versionName = "${API_VERSION}"
    }
}
G
printf '# Changelog\n\n## Lava-Android-1.0.0-1000 — an older release\n\nolder notes\n' \
  > "${FIX}/CHANGELOG.md"
git -C "$FIX" add -A >/dev/null 2>&1
git -C "$FIX" commit -qm "fixture init" >/dev/null 2>&1

( cd "$FIX" && source "${REPO_ROOT}/scripts/pipeline/lib/run-report.sh" \
    && init_run_report "$RUN_ID" "$(git -C "$FIX" rev-parse HEAD)" >/dev/null )

OUT_FILE="${WORKDIR}/p5a.log"
set +e
( cd "$FIX" && bash "$PHASE05A" "$RUN_ID" "$FIX" --app both ) >"$OUT_FILE" 2>&1
P5_RC=$?
set -e
P5_OUT="$(cat "$OUT_FILE")"

PHASE_DIR="${FIX}/.lava-ci-evidence/pipeline-runs/${RUN_ID}/phase-05a"
REC_DIR="${PHASE_DIR}/hermetic-script"
REC_CLIENT="${REC_DIR}/changelog-entry-client-${CLIENT_VERSION}-${CLIENT_CODE}.json"
REC_API="${REC_DIR}/changelog-entry-api-app-${API_VERSION}-${API_CODE}.json"

CLIENT_LABEL="Lava-Android-${CLIENT_VERSION}-${CLIENT_CODE}"
API_LABEL="Lava-API-App-${API_VERSION}-${API_CODE}"

echo "==============================================================="
echo "CASE 0: the two-app run really succeeds (over-correction guard)"
echo "==============================================================="
if [[ "$P5_RC" -eq 0 ]]; then
  pass "--app both -> phase exits 0"
else
  fail "--app both -> phase exits ${P5_RC}; output: ${P5_OUT}"
fi
for r in "$REC_CLIENT" "$REC_API"; do
  if [[ -f "$r" ]]; then
    pass "Evidence Record present: $(basename "$r")"
  else
    fail "no Evidence Record at ${r}; output: ${P5_OUT}"
  fi
done

echo ""
echo "==============================================================="
echo "CASE 1 (LOAD-BEARING): each app's record cites its OWN raw file"
echo "==============================================================="
if [[ -f "$REC_CLIENT" && -f "$REC_API" ]]; then
  REF_CLIENT="$(jq -r '.raw_output_ref' "$REC_CLIENT")"
  REF_API="$(jq -r '.raw_output_ref' "$REC_API")"
  echo "  client  raw_output_ref = ${REF_CLIENT}"
  echo "  api-app raw_output_ref = ${REF_API}"

  if [[ "$REF_CLIENT" != "$REF_API" ]]; then
    pass "the two records cite DIFFERENT raw_output_ref values"
  else
    fail "both records cite the same raw_output_ref ('${REF_CLIENT}') — neither claim is independently falsifiable"
  fi

  # raw_output_ref is resolved relative to the record file's own directory.
  ABS_CLIENT="${REC_DIR}/${REF_CLIENT}"
  ABS_API="${REC_DIR}/${REF_API}"

  for pair in "client:${ABS_CLIENT}" "api-app:${ABS_API}"; do
    app="${pair%%:*}"; f="${pair#*:}"
    if [[ -f "$f" && -s "$f" ]]; then
      pass "[${app}] its raw_output_ref resolves to a real, non-empty file"
    else
      fail "[${app}] raw_output_ref does not resolve to a non-empty regular file: ${f}"
    fi
  done

  echo ""
  echo "  --- the falsifiability core: each raw file must carry that app's own"
  echo "      captured output and NOT be a copy of the other app's ---"

  # Each raw file must contain its OWN app's real, run-specific facts...
  if grep -qF -- "$CLIENT_LABEL" "$ABS_CLIENT" 2>/dev/null; then
    pass "[client] its raw file contains the client's own release label (${CLIENT_LABEL})"
  else
    fail "[client] its raw file never mentions ${CLIENT_LABEL}"
  fi
  if grep -qF -- "$API_LABEL" "$ABS_API" 2>/dev/null; then
    pass "[api-app] its raw file contains the api-app's own release label (${API_LABEL})"
  else
    fail "[api-app] its raw file never mentions ${API_LABEL}"
  fi

  # ...and must NOT contain the other app's, or the reader cannot attribute
  # any line in it to the record that cites it.
  if grep -qF -- "$API_LABEL" "$ABS_CLIENT" 2>/dev/null; then
    fail "[client] its raw file also contains the api-app's release label (${API_LABEL}) — the evidence is not attributable to this record"
  else
    pass "[client] its raw file does NOT contain the api-app's release label"
  fi
  if grep -qF -- "$CLIENT_LABEL" "$ABS_API" 2>/dev/null; then
    fail "[api-app] its raw file also contains the client's release label (${CLIENT_LABEL}) — the evidence is not attributable to this record"
  else
    pass "[api-app] its raw file does NOT contain the client's release label"
  fi
else
  fail "cannot run CASE 1: one or both Evidence Records are missing"
fi

echo ""
echo "==============================================================="
echo "CASE 2: both records still pass anti-bluff validation"
echo "(a per-app split that produced an unreadable or empty raw file"
echo " would be a regression, not a fix)"
echo "==============================================================="
for pair in "client:${REC_CLIENT}" "api-app:${REC_API}"; do
  app="${pair%%:*}"; r="${pair#*:}"
  if [[ -f "$r" ]]; then
    abs="$(jq -r '.anti_bluff_status' "$r" 2>/dev/null || echo '<unreadable>')"
    if [[ "$abs" == "validated" ]]; then
      pass "[${app}] anti_bluff_status == validated"
    else
      fail "[${app}] anti_bluff_status == '${abs}'"
    fi
  fi
done

echo ""
echo "---"
if [[ "$FAILURES" -eq 0 ]]; then
  echo "PASS: phase-05a per-app raw-evidence suite passed"
  exit 0
else
  echo "FAIL: ${FAILURES} phase-05a per-app raw-evidence assertion(s) failed"
  exit 1
fi

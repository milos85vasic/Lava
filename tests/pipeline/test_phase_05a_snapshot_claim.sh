#!/usr/bin/env bash
# Hermetic test suite for scripts/pipeline/phase-05a-changelog-entry.sh's
# post-write self-verification and the honesty of the Evidence Record it
# writes about the per-version snapshot.
#
# scripts/firebase-distribute.sh is never executed. A throwaway repo carries a
# stand-in containing exactly the three literals the phase's own
# _assert_no_drift greps for, so the drift check passes and the behaviour under
# test is the phase's own reasoning about the artifacts it produces. The seam
# is the script's documented `[repo-path]` positional.
#
# WHY CASE 2 EXISTS (forensic anchor, 2026-08-22):
# The phase is idempotent by design: an existing snapshot is left untouched
# unless --force. On that path it writes NOTHING, yet its Evidence Record
# asserted a fact about a file it had just declined to write:
#
#   "Gate 3 re-run confirmed the per-version snapshot exists at
#    .lava-ci-evidence/distribute-changelog/firebase-app-distribution/
#    1.9.9-1099.md (already-present), 1084 bytes of release notes for
#    Lava-Android-1.9.9-1099"
#
# 1084 is ${#SNAPSHOT_CONTENT} — the length of the text the script COMPOSED IN
# MEMORY and deliberately did not write. The file on disk was 0 bytes. The
# record described the release notes the phase would have produced as though
# they were the release notes that exist, and a reader auditing the distribute
# gate's inputs would have believed a 1084-byte notes file was in place.
#
# A 0-byte snapshot still satisfies firebase-distribute.sh's Gate 3, which is
# only `[[ -f "$SNAPSHOT_FILE" ]]` — an existence test an empty file passes.
# That is the same shape anti-bluff-validate.sh Rule 3 already rejects for
# raw_output_ref ("exists but is empty (0 bytes)"), so this phase refuses to
# certify it too.
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

VERSION="1.9.9"
CODE="1099"
CHANNEL="firebase-app-distribution"

# _new_fixture <name> — repo with a firebase-distribute.sh stand-in carrying
# the three drift-checked literals verbatim, plus a parseable app gradle file
# and a CHANGELOG.md. Prints its path.
_new_fixture() {
  local name="$1"
  local f="${WORKDIR}/${name}"
  mkdir -p "${f}/scripts" "${f}/app"
  git init -q -b master "$f"
  git -C "$f" config user.email "fixture@example.invalid"
  git -C "$f" config user.name "Fixture"

  cat > "${f}/scripts/firebase-distribute.sh" <<'FD'
#!/usr/bin/env bash
# Stand-in carrying the literals phase-05a's _assert_no_drift greps for.
GRADLE_VERSION_FILE="app/build.gradle.kts"
CHANGELOG_CHANNEL="firebase-app-distribution"
CHANGELOG_PATTERN_TMPL='Lava-Android-?APP_VERSION-?APP_VERSION_CODE|Lava-Android APP_VERSION \(APP_VERSION_CODE\)'
FD
  cat > "${f}/app/build.gradle.kts" <<G
android {
    defaultConfig {
        versionCode = ${CODE}
        versionName = "${VERSION}"
    }
}
G
  printf '# Changelog\n\n## Lava-Android-1.0.0-1000 — an older release\n\nolder notes\n' \
    > "${f}/CHANGELOG.md"
  git -C "$f" add -A >/dev/null 2>&1
  git -C "$f" commit -qm "fixture init" >/dev/null 2>&1
  printf '%s' "$f"
}

# _run <fixture> <run_id> — sets P5_RC, P5_OUT, P5_RECORD, P5_SNAPSHOT.
_run() {
  local f="$1" run_id="$2"
  ( cd "$f" && source "${REPO_ROOT}/scripts/pipeline/lib/run-report.sh" \
      && init_run_report "$run_id" "$(git -C "$f" rev-parse HEAD)" >/dev/null )
  local out="${WORKDIR}/p5a.log"
  set +e
  ( cd "$f" && bash "$PHASE05A" "$run_id" "$f" --app client ) >"$out" 2>&1
  P5_RC=$?
  set -e
  P5_OUT="$(cat "$out")"
  P5_RECORD="${f}/.lava-ci-evidence/pipeline-runs/${run_id}/phase-05a/hermetic-script/changelog-entry-client-${VERSION}-${CODE}.json"
  P5_SNAPSHOT="${f}/.lava-ci-evidence/distribute-changelog/${CHANNEL}/${VERSION}-${CODE}.md"
}

# _claimed_bytes — the byte count the record asserts, or empty.
_claimed_bytes() {
  jq -r '.assertion_summary' "$1" 2>/dev/null \
    | grep -oE '[0-9]+ bytes of release notes' | grep -oE '^[0-9]+' || true
}

echo "==============================================================="
echo "CASE 1: a fresh run really writes the snapshot -> PASS, honest size"
echo "(guards against a 'fix' that just makes every run fail)"
echo "==============================================================="

F1="$(_new_fixture fresh)"
_run "$F1" "2026-08-22T30-00-00Z"

if [[ "$P5_RC" -eq 0 ]]; then
  pass "fresh run -> phase exits 0"
else
  fail "fresh run -> phase exits ${P5_RC}; output: ${P5_OUT}"
fi
if [[ -s "$P5_SNAPSHOT" ]]; then
  pass "fresh run -> a non-empty snapshot really was written"
else
  fail "fresh run -> no non-empty snapshot at ${P5_SNAPSHOT}"
fi
if [[ -f "$P5_RECORD" ]]; then
  real1="$(wc -c < "$P5_SNAPSHOT" | tr -d '[:space:]')"
  claim1="$(_claimed_bytes "$P5_RECORD")"
  if [[ -n "$claim1" && "$claim1" == "$real1" ]]; then
    pass "the claimed byte count (${claim1}) equals the file's real size on disk (${real1})"
  else
    fail "the record claims '${claim1}' bytes but the snapshot on disk is ${real1} bytes"
  fi
else
  fail "no Evidence Record at ${P5_RECORD}; output: ${P5_OUT}"
fi

echo ""
echo "==============================================================="
echo "CASE 2 (LOAD-BEARING): snapshot already present and EMPTY"
echo "==============================================================="
echo "The phase writes nothing on this path. It must not describe the notes it"
echo "would have written as though they were on disk, and it must not certify"
echo "a 0-byte file as the version's release notes."
echo ""

F2="$(_new_fixture preexisting-empty)"
CD2="${F2}/.lava-ci-evidence/distribute-changelog/${CHANNEL}"
mkdir -p "$CD2"
: > "${CD2}/${VERSION}-${CODE}.md"
python3 - "${F2}/CHANGELOG.md" <<PY
import sys
p = sys.argv[1]; s = open(p).read()
open(p, "w").write(s.replace(
    "## Lava-Android-1.0.0-1000",
    "## Lava-Android-${VERSION}-${CODE} — a pre-existing entry\n\nplaceholder\n\n## Lava-Android-1.0.0-1000", 1))
PY

_run "$F2" "2026-08-22T31-00-00Z"

if [[ -f "$P5_SNAPSHOT" && ! -s "$P5_SNAPSHOT" ]]; then
  pass "fixture sanity: the snapshot on disk is present and 0 bytes, before and after"
else
  fail "fixture sanity: the snapshot is not a present-but-empty file; this case proves nothing"
fi
if grep -q 'already-present' <<< "$P5_OUT"; then
  pass "fixture sanity: the phase really took the already-present (writes nothing) path"
else
  fail "fixture sanity: the phase did not take the already-present path; output: ${P5_OUT}"
fi

if [[ -f "$P5_RECORD" ]]; then
  claim2="$(_claimed_bytes "$P5_RECORD")"
  sum2="$(jq -r '.assertion_summary' "$P5_RECORD")"
  if [[ -n "$claim2" && "$claim2" -gt 0 ]]; then
    fail "the record claims '${claim2} bytes of release notes' for a snapshot that is 0 bytes on disk — a byte count taken from text the phase composed and deliberately did not write: ${sum2}"
  else
    pass "the record does not claim a byte count that the empty snapshot does not have"
  fi
  r2="$(jq -r '.result' "$P5_RECORD")"
  if [[ "$r2" == "PASS" ]]; then
    fail "a 0-byte per-version snapshot was certified PASS. firebase-distribute.sh Gate 3 is only [[ -f ]], which an empty file passes; an empty release-notes file is not release notes."
  else
    pass "a 0-byte snapshot is not certified PASS (result=${r2})"
  fi
else
  # No record at all is an acceptable outcome only if the phase failed loudly.
  if [[ "$P5_RC" -ne 0 ]]; then
    pass "no record written and the phase failed loudly (exit ${P5_RC})"
  else
    fail "no Evidence Record and yet the phase exited 0; output: ${P5_OUT}"
  fi
fi
if [[ "$P5_RC" -ne 0 ]]; then
  pass "empty pre-existing snapshot -> phase exits non-zero (${P5_RC})"
else
  fail "empty pre-existing snapshot -> phase exits 0, reporting 'CHANGELOG entry + per-version snapshot present and gate-verified'"
fi

echo ""
echo "==============================================================="
echo "CASE 3: a NON-EMPTY pre-existing snapshot is honoured, honestly sized"
echo "(idempotency must survive the fix; only the false claim goes away)"
echo "==============================================================="

F3="$(_new_fixture preexisting-real)"
CD3="${F3}/.lava-ci-evidence/distribute-changelog/${CHANNEL}"
mkdir -p "$CD3"
printf '# Lava-Android-%s-%s\n\nHand-written notes an operator wrote earlier.\n' \
  "$VERSION" "$CODE" > "${CD3}/${VERSION}-${CODE}.md"
python3 - "${F3}/CHANGELOG.md" <<PY
import sys
p = sys.argv[1]; s = open(p).read()
open(p, "w").write(s.replace(
    "## Lava-Android-1.0.0-1000",
    "## Lava-Android-${VERSION}-${CODE} — a pre-existing entry\n\nplaceholder\n\n## Lava-Android-1.0.0-1000", 1))
PY

REAL3_BEFORE="$(wc -c < "${CD3}/${VERSION}-${CODE}.md" | tr -d '[:space:]')"
_run "$F3" "2026-08-22T32-00-00Z"
REAL3_AFTER="$(wc -c < "$P5_SNAPSHOT" | tr -d '[:space:]')"

if [[ "$REAL3_BEFORE" == "$REAL3_AFTER" ]]; then
  pass "idempotency preserved: the operator's existing snapshot was left untouched (${REAL3_AFTER} bytes)"
else
  fail "the existing snapshot was modified (${REAL3_BEFORE} -> ${REAL3_AFTER} bytes); --force is supposed to be required for that"
fi
if [[ "$P5_RC" -eq 0 ]]; then
  pass "non-empty pre-existing snapshot -> phase exits 0"
else
  fail "non-empty pre-existing snapshot -> phase exits ${P5_RC}; output: ${P5_OUT}"
fi
if [[ -f "$P5_RECORD" ]]; then
  claim3="$(_claimed_bytes "$P5_RECORD")"
  if [[ -n "$claim3" && "$claim3" == "$REAL3_AFTER" ]]; then
    pass "the claimed byte count (${claim3}) equals the pre-existing file's real size (${REAL3_AFTER})"
  else
    fail "the record claims '${claim3}' bytes but the pre-existing snapshot is ${REAL3_AFTER} bytes"
  fi
else
  fail "no Evidence Record at ${P5_RECORD}; output: ${P5_OUT}"
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

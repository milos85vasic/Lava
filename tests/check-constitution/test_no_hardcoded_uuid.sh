#!/usr/bin/env bash
# Hermetic test for scripts/scan-no-hardcoded-uuid.sh — the §6.R UUID scanner.
#
# Calls the standalone scanner DIRECTLY (Approach A from the original code
# review). The previous form invoked check-constitution.sh and had a silent-PASS
# fall-through that reported green when the checker failed on an UNRELATED
# earlier gate AND a real UUID violation existed — i.e. the gate this test
# claims to enforce was never evaluated, but the test reported PASS anyway. That
# is the canonical §6.J bluff: tests must guarantee the rule they cover holds.
#
# Originally this test ONLY ran the scanner against the live tree (a single
# positive assertion). That covered the happy path but proved nothing about the
# scanner's branches: it could not catch a refactor that broke detection, that
# dropped the nil-UUID exemption, or — the LVA-062 motivation — that dropped the
# `\.db$` exemption (added 2026-06; the §11.4.95 git-tracked docs/workable_items.db
# legitimately carries UUID-shaped keys). This rewrite adds throwaway-git-repo
# fixtures mirroring the sibling test_no_hardcoded_hostport.sh discipline so
# every branch is falsifiable:
#   1. live repo passes (the standing tree is clean)
#   2. NEGATIVE: a real (non-nil) UUID in production source IS flagged
#   3. POSITIVE: the IETF nil UUID 00000000-...-000000000000 is exempt
#   4. POSITIVE: a UUID inside a *Test.kt fixture is exempt (test-source rule)
#   5. POSITIVE (LVA-058/062): a UUID inside a tracked .db file is exempt
#      (proven falsifiable: dropping `\.db$` from the scanner makes this FAIL)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCANNER="$REPO_ROOT/scripts/scan-no-hardcoded-uuid.sh"

fail=0

# Assemble fixture UUIDs at runtime from parts so NO 36-char hex-dash literal
# sits in this tracked test file. The UUID scanner's exemption set does not
# include `^tests/`, so a literal UUID here would (correctly) make the scanner
# flag this very file in the live-tree pass — we must not weaken the scanner to
# accommodate the test, and we must not plant a real UUID in tracked source. A
# composed string is a genuine UUID to the scanner's regex once written into a
# fixture, but never appears as a literal in git-tracked text.
REAL_UUID="11111111-2222-3333-4444-$(printf '5%.0s' $(seq 12))"
NIL_UUID="$(printf '0%.0s' $(seq 8))-$(printf '0%.0s' $(seq 4))-$(printf '0%.0s' $(seq 4))-$(printf '0%.0s' $(seq 4))-$(printf '0%.0s' $(seq 12))"

# Build a throwaway git repo with the supplied file body, copy the real scanner
# in (its `cd "$(dirname "$0")/.."` then resolves to the fixture root), run it,
# and echo the exit code. Fully hermetic + falsifiable.
run_fixture() {
  local relpath="$1"; shift
  local body="$1"; shift
  local dir
  dir="$(mktemp -d)"
  (
    cd "$dir"
    git init -q
    git config user.email t@example.com
    git config user.name t
    mkdir -p "$(dirname "$relpath")" scripts
    printf '%s\n' "$body" > "$relpath"
    cp "$SCANNER" scripts/scan-no-hardcoded-uuid.sh
    git add -A
    git -c commit.gpgsign=false commit -qm fixture
    bash scripts/scan-no-hardcoded-uuid.sh >/dev/null 2>&1
    echo "$?"
  )
  rm -rf "$dir"
}

# Test 1: live repo passes.
if bash "$SCANNER" >/dev/null 2>&1; then
  echo "PASS test_live_repo_passes"
else
  echo "FAIL test_live_repo_passes: scanner flagged the live tree" >&2
  bash "$SCANNER" >&2 || true
  fail=1
fi

# Test 2 (falsifiability): a real (non-nil) UUID in production source IS flagged.
rc=$(run_fixture core/data/src/main/kotlin/lava/data/Cfg.kt \
  "const val ID = \"$REAL_UUID\"")
if [[ "$rc" == "1" ]]; then
  echo "PASS test_real_uuid_flagged"
else
  echo "FAIL test_real_uuid_flagged: expected 1, got $rc" >&2
  fail=1
fi

# Test 3: the IETF nil UUID is the canonical "no UUID" sentinel and is exempt.
rc=$(run_fixture core/data/src/main/kotlin/lava/data/Nil.kt \
  "const val NONE = \"$NIL_UUID\"")
if [[ "$rc" == "0" ]]; then
  echo "PASS test_nil_uuid_exempt"
else
  echo "FAIL test_nil_uuid_exempt: expected 0 (nil UUID is the empty sentinel), got $rc" >&2
  fail=1
fi

# Test 4: a UUID inside a *Test.kt fixture is exempt (synthetic test value).
rc=$(run_fixture core/data/src/test/kotlin/lava/data/CfgTest.kt \
  "val fixture = \"$REAL_UUID\"")
if [[ "$rc" == "0" ]]; then
  echo "PASS test_test_source_uuid_exempt"
else
  echo "FAIL test_test_source_uuid_exempt: expected 0 (test fixtures exempt), got $rc" >&2
  fail=1
fi

# Test 5 (LVA-058/062): a UUID inside a tracked .db file is exempt. The
# §11.4.95 git-tracked docs/workable_items.db is a binary SQLite DB whose rows
# legitimately carry UUID-shaped identifiers; the scanner gained a `\.db$`
# exemption for exactly this. Proven falsifiable: removing `|\.db$` from the
# scanner's exclusion regex makes this fixture FAIL (the UUID is no longer
# exempt). Without this case a refactor could silently drop the branch and the
# next DB sync would break the build with a false §6.R violation.
rc=$(run_fixture docs/workable_items.db \
  "row|$REAL_UUID|open")
if [[ "$rc" == "0" ]]; then
  echo "PASS test_db_file_exempt"
else
  echo "FAIL test_db_file_exempt: expected 0 (.db exempt), got $rc" >&2
  fail=1
fi

# Test 6 (nezha gate, 2026-06-16): a real device serial (UUID) inside a
# .lava-ci-evidence/**/running-devices.tsv attestation file is exempt. Genymotion
# / emulator instance IDs ARE UUIDs and ARE the §6.I per-AVD proof that a
# specific real device ran; redacting them would weaken the attestation
# (anti-bluff). Proven falsifiable: removing the
# `^\.lava-ci-evidence/.*running-devices\.tsv$` alternative from the scanner's
# exclusion regex makes this fixture FAIL.
rc=$(run_fixture .lava-ci-evidence/genymotion/1.3.10-1067-client-gate/running-devices.tsv \
  "$REAL_UUID	Pixel_8	online")
if [[ "$rc" == "0" ]]; then
  echo "PASS test_device_evidence_tsv_exempt"
else
  echo "FAIL test_device_evidence_tsv_exempt: expected 0 (device-serial evidence exempt), got $rc" >&2
  fail=1
fi

if [[ "$fail" == "0" ]]; then
  echo "ALL PASS test_no_hardcoded_uuid"
  exit 0
fi
echo "FAIL test_no_hardcoded_uuid (one or more cases failed)" >&2
exit 1

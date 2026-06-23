#!/usr/bin/env bash
# tests/firebase/test_assert_apk_versioncode.sh
#
# §6.A hermetic anti-bluff guard for the _assert_apk_versioncode() function
# added to scripts/firebase-distribute.sh on 2026-06-23.
#
# Forensic anchor: Lava-API-App 0.2.11-17's RELEASE channel shipped a STALE
# versionCode-16 binary because the rebuild failed mid-package (transient
# crashlytics-DNS). The APK was named *-17-release.apk but its binary manifest
# said versionCode='16'.  The filename-only picker passed; the content guard
# now added catches it.  THIS test is the §6.A contract test that proves
# the guard catches that class — per §6.A clause 4 (falsifiability rehearsal
# sub-test that re-introduces the historical bug and confirms rejection).
#
# CASES:
#   POSITIVE  — actual versionCode == APP_VERSION_CODE  →  return 0 + prints check line
#   NEGATIVE  — actual versionCode != APP_VERSION_CODE  →  exit 1 + FATAL §6.Z message
#               (this IS the §6.A falsifiability rehearsal; bug re-introduced via
#               fake aapt2 returning '16' while APP_VERSION_CODE=17)
#   EDGE-SKIP — aapt2 absent on PATH/SDK                →  WARN + return 0 (graceful)
#   EDGE-MISS — APK path empty / file absent             →  return 0 silently
#
# Hermetic: mktemp workspace, fake aapt2 on PATH, no network, no real .env,
#           no modification of scripts/firebase-distribute.sh.
#
# Strategy: extract _aapt2() + _assert_apk_versioncode() from the script via
# sed (line numbers confirmed stable at 364-385 as of 2026-06-23), source them
# into each subshell's environment, then override _aapt2() with a trivial stub
# that returns the path to a tiny fake `aapt2` wrapper we control.  APP_VERSION_CODE
# is set as a normal variable — the function reads it from the enclosing scope
# exactly as the script does.
#
# §6.A falsifiability rehearsal (inline record):
#   Mutation: fake aapt2 returns versionCode='16' while APP_VERSION_CODE=17
#   Observed failure:
#     FATAL §6.Z: api-app-0.2.11-17-release.apk has ACTUAL versionCode 16
#                 but this distribute is for 17.
#                 The filename says 17 but the binary manifest says 16 —
#                 a stale/mis-built APK (the wrong-binary class).
#   Reverted: yes — positive case re-confirmed with versionCode='17'

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DIST_SH="$REPO_ROOT/scripts/firebase-distribute.sh"

# ─── sanity: the script must exist ───────────────────────────────────────────
if [[ ! -f "$DIST_SH" ]]; then
    echo "FAIL: scripts/firebase-distribute.sh not found at $DIST_SH"
    exit 1
fi

# ─── extract the two functions from the script ───────────────────────────────
# Lines 364-385 contain _aapt2() and _assert_apk_versioncode().
# We verify the expected anchors are still there before relying on them.
if ! grep -q '^_aapt2()' "$DIST_SH"; then
    echo "FAIL §6.A: _aapt2() not found in $DIST_SH — function may have been renamed or removed."
    exit 1
fi
if ! grep -q '^_assert_apk_versioncode()' "$DIST_SH"; then
    echo "FAIL §6.A: _assert_apk_versioncode() not found in $DIST_SH — function may have been renamed or removed."
    exit 1
fi

# Extract exactly the two function bodies by finding their precise line range.
# _aapt2() starts at the first line matching '^_aapt2()'; the block ends at
# the closing '}' of _assert_apk_versioncode() — the first '^}$' at or after
# the _assert_apk_versioncode() definition line.
_AAPT2_LINE="$(grep -n '^_aapt2()' "$DIST_SH" | cut -d: -f1 | head -1)"
_ASSERT_LINE="$(grep -n '^_assert_apk_versioncode()' "$DIST_SH" | cut -d: -f1 | head -1)"
_ASSERT_END="$(awk "NR>=${_ASSERT_LINE} && /^\}$/{print NR; exit}" "$DIST_SH")"
if [[ -z "$_AAPT2_LINE" || -z "$_ASSERT_END" ]]; then
    echo "FAIL §6.A: could not locate function boundaries in $DIST_SH"
    exit 1
fi
FUNCTIONS_TEXT="$(sed -n "${_AAPT2_LINE},${_ASSERT_END}p" "$DIST_SH")"
if [[ -z "$FUNCTIONS_TEXT" ]]; then
    echo "FAIL §6.A: could not extract function bodies from $DIST_SH"
    exit 1
fi

# ─── scratch workspace ───────────────────────────────────────────────────────
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# A tiny fake APK file (content irrelevant; real aapt2 won't be called — we
# stub _aapt2() to return our fake binary).
FAKE_APK="$TMP/api-app-0.2.11-17-release.apk"
printf '\x50\x4b\x03\x04' > "$FAKE_APK"   # PK magic bytes — just needs to be a file

fails=0

# ─── helper: run _assert_apk_versioncode in an isolated subshell ─────────────
# $1 = the versionCode our fake aapt2 will claim ("" = simulate aapt2-absent)
# $2 = APP_VERSION_CODE we tell the function to expect
# $3 = APK path to pass (default: FAKE_APK)
run_guard() {
    local reported_code="$1"
    local expected_code="$2"
    local apk="${3:-$FAKE_APK}"

    # Build a fake aapt2 that echoes the controlled output
    local fake_bin_dir="$TMP/fakebin-$$-$RANDOM"
    mkdir -p "$fake_bin_dir"

    if [[ -n "$reported_code" ]]; then
        # Normal fake: outputs the `dump badging` line the function parses
        cat > "$fake_bin_dir/aapt2" <<FAKEEOF
#!/usr/bin/env bash
# fake aapt2 — returns controlled versionCode for test
if [[ "\$1" == "dump" && "\$2" == "badging" ]]; then
    echo "package: name='digital.vasic.lava.api' versionCode='${reported_code}' versionName='0.2.11'"
    exit 0
fi
exit 1
FAKEEOF
    else
        # Absent-aapt2 simulation: aapt2 exists on PATH but we make _aapt2()
        # return empty by overriding it after sourcing — see below.
        cat > "$fake_bin_dir/aapt2" <<'FAKEEOF'
#!/usr/bin/env bash
echo "package: name='dummy' versionCode='99' versionName='0.0'"
FAKEEOF
    fi
    chmod +x "$fake_bin_dir/aapt2"

    # Run in a subshell so exit 1 inside _assert_apk_versioncode is catchable
    (
        # Source the two functions
        eval "$FUNCTIONS_TEXT"

        # Override _aapt2() to return our fake binary (or empty for absent case)
        if [[ -n "$reported_code" ]]; then
            _aapt2() { echo "$fake_bin_dir/aapt2"; }
        else
            _aapt2() { echo ""; }   # simulate: no aapt2 found
        fi

        APP_VERSION_CODE="$expected_code"
        export APP_VERSION_CODE
        _assert_apk_versioncode "$apk"
    )
}

# ═════════════════════════════════════════════════════════════════════════════
# TEST 1 — POSITIVE: actual == expected  →  return 0 + prints the check line
# ═════════════════════════════════════════════════════════════════════════════
echo ""
echo "=== Test 1 (POSITIVE): actual versionCode == APP_VERSION_CODE ==="
OUT1="$(run_guard "17" "17" 2>&1)"
RC1=$?
echo "$OUT1"
if [[ "$RC1" -eq 0 ]]; then
    echo "[1a] PASS: exit code 0"
else
    echo "[1a] FAIL: expected exit 0, got $RC1"
    fails=$((fails+1))
fi
if echo "$OUT1" | grep -q '§6.Z content-check:'; then
    echo "[1b] PASS: content-check line printed"
else
    echo "[1b] FAIL: §6.Z content-check line absent from output"
    fails=$((fails+1))
fi
if echo "$OUT1" | grep -q '17 == 17'; then
    echo "[1c] PASS: output shows '17 == 17'"
else
    echo "[1c] FAIL: '17 == 17' not found in output"
    fails=$((fails+1))
fi

# ═════════════════════════════════════════════════════════════════════════════
# TEST 2 — NEGATIVE (§6.A FALSIFIABILITY REHEARSAL):
#   actual versionCode=16, APP_VERSION_CODE=17  →  exit 1 + FATAL §6.Z
#   This is the re-introduction of the historical wrong-binary bug
#   (api-app-0.2.11-17-release.apk whose manifest says versionCode='16').
# ═════════════════════════════════════════════════════════════════════════════
echo ""
echo "=== Test 2 (NEGATIVE / §6.A FALSIFIABILITY REHEARSAL): actual=16, expected=17 ==="
OUT2="$(run_guard "16" "17" 2>&1)"
RC2=$?
echo "$OUT2"
if [[ "$RC2" -ne 0 ]]; then
    echo "[2a] PASS: exit code non-zero ($RC2) — guard fired"
else
    echo "[2a] FAIL: exit code was 0 — guard did NOT fire for mismatched versionCode (BLUFF!)"
    fails=$((fails+1))
fi
if echo "$OUT2" | grep -q 'FATAL §6.Z:'; then
    echo "[2b] PASS: 'FATAL §6.Z:' message present"
else
    echo "[2b] FAIL: 'FATAL §6.Z:' message absent — guard is silent on mismatch (BLUFF!)"
    fails=$((fails+1))
fi
if echo "$OUT2" | grep -q 'ACTUAL versionCode 16'; then
    echo "[2c] PASS: message names the actual wrong versionCode (16)"
else
    echo "[2c] FAIL: message does not name actual versionCode"
    fails=$((fails+1))
fi
if echo "$OUT2" | grep -q 'this distribute is for 17'; then
    echo "[2d] PASS: message names the expected versionCode (17)"
else
    echo "[2d] FAIL: message does not name the expected versionCode"
    fails=$((fails+1))
fi
if echo "$OUT2" | grep -q 'stale/mis-built APK'; then
    echo "[2e] PASS: message names the failure class (stale/mis-built APK)"
else
    echo "[2e] FAIL: failure-class label absent from FATAL message"
    fails=$((fails+1))
fi

# ═════════════════════════════════════════════════════════════════════════════
# TEST 3 — EDGE: aapt2 absent  →  WARN + return 0 (graceful skip, no false block)
# ═════════════════════════════════════════════════════════════════════════════
echo ""
echo "=== Test 3 (EDGE-SKIP): aapt2 absent — must WARN and return 0 ==="
OUT3="$(run_guard "" "17" 2>&1)"
RC3=$?
echo "$OUT3"
if [[ "$RC3" -eq 0 ]]; then
    echo "[3a] PASS: exit code 0 (graceful skip when aapt2 absent)"
else
    echo "[3a] FAIL: exit code $RC3 — guard hard-blocked on absent aapt2 (should WARN+skip)"
    fails=$((fails+1))
fi
if echo "$OUT3" | grep -q 'WARN §6.Z:'; then
    echo "[3b] PASS: 'WARN §6.Z:' present for absent aapt2"
else
    echo "[3b] FAIL: no warning when aapt2 absent"
    fails=$((fails+1))
fi
if echo "$OUT3" | grep -q 'aapt2 not found'; then
    echo "[3c] PASS: warning message mentions 'aapt2 not found'"
else
    echo "[3c] FAIL: warning does not mention 'aapt2 not found'"
    fails=$((fails+1))
fi

# ═════════════════════════════════════════════════════════════════════════════
# TEST 4 — EDGE: empty APK path  →  return 0 silently (no APK to check yet)
# ═════════════════════════════════════════════════════════════════════════════
echo ""
echo "=== Test 4 (EDGE-MISS): empty APK path — must return 0 silently ==="
OUT4="$(run_guard "17" "17" "" 2>&1)"
RC4=$?
echo "${OUT4:-(no output)}"
if [[ "$RC4" -eq 0 ]]; then
    echo "[4a] PASS: exit code 0 for empty APK path"
else
    echo "[4a] FAIL: exit code $RC4 for empty APK path"
    fails=$((fails+1))
fi
if echo "$OUT4" | grep -q 'FATAL'; then
    echo "[4b] FAIL: FATAL message emitted for empty APK path (should be silent)"
    fails=$((fails+1))
else
    echo "[4b] PASS: no FATAL for empty APK path"
fi

# ═════════════════════════════════════════════════════════════════════════════
# TEST 5 — EDGE: non-existent file path  →  return 0 silently
# ═════════════════════════════════════════════════════════════════════════════
echo ""
echo "=== Test 5 (EDGE-MISS): non-existent file path — must return 0 silently ==="
OUT5="$(run_guard "17" "17" "/tmp/no-such-file-$RANDOM.apk" 2>&1)"
RC5=$?
echo "${OUT5:-(no output)}"
if [[ "$RC5" -eq 0 ]]; then
    echo "[5a] PASS: exit code 0 for non-existent APK path"
else
    echo "[5a] FAIL: exit code $RC5 for non-existent APK path"
    fails=$((fails+1))
fi

# ═════════════════════════════════════════════════════════════════════════════
# SUMMARY
# ═════════════════════════════════════════════════════════════════════════════
echo ""
if [[ "$fails" -eq 0 ]]; then
    echo "[firebase] OK: test_assert_apk_versioncode — all cases PASS (1-POSITIVE, 2-NEGATIVE/falsifiability, 3-WARN-skip, 4-empty-path, 5-absent-file)."
    exit 0
else
    echo "[firebase] FAIL: $fails assertion(s) failed in test_assert_apk_versioncode."
    exit 1
fi

# ─── §6.A FALSIFIABILITY REHEARSAL RECORD ────────────────────────────────────
# (inline — NOT re-run at test time; recorded per §6.A clause 4)
#
# Mutation applied: fake aapt2 in run_guard() returns versionCode='16'
#   while APP_VERSION_CODE=17 (Test 2 case).
#
# This IS the mutation — no separate production-code change needed; the test
# itself constructs the bug scenario mechanically (fake binary returns wrong code).
# The guard being tested is the production code path; the mutation is injected
# via the controlled fake aapt2 output.
#
# Observed failure (Test 2, confirmed before commit):
#   FATAL §6.Z: api-app-0.2.11-17-release.apk has ACTUAL versionCode 16
#               but this distribute is for 17.
#               The filename says 17 but the binary manifest says 16 —
#               a stale/mis-built APK (the wrong-binary class).
#               Rebuild the artifact cleanly …
#   [2a] PASS: exit code non-zero (1) — guard fired
#   [2b] PASS: 'FATAL §6.Z:' message present
#   [2c] PASS: message names the actual wrong versionCode (16)
#   [2d] PASS: message names the expected versionCode (17)
#   [2e] PASS: message names the failure class (stale/mis-built APK)
#
# Reverted: yes — positive case (Test 1, code 17==17) re-confirmed PASS.

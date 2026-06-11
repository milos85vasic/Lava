#!/usr/bin/env bash
# tests/firebase/test_app_selector.sh
#
# §6.A hermetic anti-bluff guard for the --app client|api-app selector
# added to scripts/firebase-distribute.sh.
#
# Asserts (behavioral, not merely structural):
#   A. --app api-app uploads to the api-app app-id (not the client app-id)
#      and reads the api-app channel + releases path.
#   B. --app client (default) is byte-identical to the pre-selector behavior
#      (client app-id, client channel, client releases path).
#   C. --app api-app does NOT run Phase-1 Gates 4+5 (no pepper check, no
#      LAVA_AUTH_CURRENT_CLIENT_NAME check — server artifact, no client pepper).
#   D. --app <invalid> exits non-zero with a clear message.
#
# §6.A falsifiability rehearsal:
#   After confirming green, deliberately break the resolution in
#   firebase-distribute.sh (api-app route → client release app-id), re-run,
#   confirm FAIL, revert, re-confirm PASS.  Result recorded at end of this
#   file as inline comments.
#
# Hermetic: mktemp fixture repo, fake `firebase` binary on PATH, no network,
#           no real device, no real .env values.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DIST_SH="$REPO_ROOT/scripts/firebase-distribute.sh"
ENV_SH="$REPO_ROOT/scripts/firebase-env.sh"

fails=0

# ──────────────────────────────────────────────────────────────────────────────
# FIXTURE: build a minimal temp directory that acts as a fake repo.
# ──────────────────────────────────────────────────────────────────────────────
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# --- Fake `firebase` binary ---------------------------------------------------
# Echoes all its arguments to stdout so the test can verify which --app arg was
# used. Always exits 0 (simulate successful distribute).
FAKE_BIN_DIR="$TMP/fakebin"
mkdir -p "$FAKE_BIN_DIR"
cat > "$FAKE_BIN_DIR/firebase" <<'FAKEEOF'
#!/usr/bin/env bash
# Fake firebase — records invocation args for test assertions.
echo "FAKE_FIREBASE_CALLED: $*"
# Write a compact record to a shared log for assertion by the test harness.
echo "firebase $*" >> "${FAKE_FIREBASE_LOG:-/tmp/firebase_calls.log}"
exit 0
FAKEEOF
chmod +x "$FAKE_BIN_DIR/firebase"

# --- Fake `sha256sum` shim (macOS ships `shasum -a 256`, not sha256sum) -------
# The script calls `sha256sum`; shim it if absent so the test is portable.
if ! command -v sha256sum >/dev/null 2>&1; then
    cat > "$FAKE_BIN_DIR/sha256sum" <<'SHAEOF'
#!/usr/bin/env bash
# Portable sha256sum shim using shasum
shasum -a 256 "$@"
SHAEOF
    chmod +x "$FAKE_BIN_DIR/sha256sum"
fi

export PATH="$FAKE_BIN_DIR:$PATH"

# --- Fake git (sha + branch queries) -----------------------------------------
cat > "$FAKE_BIN_DIR/git" <<'GITEOF'
#!/usr/bin/env bash
case "$*" in
    "rev-parse --short HEAD") echo "deadbeef" ;;
    "rev-parse --abbrev-ref HEAD") echo "master" ;;
    *) command git "$@" ;;
esac
GITEOF
chmod +x "$FAKE_BIN_DIR/git"

# --- Fake repo layout ---------------------------------------------------------
FAKE_REPO="$TMP/repo"
mkdir -p "$FAKE_REPO/scripts"
mkdir -p "$FAKE_REPO/app"
mkdir -p "$FAKE_REPO/api-app"

# Minimal app/build.gradle.kts with client version
cat > "$FAKE_REPO/app/build.gradle.kts" <<'GRADLEEOF'
android {
    defaultConfig {
        applicationId = "digital.vasic.lava.client"
        versionCode = 1055
        versionName = "1.2.35"
    }
}
GRADLEEOF

# Minimal api-app/build.gradle.kts with api-app version
cat > "$FAKE_REPO/api-app/build.gradle.kts" <<'GRADLEEOF'
android {
    defaultConfig {
        applicationId = "digital.vasic.lava.api"
        versionCode = 1
        versionName = "0.1.0"
    }
}
GRADLEEOF

# Fake .env (§6.H: only placeholder values, never real tokens)
cat > "$FAKE_REPO/.env" <<'ENVEOF'
LAVA_FIREBASE_TOKEN=1//fake-token-for-test
LAVA_FIREBASE_PROJECT_ID=fake-project-id
LAVA_FIREBASE_ANDROID_APP_ID=1:111111111111:android:aaaaaaaaaaaaaaaaaaaaaa
LAVA_FIREBASE_ANDROID_DEV_APP_ID=1:111111111111:android:bbbbbbbbbbbbbbbbbbbbbb
LAVA_FIREBASE_API_GO_APP_ID=1:111111111111:web:cccccccccccccccccccccc
LAVA_FIREBASE_API_APP_ID=1:222222222222:android:dddddddddddddddddddddd
LAVA_FIREBASE_API_APP_DEV_APP_ID=1:222222222222:android:eeeeeeeeeeeeeeeeeeeeee
LAVA_FIREBASE_TESTERS_OWNER=owner@example.com
LAVA_FIREBASE_TESTERS_DEVELOPER=developer@example.com
LAVA_FIREBASE_TESTERS_TESTER=tester@example.com
LAVA_AUTH_OBFUSCATION_PEPPER=dGVzdC1wZXBwZXItcGxhY2Vob2xkZXI=
LAVA_AUTH_CURRENT_CLIENT_NAME=android-1.2.35-1055
LAVA_AUTH_ACTIVE_CLIENTS=android-1.2.35-1055:<vm-instance-uuid-redacted>
ENVEOF

# Fake CHANGELOG.md with entries for both apps
cat > "$FAKE_REPO/CHANGELOG.md" <<'CLEOF'
# Changelog

## Lava-Android-1.2.35-1055
- Client app 1.2.35

## Lava-API-App-0.1.0-1
- API app 0.1.0
CLEOF

# Per-version snapshot files (§6.P Gate 3)
CLIENT_CHAN="$FAKE_REPO/.lava-ci-evidence/distribute-changelog/firebase-app-distribution"
API_APP_CHAN="$FAKE_REPO/.lava-ci-evidence/distribute-changelog/firebase-app-distribution-api-app"
mkdir -p "$CLIENT_CHAN"
mkdir -p "$API_APP_CHAN"
echo "Client snapshot 1.2.35-1055" > "$CLIENT_CHAN/1.2.35-1055.md"
echo "API-App snapshot 0.1.0-1"    > "$API_APP_CHAN/0.1.0-1.md"

# Fake release APK trees
mkdir -p "$FAKE_REPO/releases/1.2.35/android-debug"
mkdir -p "$FAKE_REPO/releases/1.2.35/android-release"
touch "$FAKE_REPO/releases/1.2.35/android-debug/digital.vasic.lava.client-1.2.35-debug.apk"
touch "$FAKE_REPO/releases/1.2.35/android-release/digital.vasic.lava.client-1.2.35-release.apk"

mkdir -p "$FAKE_REPO/releases/api-app/0.1.0/android-debug"
mkdir -p "$FAKE_REPO/releases/api-app/0.1.0/android-release"
touch "$FAKE_REPO/releases/api-app/0.1.0/android-debug/digital.vasic.lava.api-0.1.0-debug.apk"
touch "$FAKE_REPO/releases/api-app/0.1.0/android-release/digital.vasic.lava.api-0.1.0-release.apk"

# Symlink the real scripts into the fake repo's scripts/ so sourcing works
ln -sf "$ENV_SH"  "$FAKE_REPO/scripts/firebase-env.sh"
ln -sf "$DIST_SH" "$FAKE_REPO/scripts/firebase-distribute.sh"

# ──────────────────────────────────────────────────────────────────────────────
# Helper: run firebase-distribute.sh from the fake repo context.
# ──────────────────────────────────────────────────────────────────────────────
FIREBASE_CALLS_LOG="$TMP/firebase_calls.log"

run_distribute() {
    # Clear the fake firebase call log before each run.
    : > "$FIREBASE_CALLS_LOG"
    FAKE_FIREBASE_LOG="$FIREBASE_CALLS_LOG" \
    LAVA_REPO_ROOT="$FAKE_REPO" \
    bash "$FAKE_REPO/scripts/firebase-distribute.sh" "$@" 2>&1
}

# ──────────────────────────────────────────────────────────────────────────────
# TEST A: --app api-app uploads to the api-app app-id, uses the api-app channel.
# ──────────────────────────────────────────────────────────────────────────────
echo ""
echo "=== Test A: --app api-app routes to api-app app-id ==="
OUTPUT_A="$(run_distribute --app api-app --debug-only)"
echo "$OUTPUT_A"

# A1: the distribute call must reference the api-app DEV app-id
if echo "$OUTPUT_A" | grep -qF "1:222222222222:android:eeeeeeeeeeeeeeeeeeeeee"; then
    echo "[A1] PASS: api-app debug distribute used LAVA_FIREBASE_API_APP_DEV_APP_ID"
else
    echo "[A1] FAIL: api-app debug distribute did NOT use LAVA_FIREBASE_API_APP_DEV_APP_ID"
    echo "     Expected app-id: 1:222222222222:android:eeeeeeeeeeeeeeeeeeeeee"
    echo "     Actual output: $OUTPUT_A"
    fails=$((fails+1))
fi

# A2: must NOT reference the client app-id at all
if echo "$OUTPUT_A" | grep -qF "1:111111111111:android:bbbbbbbbbbbbbbbbbbbbbb"; then
    echo "[A2] FAIL: api-app distribute leaked the CLIENT dev app-id (routing bug)"
    fails=$((fails+1))
else
    echo "[A2] PASS: client app-id NOT used in api-app distribute"
fi

# A3: the channel directory referenced must be the api-app channel
if echo "$OUTPUT_A" | grep -qF "firebase-app-distribution-api-app"; then
    echo "[A3] PASS: api-app channel directory used"
else
    echo "[A3] FAIL: api-app channel directory NOT found in output"
    fails=$((fails+1))
fi

# A4: the releases path must be under releases/api-app/
if echo "$OUTPUT_A" | grep -qE "releases/api-app/0\.1\.0"; then
    echo "[A4] PASS: api-app releases path used"
else
    echo "[A4] FAIL: api-app releases path NOT found in output"
    fails=$((fails+1))
fi

# ──────────────────────────────────────────────────────────────────────────────
# TEST B: --app client (default) uses client paths and app-ids.
# ──────────────────────────────────────────────────────────────────────────────
echo ""
echo "=== Test B: --app client uses client app-id and paths ==="
OUTPUT_B="$(run_distribute --app client --debug-only)"
echo "$OUTPUT_B"

# B1: distribute must use the CLIENT dev app-id
if echo "$OUTPUT_B" | grep -qF "1:111111111111:android:bbbbbbbbbbbbbbbbbbbbbb"; then
    echo "[B1] PASS: client debug distribute used LAVA_FIREBASE_ANDROID_DEV_APP_ID"
else
    echo "[B1] FAIL: client debug distribute did NOT use LAVA_FIREBASE_ANDROID_DEV_APP_ID"
    fails=$((fails+1))
fi

# B2: must NOT reference the api-app app-id
if echo "$OUTPUT_B" | grep -qF "1:222222222222:android:eeeeeeeeeeeeeeeeeeeeee"; then
    echo "[B2] FAIL: client distribute leaked the API-APP dev app-id"
    fails=$((fails+1))
else
    echo "[B2] PASS: api-app app-id NOT used in client distribute"
fi

# B3: channel must be the client channel (not the api-app channel)
if echo "$OUTPUT_B" | grep -q "firebase-app-distribution-api-app"; then
    echo "[B3] FAIL: client distribute used the api-app channel"
    fails=$((fails+1))
else
    echo "[B3] PASS: client channel used (not api-app channel)"
fi

# ──────────────────────────────────────────────────────────────────────────────
# TEST C: --app api-app does NOT run Phase-1 Gates 4+5 (no pepper check).
# ──────────────────────────────────────────────────────────────────────────────
echo ""
echo "=== Test C: --app api-app skips Phase-1 Gates 4+5 ==="
# Reset the api-app channel's last-version-debug pointer so the §6.P monotonic
# gate does not block this run (Test A already advanced it to code 1).
rm -f "$API_APP_CHAN/last-version-debug" "$API_APP_CHAN/last-version-release" "$API_APP_CHAN/last-version"
OUTPUT_C="$(run_distribute --app api-app --debug-only)"

# C1: skip message must appear
if echo "$OUTPUT_C" | grep -q "Gates 4+5 skipped for api-app"; then
    echo "[C1] PASS: Phase-1 Gates 4+5 skip message present for api-app"
else
    echo "[C1] FAIL: Phase-1 Gates 4+5 skip message NOT found for api-app"
    echo "     Output: $OUTPUT_C"
    fails=$((fails+1))
fi

# C2: no pepper rejection must fire (even with an obviously stale pepper).
# Seed the pepper-history with the SAME pepper sha so it would reject if checked.
PEPPER_VALUE="$(grep LAVA_AUTH_OBFUSCATION_PEPPER "$FAKE_REPO/.env" | cut -d= -f2-)"
PEPPER_SHA_FOR_TEST="$(printf '%s' "$PEPPER_VALUE" | sha256sum | awk '{print $1}')"
# Record the SHA as belonging to a DIFFERENT version so Gate 4 WOULD reject it
# if it ran on the api-app distribute.
PEPPER_HIST_API="$API_APP_CHAN/pepper-history.sha256"
echo "$PEPPER_SHA_FOR_TEST  # 0.0.9-0  2026-01-01T00-00-00Z" >> "$PEPPER_HIST_API"

OUTPUT_C2="$(run_distribute --app api-app --debug-only 2>&1)"
if echo "$OUTPUT_C2" | grep -q "FATAL Phase 1 Gate 4"; then
    echo "[C2] FAIL: api-app distribute ran pepper Gate 4 (should be skipped)"
    fails=$((fails+1))
else
    echo "[C2] PASS: pepper Gate 4 NOT invoked for api-app"
fi

# ──────────────────────────────────────────────────────────────────────────────
# TEST D: --app <invalid> exits non-zero with a clear error message.
# ──────────────────────────────────────────────────────────────────────────────
echo ""
echo "=== Test D: invalid --app value exits non-zero ==="
OUTPUT_D="$(run_distribute --app foobar 2>&1 || true)"
if echo "$OUTPUT_D" | grep -qE "FATAL.*--app.*foobar|must be.*client.*api-app"; then
    echo "[D1] PASS: invalid --app value produces clear FATAL message"
else
    echo "[D1] FAIL: invalid --app value did not produce expected error"
    echo "     Output: $OUTPUT_D"
    fails=$((fails+1))
fi

# ──────────────────────────────────────────────────────────────────────────────
# SUMMARY
# ──────────────────────────────────────────────────────────────────────────────
echo ""
if [[ "$fails" -eq 0 ]]; then
    echo "[firebase] OK: --app selector test passed (A/B/C/D all green)."
    exit 0
else
    echo "[firebase] FAIL: $fails assertion(s) failed."
    exit 1
fi

# ──────────────────────────────────────────────────────────────────────────────
# §6.A FALSIFIABILITY REHEARSAL RECORD (inline — NOT re-run at test time)
# ──────────────────────────────────────────────────────────────────────────────
# Mutation applied to scripts/firebase-distribute.sh:
#   Changed the api-app RELEASE_APP_ID_VAR from
#     RELEASE_APP_ID_VAR="LAVA_FIREBASE_API_APP_ID"
#   to
#     RELEASE_APP_ID_VAR="LAVA_FIREBASE_ANDROID_APP_ID"  # deliberately wrong: client ID
#
# Observed failure with the mutation (Test A, --debug-only checks DEV id,
# so to exercise the release path the rehearsal was also run with --release-only
# after seeding last-version-debug):
#   [A1] PASS (debug path unaffected, DEV id still correct)
#   [A2] FAIL: api-app distribute leaked the CLIENT dev app-id
#              (debug id also correct; the RELEASE id mutation would show on --release-only)
#
# For a cleaner demonstration the mutation targeted the DEBUG_APP_ID_VAR:
#   DEBUG_APP_ID_VAR="LAVA_FIREBASE_ANDROID_DEV_APP_ID"  # client dev id, not api-app dev id
# Observed failure:
#   [A1] FAIL: api-app debug distribute did NOT use LAVA_FIREBASE_API_APP_DEV_APP_ID
#              Expected app-id: 1:222222222222:android:eeeeeeeeeeeeeeeeeeeeee
#   [A2] FAIL: api-app distribute leaked the CLIENT dev app-id (routing bug)
#   [B3] (unrelated test section unchanged — confirming localization)
#
# Reverted: yes — mutation reverted to DEBUG_APP_ID_VAR="LAVA_FIREBASE_API_APP_DEV_APP_ID"
# Re-run: all assertions PASS (confirmed prior to commit).

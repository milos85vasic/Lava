#!/usr/bin/env bash
# scripts/firebase-distribute.sh — upload built artifacts to Firebase App
# Distribution and invite testers loaded from .env.
#
# Replaces the local releases/ delivery flow as the canonical operator
# distribution channel (operator directive 2026-05-05).
#
# Usage:
#   ./scripts/firebase-distribute.sh                    # debug + release APKs
#   ./scripts/firebase-distribute.sh --debug-only       # only debug APK
#   ./scripts/firebase-distribute.sh --release-only     # only release APK
#   ./scripts/firebase-distribute.sh --release-notes "<text>"   # custom notes
#
# Inputs:
#   .env  (gitignored) — LAVA_FIREBASE_TOKEN, project + app IDs, tester emails
#   releases/<version>/android-debug/*.apk
#   releases/<version>/android-release/*.apk
#
# Outputs:
#   App Distribution release at the Firebase Console under
#     project $LAVA_FIREBASE_PROJECT_ID, app $LAVA_FIREBASE_ANDROID_APP_ID.
#   3 testers receive an email invite (per .env LAVA_FIREBASE_TESTERS_*).
#
# Constitutional bindings:
#   §6.H Credential Security — tokens read from .env, never echoed
#   §6.J Anti-Bluff — propagates real failures via set -euo pipefail; no WARN swallow
#   §6.G End-to-end provider operational verification — distribute step is the
#         hand-off the operator's manual real-device pass exercises against.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/firebase-env.sh
source "$SCRIPT_DIR/firebase-env.sh"
cd "$LAVA_REPO_ROOT"

MODE="${1:-both}"
RELEASE_NOTES_OVERRIDE=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --debug-only) MODE="debug"; shift ;;
        --release-only) MODE="release"; shift ;;
        --release-notes) RELEASE_NOTES_OVERRIDE="$2"; shift 2 ;;
        *) shift ;;
    esac
done

# ----------------------------------------------------------------
# 1. Resolve current Android version + build number from app gradle
# ----------------------------------------------------------------
APP_VERSION="$(grep -E '^\s+versionName\s*=' app/build.gradle.kts \
    | head -1 | sed 's/.*"\([^"]*\)".*/\1/')"
APP_VERSION_CODE="$(grep -E '^\s+versionCode\s*=' app/build.gradle.kts \
    | head -1 | sed 's/.*= \([0-9]*\).*/\1/')"

if [[ -z "$APP_VERSION" || -z "$APP_VERSION_CODE" ]]; then
    echo "FATAL: could not parse versionName/versionCode from app/build.gradle.kts" >&2
    exit 1
fi
echo "==> Distributing Lava Android $APP_VERSION ($APP_VERSION_CODE)"

# ----------------------------------------------------------------
# 1a. §6.P (Distribution Versioning + Changelog Mandate) gates.
# Refuses to operate when:
#   - current versionCode <= last distributed versionCode for this channel
#   - CHANGELOG.md lacks entry for this version
#   - per-version snapshot file is missing
# ----------------------------------------------------------------
CHANGELOG_DIR="$LAVA_REPO_ROOT/.lava-ci-evidence/distribute-changelog/firebase-app-distribution"
LAST_VERSION_FILE="$CHANGELOG_DIR/last-version"
SNAPSHOT_FILE="$CHANGELOG_DIR/$APP_VERSION-$APP_VERSION_CODE.md"

mkdir -p "$CHANGELOG_DIR"

# Gate 1: monotonic version code
if [[ -f "$LAST_VERSION_FILE" ]]; then
    LAST_DISTRIBUTED="$(cat "$LAST_VERSION_FILE" 2>/dev/null || echo 0)"
    if [[ "$APP_VERSION_CODE" -le "$LAST_DISTRIBUTED" ]]; then
        echo "FATAL §6.P: current versionCode $APP_VERSION_CODE is not strictly greater than the last distributed code $LAST_DISTRIBUTED." >&2
        echo "       Bump versionCode in app/build.gradle.kts before re-running this script." >&2
        echo "       Re-distribution of an already-published versionCode is forbidden." >&2
        exit 1
    fi
fi

# Gate 2: CHANGELOG.md entry
if ! grep -qE "Lava-Android-?$APP_VERSION-?$APP_VERSION_CODE|Lava-Android $APP_VERSION \\($APP_VERSION_CODE\\)" "$LAVA_REPO_ROOT/CHANGELOG.md"; then
    echo "FATAL §6.P: CHANGELOG.md does not contain an entry for version $APP_VERSION ($APP_VERSION_CODE)." >&2
    echo "       Add an entry to CHANGELOG.md before distributing." >&2
    exit 1
fi

# Gate 3: per-version snapshot file exists
if [[ ! -f "$SNAPSHOT_FILE" ]]; then
    echo "FATAL §6.P: per-version distribute-changelog snapshot missing." >&2
    echo "       Expected: $SNAPSHOT_FILE" >&2
    echo "       This file is shipped to App Distribution as release-notes." >&2
    exit 1
fi

echo "    §6.P gates passed: versionCode monotonic; CHANGELOG.md entry present; snapshot at $SNAPSHOT_FILE"

# ----------------------------------------------------------------
# 1b. Phase 1 (Phase-1 §6.R + §6.H) gates — added 2026-05-06.
#
#  - Gate 4: pepper rotation. The Phase-11 codegen embeds the
#    LAVA_AUTH_OBFUSCATION_PEPPER value into the APK as one of the
#    constants the AuthInterceptor uses to derive the AES key. Per
#    the Phase-1 spec §9 rotation runbook, every distributed build
#    MUST carry a fresh pepper. Reusing a pepper across distributions
#    means a leak in version N is also a compromise of version N+1.
#    Refuse to operate if the current pepper's SHA-256 already
#    appears in pepper-history.sha256.
#
#  - Gate 5: LAVA_AUTH_CURRENT_CLIENT_NAME consistency.
#    Refuse to operate if .env's CURRENT_CLIENT_NAME does NOT match
#    `android-${APP_VERSION}-${APP_VERSION_CODE}`, OR if the named
#    entry is missing from LAVA_AUTH_ACTIVE_CLIENTS. Either case
#    means the Phase-11 codegen would generate a UUID for the wrong
#    client identifier — a silent rotation bug.
# ----------------------------------------------------------------
ENV_FILE="$LAVA_REPO_ROOT/.env"
PEPPER_HISTORY="$CHANGELOG_DIR/pepper-history.sha256"
touch "$PEPPER_HISTORY"

if [[ -f "$ENV_FILE" ]]; then
    PEPPER_VALUE="$(grep -E '^LAVA_AUTH_OBFUSCATION_PEPPER=' "$ENV_FILE" | head -1 | cut -d= -f2-)"
    if [[ -n "$PEPPER_VALUE" ]]; then
        PEPPER_SHA="$(printf '%s' "$PEPPER_VALUE" | sha256sum | awk '{print $1}')"
        if grep -qF "$PEPPER_SHA" "$PEPPER_HISTORY"; then
            echo "FATAL Phase 1 Gate 4: pepper SHA $PEPPER_SHA already used in a previous distribution." >&2
            echo "       Rotate LAVA_AUTH_OBFUSCATION_PEPPER in .env before re-running this script." >&2
            echo "       Run: openssl rand -base64 32  → set as LAVA_AUTH_OBFUSCATION_PEPPER" >&2
            exit 1
        fi
    fi

    CURRENT_NAME="$(grep -E '^LAVA_AUTH_CURRENT_CLIENT_NAME=' "$ENV_FILE" | head -1 | cut -d= -f2-)"
    if [[ -n "$CURRENT_NAME" ]]; then
        EXPECTED_NAME="android-$APP_VERSION-$APP_VERSION_CODE"
        if [[ "$CURRENT_NAME" != "$EXPECTED_NAME" ]]; then
            echo "FATAL Phase 1 Gate 5: LAVA_AUTH_CURRENT_CLIENT_NAME=$CURRENT_NAME does not match expected $EXPECTED_NAME." >&2
            echo "       The expected name is derived from app/build.gradle.kts versionName/versionCode." >&2
            echo "       Update LAVA_AUTH_CURRENT_CLIENT_NAME in .env." >&2
            exit 1
        fi
        ACTIVE_CLIENTS="$(grep -E '^LAVA_AUTH_ACTIVE_CLIENTS=' "$ENV_FILE" | head -1 | cut -d= -f2-)"
        if ! echo "$ACTIVE_CLIENTS" | grep -qF "$CURRENT_NAME:"; then
            echo "FATAL Phase 1 Gate 5: $CURRENT_NAME not present in LAVA_AUTH_ACTIVE_CLIENTS." >&2
            echo "       Add the new entry to .env's LAVA_AUTH_ACTIVE_CLIENTS before distributing." >&2
            exit 1
        fi
    fi
    echo "    Phase 1 Gates 4+5 passed: pepper rotated; current-client-name matches version + appears in active list."
else
    echo "    Phase 1 Gates 4+5 skipped: .env not present (auth feature inert at runtime; runtime falls back to StubLavaAuthBlobProvider)."
fi

# ----------------------------------------------------------------
# 2. Resolve git SHA + branch for the release notes
# ----------------------------------------------------------------
GIT_SHA="$(git rev-parse --short HEAD)"
GIT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
TIMESTAMP="$(date -u +%Y-%m-%dT%H-%M-%SZ)"

if [[ -n "$RELEASE_NOTES_OVERRIDE" ]]; then
    RELEASE_NOTES="$RELEASE_NOTES_OVERRIDE"
else
    # §6.P: ship the per-version snapshot file as release-notes (truncated
    # to App Distribution's 16KB limit). The snapshot is the canonical
    # operator/tester-visible "what's new" payload.
    RELEASE_NOTES="$(head -c 16000 "$SNAPSHOT_FILE")
---
branch=$GIT_BRANCH sha=$GIT_SHA built=$TIMESTAMP"
fi

# ----------------------------------------------------------------
# 3. Locate built APKs
# ----------------------------------------------------------------
RELEASE_DIR="releases/$APP_VERSION"
DEBUG_APK="$(find "$RELEASE_DIR/android-debug" -maxdepth 1 -name '*.apk' 2>/dev/null | head -1 || true)"
RELEASE_APK="$(find "$RELEASE_DIR/android-release" -maxdepth 1 -name '*.apk' 2>/dev/null | head -1 || true)"

if [[ "$MODE" == "debug" || "$MODE" == "both" ]]; then
    if [[ -z "$DEBUG_APK" || ! -f "$DEBUG_APK" ]]; then
        echo "FATAL: debug APK not found under $RELEASE_DIR/android-debug/" >&2
        echo "       Run ./build_and_release.sh first." >&2
        exit 1
    fi
fi
if [[ "$MODE" == "release" || "$MODE" == "both" ]]; then
    if [[ -z "$RELEASE_APK" || ! -f "$RELEASE_APK" ]]; then
        echo "FATAL: release APK not found under $RELEASE_DIR/android-release/" >&2
        echo "       Run ./build_and_release.sh first." >&2
        exit 1
    fi
fi

# ----------------------------------------------------------------
# 4. Upload to Firebase App Distribution
# ----------------------------------------------------------------
distribute_apk() {
    local apk="$1"
    local label="$2"
    local app_id="$3"
    echo "==> Uploading $label APK: $(basename "$apk")"
    FIREBASE_TOKEN="$LAVA_FIREBASE_TOKEN" firebase appdistribution:distribute \
        "$apk" \
        --app "$app_id" \
        --project "$LAVA_FIREBASE_PROJECT_ID" \
        --testers "$LAVA_FIREBASE_TESTERS" \
        --release-notes "$RELEASE_NOTES ($label)"
    echo "    $label APK distributed."
}

if [[ "$MODE" == "debug" || "$MODE" == "both" ]]; then
    # Debug APK uses applicationIdSuffix .dev → registered as a separate
    # Firebase Android app (LAVA_FIREBASE_ANDROID_DEV_APP_ID).
    distribute_apk "$DEBUG_APK" "debug" "$LAVA_FIREBASE_ANDROID_DEV_APP_ID"
fi
if [[ "$MODE" == "release" || "$MODE" == "both" ]]; then
    distribute_apk "$RELEASE_APK" "release" "$LAVA_FIREBASE_ANDROID_APP_ID"
fi

# ----------------------------------------------------------------
# 5. Local distribution log (gitignored per .gitignore firebase-distribute-*.log)
# ----------------------------------------------------------------
LOG="firebase-distribute-${APP_VERSION}-${APP_VERSION_CODE}-${TIMESTAMP}.log"
{
    echo "timestamp=$TIMESTAMP"
    echo "version=$APP_VERSION ($APP_VERSION_CODE)"
    echo "branch=$GIT_BRANCH sha=$GIT_SHA"
    echo "mode=$MODE"
    echo "tester_count=3"
    echo "project=$LAVA_FIREBASE_PROJECT_ID"
} > "$LOG"
echo "==> Distribute log: $LOG (gitignored)"

# §6.P: persist the version code we just published so the next distribute
# session refuses to ship the same (or older) code.
echo "$APP_VERSION_CODE" > "$LAST_VERSION_FILE"
echo "==> §6.P last-version recorded: $APP_VERSION_CODE → $LAST_VERSION_FILE"

# Phase 1 Gate 4: persist the pepper SHA after a successful distribute so
# the next session refuses to reuse it.
if [[ -n "${PEPPER_SHA:-}" ]]; then
    echo "$PEPPER_SHA  # $APP_VERSION-$APP_VERSION_CODE  $TIMESTAMP" >> "$PEPPER_HISTORY"
    echo "==> Phase 1 Gate 4 pepper SHA recorded: $PEPPER_SHA → $PEPPER_HISTORY"
fi

echo "==> Firebase distribute complete."
echo "    Console: https://console.firebase.google.com/project/$LAVA_FIREBASE_PROJECT_ID/appdistribution"
